package umlopaquebehaviourbodyeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.HashMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;

import umlopaquebehaviourbodyeditor.LanguageMapping.LanguageDef;

/**
 * Provides code completion for a {@link StyledText} widget.
 * <p>
 * Shows a popup with keyword, type, and snippet suggestions that match
 * the word currently being typed. Triggers:
 * <ul>
 *   <li><b>Automatic</b>: after typing 2+ identifier characters</li>
 *   <li><b>Manual</b>: on Ctrl+Space</li>
 * </ul>
 * Keyboard: ↑↓ to navigate, Enter/Tab to accept, Escape to dismiss.
 */
public class CodeCompletionProvider {

    private final StyledText styledText;
    private Shell popupShell;
    private Table proposalTable;
    private final boolean darkTheme;

    /** Sorted set of all completable words for the current language. */
    private TreeSet<String> completionWords = new TreeSet<>();
    private TreeSet<String> extraWords = new TreeSet<>();
    private LanguageDef currentLangDef;
    private Map<String, Map<String, String>> typeMembers = new HashMap<>();
    
    private Map<String, EObject> globalElements = new HashMap<>();
    private Map<String, Map<String, EObject>> classElements = new HashMap<>();
    private ISelectionProvider selectionProvider;

    /** Tracks whether we're currently inserting a completion (to avoid re-triggering). */
    private boolean inserting = false;

    /** Minimum prefix length before auto-popup triggers. */
    private static final int AUTO_TRIGGER_LENGTH = 2;

    /** Maximum number of proposals shown. */
    private static final int MAX_PROPOSALS = 15;
    
    private static final String[] COMMON_METHODS = {
        "add", "remove", "clear", "size", "empty", "front", "back", "insert", "erase", 
        "push_back", "pop_back", "begin", "end", "find", "count", "length", "substr", "at"
    };

    private static final String[] MDE4CPP_COLLECTION_METHODS = {
        "add", "insert", "remove", "erase", "clear", "size", "empty", 
        "front", "back", "begin", "end", "at"
    };

    public CodeCompletionProvider(StyledText styledText, String language) {
        this.styledText = styledText;
        this.darkTheme = isDarkTheme(styledText.getDisplay());
        setLanguage(language);
        attachListeners();
    }

    /** Update the completion word list when the language changes. */
    public void setLanguage(String language) {
        this.currentLangDef = LanguageMapping.getLanguageDef(language);
        rebuildCompletionWords();
    }

    public void setExtraWords(Set<String> extraWords) {
        this.extraWords.clear();
        if (extraWords != null) {
            this.extraWords.addAll(extraWords);
        }
        rebuildCompletionWords();
    }

    public void setTypeMembers(Map<String, Map<String, String>> typeMembers) {
        this.typeMembers.clear();
        if (typeMembers != null) {
            this.typeMembers.putAll(typeMembers);
        }
    }

    public void setHyperlinkElements(Map<String, EObject> global, Map<String, Map<String, EObject>> classes, ISelectionProvider provider) {
        this.globalElements.clear();
        this.classElements.clear();
        if (global != null) this.globalElements.putAll(global);
        if (classes != null) this.classElements.putAll(classes);
        this.selectionProvider = provider;
    }

    private void rebuildCompletionWords() {
        completionWords.clear();
        if (currentLangDef != null && !currentLangDef.isPlainText()) {
            for (String kw : currentLangDef.keywords) completionWords.add(kw);
            for (String tp : currentLangDef.types)    completionWords.add(tp);
        }
        completionWords.addAll(extraWords);
        dismissPopup();
    }

    /** Clean up resources. */
    public void dispose() {
        dismissPopup();
    }

    // ------------------------------------------------------------------
    // Listener setup
    // ------------------------------------------------------------------

    private void attachListeners() {
        // ---- Intercept keys before StyledText processes them ----
        styledText.addVerifyKeyListener(e -> {
            // 1. Manual trigger (Ctrl+Space)
            // Note: On Mac, Ctrl+Space might be intercepted by OS. 
            if ((e.stateMask & SWT.CTRL) != 0 && (e.character == ' ' || e.keyCode == 32 || e.keyCode == SWT.SPACE)) {
                e.doit = false;
                showCompletions(true);
                return;
            }

            // 2. Navigation when popup is visible
            if (isPopupVisible()) {
                switch (e.keyCode) {
                    case SWT.ARROW_DOWN:
                        navigatePopup(1);
                        e.doit = false;
                        return;
                    case SWT.ARROW_UP:
                        navigatePopup(-1);
                        e.doit = false;
                        return;
                    case SWT.CR:
                    case SWT.KEYPAD_CR:
                    case SWT.TAB:
                        acceptSelected();
                        e.doit = false;
                        return;
                    case SWT.ESC:
                        dismissPopup();
                        e.doit = false;
                        return;
                }
            }
        });

        // ---- Auto-trigger on typing ----
        styledText.addModifyListener(e -> {
            if (inserting) return;
            String prefix = getCurrentPrefix();
            boolean isMemberAccess = isMemberAccessContext();
            
            if (prefix.length() >= AUTO_TRIGGER_LENGTH || isMemberAccess) {
                showCompletions(false);
            } else {
                dismissPopup();
            }
        });

        // ---- Dismiss on focus loss ----
        styledText.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                // Small delay to allow click on the popup itself
                styledText.getDisplay().timerExec(200, () -> {
                    if (popupShell != null && !popupShell.isDisposed() && !popupShell.isFocusControl()) {
                        dismissPopup();
                    }
                });
            }
        });

        // ---- MDE4CPP Smart Pointers: auto '.' to '->' ----
        styledText.addVerifyListener(e -> {
            if (inserting) return;
            if (e.text.equals(".") && currentLangDef != null && currentLangDef.name.equals("C++")) {
                String textBefore = styledText.getText().substring(0, e.start) + ".";
                String type = resolveContextTypeFromText(textBefore);
                if (type != null) {
                    e.text = "->";
                }
            }
        });

        // ---- Hyperlink and Tooltip logic ----
        styledText.addMouseMoveListener(e -> {
            boolean isMod = (e.stateMask & SWT.MOD1) != 0;
            boolean hasHyperlink = false;
            try {
                int offset = styledText.getOffsetAtLocation(new Point(e.x, e.y));
                String text = styledText.getText();
                int start = offset;
                int end = offset;
                while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
                while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                if (start < end) {
                    String word = text.substring(start, end);
                    String textBefore = text.substring(0, start).stripTrailing();
                    
                    EObject hyperlinkObj = resolveHyperlink(word, textBefore);
                    if (hyperlinkObj != null) {
                        hasHyperlink = true;
                    }

                    String type = resolveVariableType(word);
                    if (type != null && currentLangDef != null && currentLangDef.name.equals("C++")) {
                        styledText.setToolTipText("std::shared_ptr<" + type + ">");
                    } else if (type != null) {
                        styledText.setToolTipText(type);
                    } else {
                        styledText.setToolTipText(null);
                    }
                } else {
                    styledText.setToolTipText(null);
                }
            } catch (IllegalArgumentException ex) {
                styledText.setToolTipText(null);
            }

            if (isMod && hasHyperlink) {
                styledText.setCursor(styledText.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            } else {
                styledText.setCursor(null);
            }
        });

        styledText.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if ((e.keyCode & SWT.MODIFIER_MASK) == SWT.MOD1 || e.keyCode == SWT.COMMAND || e.keyCode == SWT.CTRL) {
                    styledText.setCursor(null);
                }
            }
        });

        styledText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                if ((e.stateMask & SWT.MOD1) != 0 && selectionProvider != null) {
                    try {
                        int offset = styledText.getOffsetAtLocation(new Point(e.x, e.y));
                        String text = styledText.getText();
                        int start = offset;
                        int end = offset;
                        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
                        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
                        if (start < end) {
                            String word = text.substring(start, end);
                            String textBefore = text.substring(0, start).stripTrailing();
                            EObject obj = resolveHyperlink(word, textBefore);
                            if (obj != null) {
                                selectionProvider.setSelection(new StructuredSelection(obj));
                            }
                        }
                    } catch (IllegalArgumentException ex) {
                        // ignore
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Completion logic
    // ------------------------------------------------------------------

    private void showCompletions(boolean explicit) {
        String prefix = getCurrentPrefix();
        boolean isMemberAccess = isMemberAccessContext();
        
        if (prefix.isEmpty() && !explicit && !isMemberAccess) {
            dismissPopup();
            return;
        }

        List<String> matches = findMatches(prefix);
        if (matches.isEmpty()) {
            dismissPopup();
            return;
        }

        // Don't show popup if the only match IS the prefix (already complete)
        if (matches.size() == 1 && matches.get(0).equals(prefix)) {
            dismissPopup();
            return;
        }

        showPopup(matches);
    }

    private List<String> findMatches(String prefix) {
        List<String> matches = new ArrayList<>();
        String lower = prefix.toLowerCase();

        boolean isMemberAccess = isMemberAccessContext();
        Set<String> allowedMembers = null;
        
        if (isMemberAccess) {
            allowedMembers = new TreeSet<>();
            for (Map<String, String> members : typeMembers.values()) {
                allowedMembers.addAll(members.keySet());
            }
            if (currentLangDef != null && currentLangDef.name.equals("C++")) {
                for (String m : COMMON_METHODS) allowedMembers.add(m);
            }
            
            String contextType = resolveContextType();
            if (contextType != null) {
                // If it's a known MDE4CPP collection, supply collection methods
                if (contextType.startsWith("Bag<") || contextType.startsWith("Set<") || 
                    contextType.startsWith("OrderedSet<") || contextType.startsWith("Sequence<") ||
                    contextType.startsWith("Union<") || contextType.startsWith("SubsetUnion<")) {
                    allowedMembers.clear(); // Only suggest collection methods
                    for (String m : MDE4CPP_COLLECTION_METHODS) allowedMembers.add(m);
                } else if (typeMembers.containsKey(contextType)) {
                    allowedMembers = new TreeSet<>(typeMembers.get(contextType).keySet());
                }
            }
        }

        for (String word : completionWords) {
            if (word.toLowerCase().startsWith(lower)) {
                if (allowedMembers != null && !allowedMembers.contains(word)) continue;
                matches.add(word);
                if (matches.size() >= MAX_PROPOSALS) break;
            }
        }
        
        // Also harvest words from the document dynamically
        if (matches.size() < MAX_PROPOSALS) {
            String[] docWords = styledText.getText().split("[^a-zA-Z0-9_]+");
            for (String dw : docWords) {
                if (dw.length() >= AUTO_TRIGGER_LENGTH && dw.toLowerCase().startsWith(lower) && !matches.contains(dw)) {
                    if (allowedMembers != null && !allowedMembers.contains(dw)) continue;
                    matches.add(dw);
                    if (matches.size() >= MAX_PROPOSALS) break;
                }
            }
        }
        
        // If it's a member access but we didn't find enough matches, inject the allowed members directly
        if (isMemberAccess && matches.size() < MAX_PROPOSALS && allowedMembers != null) {
            for (String am : allowedMembers) {
                if (am.toLowerCase().startsWith(lower) && !matches.contains(am)) {
                    matches.add(am);
                    if (matches.size() >= MAX_PROPOSALS) break;
                }
            }
        }
        
        return matches;
    }
    
    private boolean isMemberAccessContext() {
        int caretOffset = styledText.getCaretOffset();
        String text = styledText.getText();
        int start = caretOffset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int ptr = start - 1;
        while (ptr >= 0 && Character.isWhitespace(text.charAt(ptr))) ptr--;
        
        if (ptr >= 0 && text.charAt(ptr) == '.') return true;
        if (ptr >= 1 && text.charAt(ptr) == '>' && text.charAt(ptr-1) == '-') return true;
        return false;
    }

    private String resolveContextType() {
        int caretOffset = styledText.getCaretOffset();
        String text = styledText.getText();
        int start = caretOffset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        String textBeforeCaret = text.substring(0, start).stripTrailing();
        return resolveContextTypeFromText(textBeforeCaret);
    }

    private String resolveContextTypeFromText(String textBeforeCaret) {
        if (textBeforeCaret.endsWith("->")) {
            textBeforeCaret = textBeforeCaret.substring(0, textBeforeCaret.length() - 2);
        } else if (textBeforeCaret.endsWith(".")) {
            textBeforeCaret = textBeforeCaret.substring(0, textBeforeCaret.length() - 1);
        } else {
            return null;
        }
        
        textBeforeCaret = textBeforeCaret.stripTrailing();
        
        // Find the start of the expression.
        StringBuilder exp = new StringBuilder();
        int parens = 0;
        for (int i = textBeforeCaret.length() - 1; i >= 0; i--) {
            char c = textBeforeCaret.charAt(i);
            if (c == ')') parens++;
            else if (c == '(') parens--;
            else if (parens == 0 && !Character.isJavaIdentifierPart(c) && c != '-' && c != '>' && c != '.') {
                break;
            }
            exp.insert(0, c);
        }
        
        String expression = exp.toString();
        String[] parts = expression.split("->|\\.");
        if (parts.length == 0) return null;
        
        String currentType = null;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.endsWith("()")) {
                part = part.substring(0, part.length() - 2);
            }
            
            if (i == 0) {
                // Base variable
                currentType = resolveVariableType(part);
            } else {
                // Method or property on currentType
                if (currentType != null && typeMembers.containsKey(currentType)) {
                    Map<String, String> members = typeMembers.get(currentType);
                    currentType = members.get(part); // Get the return type!
                } else {
                    currentType = null;
                }
            }
            
            // Unwrap std::shared_ptr if present
            if (currentType != null && currentType.startsWith("std::shared_ptr<")) {
                currentType = currentType.substring(16, currentType.length() - 1);
            }
        }
        
        return currentType;
    }

    private EObject resolveHyperlink(String word, String textBeforeCaret) {
        String contextType = resolveContextTypeFromText(textBeforeCaret);
        if (contextType != null) {
            if (contextType.startsWith("std::shared_ptr<")) {
                contextType = contextType.substring(16, contextType.length() - 1);
            }
            if (classElements.containsKey(contextType)) {
                return classElements.get(contextType).get(word);
            }
        } else {
            return globalElements.get(word);
        }
        return null;
    }

    private String resolveVariableType(String variableName) {
        if (variableName == null || variableName.isBlank()) return null;
        String text = styledText.getText();
        
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("std::(?:weak|shared|unique)_ptr<\\s*([A-Za-z0-9_:<>,\\s]+)\\s*>\\s+" + java.util.regex.Pattern.quote(variableName) + "\\b");
        java.util.regex.Matcher m1 = p1.matcher(text);
        if (m1.find()) {
            String type = m1.group(1).trim();
            if (!type.contains("<")) { // Simple type
                return type.substring(type.lastIndexOf(':') + 1);
            }
            return type; // e.g. Bag<Author>
        }
        
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\b([A-Za-z0-9_:]+)\\s*\\**\\s+" + java.util.regex.Pattern.quote(variableName) + "\\b");
        java.util.regex.Matcher m2 = p2.matcher(text);
        while (m2.find()) {
            String type = m2.group(1);
            if (!type.equals("return") && !type.equals("new") && !type.equals("delete")) {
                return type.substring(type.lastIndexOf(':') + 1);
            }
        }
        
        return null;
    }

    /** Extracts the identifier being typed at the current caret position. */
    private String getCurrentPrefix() {
        int caretOffset = styledText.getCaretOffset();
        String text = styledText.getText();
        int start = caretOffset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        if (start == caretOffset) return "";
        return text.substring(start, caretOffset);
    }

    /** Returns the offset where the current prefix starts. */
    private int getPrefixStart() {
        int caretOffset = styledText.getCaretOffset();
        String text = styledText.getText();
        int start = caretOffset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    // ------------------------------------------------------------------
    // Popup management
    // ------------------------------------------------------------------

    private void showPopup(List<String> proposals) {
        if (popupShell == null || popupShell.isDisposed()) {
            createPopup();
        }

        proposalTable.removeAll();
        for (String p : proposals) {
            TableItem item = new TableItem(proposalTable, SWT.NONE);
            item.setText(p);
        }

        // Position below the current word
        Point caretLocation = styledText.getCaret().getLocation();
        Point displayPoint = styledText.toDisplay(caretLocation.x, caretLocation.y + styledText.getLineHeight());
        popupShell.setLocation(displayPoint);
        popupShell.setSize(280, Math.min(proposals.size() * 22 + 6, MAX_PROPOSALS * 22 + 6));

        if (!popupShell.isVisible()) {
            popupShell.setVisible(true);
        }

        // Select first item
        if (proposalTable.getItemCount() > 0) {
            proposalTable.select(0);
        }
    }

    private void createPopup() {
        popupShell = new Shell(styledText.getShell(), SWT.NO_TRIM | SWT.ON_TOP);
        popupShell.setLayout(new FillLayout());

        proposalTable = new Table(popupShell, SWT.SINGLE | SWT.FULL_SELECTION);
        proposalTable.setFont(styledText.getFont());

        if (darkTheme) {
            proposalTable.setBackground(new Color(new RGB(37, 37, 38)));
            proposalTable.setForeground(new Color(new RGB(212, 212, 212)));
        }

        // Double-click to accept
        proposalTable.addListener(SWT.DefaultSelection, e -> acceptSelected());

        // Single-click to accept
        proposalTable.addListener(SWT.Selection, e -> {
            // Delay so the selection registers first
            styledText.getDisplay().timerExec(50, this::acceptSelected);
        });
    }

    private void dismissPopup() {
        if (popupShell != null && !popupShell.isDisposed()) {
            popupShell.setVisible(false);
        }
    }

    private boolean isPopupVisible() {
        return popupShell != null && !popupShell.isDisposed() && popupShell.isVisible();
    }

    private void navigatePopup(int direction) {
        if (!isPopupVisible()) return;
        int count = proposalTable.getItemCount();
        if (count == 0) return;
        int current = proposalTable.getSelectionIndex();
        int next = Math.max(0, Math.min(count - 1, current + direction));
        proposalTable.select(next);
    }

    private void acceptSelected() {
        if (!isPopupVisible()) return;
        int idx = proposalTable.getSelectionIndex();
        if (idx < 0) {
            dismissPopup();
            return;
        }

        String selected = proposalTable.getItem(idx).getText();
        int prefixStart = getPrefixStart();
        int caretOffset = styledText.getCaretOffset();

        inserting = true;
        try {
            styledText.replaceTextRange(prefixStart, caretOffset - prefixStart, selected);
            styledText.setCaretOffset(prefixStart + selected.length());
        } finally {
            inserting = false;
        }

        dismissPopup();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isDarkTheme(Display display) {
        Color bg = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        double brightness = (bg.getRed() * 299.0 + bg.getGreen() * 587.0 + bg.getBlue() * 114.0) / 1000.0;
        return brightness < 128;
    }
}
