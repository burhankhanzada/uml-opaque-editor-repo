package umlopaquebehaviourbodyeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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

    /** Tracks whether we're currently inserting a completion (to avoid re-triggering). */
    private boolean inserting = false;

    /** Minimum prefix length before auto-popup triggers. */
    private static final int AUTO_TRIGGER_LENGTH = 2;

    /** Maximum number of proposals shown. */
    private static final int MAX_PROPOSALS = 15;

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
            if ((e.stateMask & SWT.CTRL) != 0 && e.keyCode == ' ') {
                e.doit = false;
                showCompletions();
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
            if (prefix.length() >= AUTO_TRIGGER_LENGTH) {
                showCompletions();
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
    }

    // ------------------------------------------------------------------
    // Completion logic
    // ------------------------------------------------------------------

    private void showCompletions() {
        String prefix = getCurrentPrefix();
        if (prefix.isEmpty()) {
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

        for (String word : completionWords) {
            if (word.toLowerCase().startsWith(lower)) {
                matches.add(word);
                if (matches.size() >= MAX_PROPOSALS) break;
            }
        }
        return matches;
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
