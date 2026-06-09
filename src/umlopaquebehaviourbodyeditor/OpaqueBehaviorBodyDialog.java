package umlopaquebehaviourbodyeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelectionProvider;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Dialog for editing the body entries of a UML OpaqueBehaviour.
 * Uses {@link StyledText} with {@link SyntaxHighlighter} for
 * keyword-based syntax highlighting — no JFace Text dependency.
 */
public class OpaqueBehaviorBodyDialog extends TitleAreaDialog {

    private org.eclipse.swt.graphics.Color umlTypeColor;
    private org.eclipse.swt.graphics.Color methodColor;
    private org.eclipse.swt.graphics.Color variableColor;

    private List<BodyEntry> entries = new ArrayList<>();
    private final String behaviourName;
    private int selectedIndex = -1;

    private TableViewer entryViewer;
    private SourceViewer sourceViewer;
    private StyledText codeText;
    private Combo languageCombo;
    private Button addButton;
    private Button removeButton;
    private Button upButton;
    private Button downButton;
    private Font monoFont;
    private TMPresentationReconciler tmReconciler;
    private CodeCompletionProvider completionProvider;
    private final Set<String> contextTypes;
    private final UmlModelDictionary dictionary;
    private final ISelectionProvider selectionProvider;
    
    private final SemanticHighlighter semanticHighlighter;
    private final UmlModelValidator modelValidator;

    private boolean suppressListener = false;

    public OpaqueBehaviorBodyDialog(Shell parentShell,
                                    List<String> bodies,
                                    List<String> languages,
                                    String name,
                                    Set<String> contextTypes,
                                    UmlModelDictionary dictionary,
                                    ISelectionProvider selectionProvider) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
        this.behaviourName = name;
        this.contextTypes = contextTypes;
        this.dictionary = dictionary;
        this.selectionProvider = selectionProvider;
        this.semanticHighlighter = new SemanticHighlighter(dictionary);
        this.modelValidator = new UmlModelValidator(dictionary);

        for (int i = 0; i < bodies.size(); i++) {
            String lang = (i < languages.size()) ? languages.get(i) : "";
            entries.add(new BodyEntry(lang, bodies.get(i)));
        }
    }

    // ---- Results ----

    public List<String> getBodies() {
        List<String> result = new ArrayList<>(entries.size());
        for (BodyEntry e : entries) result.add(e.body);
        return result;
    }

    public List<String> getLanguages() {
        List<String> result = new ArrayList<>(entries.size());
        for (BodyEntry e : entries) result.add(e.language);
        return result;
    }

    // ---- Dialog lifecycle ----

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Edit OpaqueBehaviour Body");
    }

    @Override
    protected Point getInitialSize() {
        return new Point(850, 700);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle("Edit OpaqueBehaviour Body");
        setMessage(behaviourName != null && !behaviourName.isBlank()
                ? "Editing body of: " + behaviourName
                : "Edit body entries of the selected OpaqueBehaviour");

        Composite main = new Composite(area, SWT.NONE);
        main.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        main.setLayout(new GridLayout(1, false));

        createEntrySection(main);
        createLanguageSection(main);
        createCodeSection(main);

        if (!entries.isEmpty()) {
            entryViewer.getTable().select(0);
            loadEntry(0);
        }
        updateButtonStates();

        return area;
    }

    @Override
    protected void okPressed() {
        commitCurrentEditor();
        super.okPressed();
    }

    @Override
    public boolean close() {
        if (monoFont != null && !monoFont.isDisposed()) {
            monoFont.dispose();
        }
        if (tmReconciler != null) {
            tmReconciler.uninstall();
        }
        if (completionProvider != null) {
            completionProvider.dispose();
        }
        return super.close();
    }

    // ---- UI construction ----

    private void createEntrySection(Composite parent) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText("Body entries:");

        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        row.setLayout(new GridLayout(2, false));

        entryViewer = new TableViewer(row, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        GridData tvGD = new GridData(SWT.FILL, SWT.FILL, true, true);
        tvGD.heightHint = 90;
        entryViewer.getTable().setLayoutData(tvGD);
        entryViewer.setContentProvider(ArrayContentProvider.getInstance());
        entryViewer.setLabelProvider(new BodyEntryLabelProvider());
        entryViewer.setInput(entries);
        entryViewer.addSelectionChangedListener(event -> {
            BodyEntry sel = (BodyEntry) event.getStructuredSelection().getFirstElement();
            if (sel != null) {
                int idx = entries.indexOf(sel);
                if (idx != selectedIndex) {
                    commitCurrentEditor();
                    loadEntry(idx);
                }
            }
        });

        Composite btnCol = new Composite(row, SWT.NONE);
        btnCol.setLayout(new GridLayout(1, true));
        btnCol.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, false));

        addButton    = createPushButton(btnCol, "Add");
        removeButton = createPushButton(btnCol, "Remove");
        upButton     = createPushButton(btnCol, "Up");
        downButton   = createPushButton(btnCol, "Down");

        addButton.addListener(SWT.Selection, e -> onAdd());
        removeButton.addListener(SWT.Selection, e -> onRemove());
        upButton.addListener(SWT.Selection, e -> onMove(-1));
        downButton.addListener(SWT.Selection, e -> onMove(1));
    }

    private void createLanguageSection(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        row.setLayout(new GridLayout(6, false));

        Label lbl = new Label(row, SWT.NONE);
        lbl.setText("Language:");

        languageCombo = new Combo(row, SWT.DROP_DOWN | SWT.READ_ONLY);
        languageCombo.setItems(LanguageMapping.getAllLanguageNames());
        languageCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        languageCombo.addModifyListener(e -> {
            if (selectedIndex >= 0 && selectedIndex < entries.size() && !suppressListener) {
                entries.get(selectedIndex).language = languageCombo.getText();
                updateSyntaxLanguage(languageCombo.getText());
                if (completionProvider != null) completionProvider.setLanguage(languageCombo.getText());
                entryViewer.refresh();
            }
        });

        Button formatBtn = new Button(row, SWT.PUSH);
        formatBtn.setText("Format Code");
        formatBtn.addListener(SWT.Selection, e -> {
            if (codeText != null && !codeText.isDisposed()) {
                String formatted = AutoFormatter.format(codeText.getText(), languageCombo.getText());
                codeText.setText(formatted);
            }
        });

        Label transLbl = new Label(row, SWT.NONE);
        transLbl.setText("  Translate to:");

        Combo targetLanguageCombo = new Combo(row, SWT.DROP_DOWN | SWT.READ_ONLY);
        targetLanguageCombo.setItems(LanguageMapping.getAllLanguageNames());
        if (targetLanguageCombo.getItemCount() > 0) targetLanguageCombo.select(0);

        Button translateBtn = new Button(row, SWT.PUSH);
        translateBtn.setText("Translate");
        translateBtn.addListener(SWT.Selection, e -> {
            if (codeText != null && !codeText.isDisposed()) {
                String sourceLang = languageCombo.getText();
                String targetLang = targetLanguageCombo.getText();
                String translated = CodeTranslator.translate(codeText.getText(), sourceLang, targetLang);
                
                codeText.setText(translated);
                languageCombo.setText(targetLang); // Update main language combo
            }
        });
    }

    /**
     * Creates the code editor section containing a StyledText widget wrapped in a SourceViewer.
     * This avoids heavy JFace Text dependencies while still allowing TM4E integration.
     */
    private void createCodeSection(Composite parent) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText("Body code:");

        sourceViewer = new SourceViewer(parent, null, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        sourceViewer.setDocument(new Document(""));
        codeText = sourceViewer.getTextWidget();

        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 350;
        gd.widthHint  = 600;
        codeText.setLayoutData(gd);

        // Prevent ESC from closing the dialog while in the code editor
        codeText.addTraverseListener(e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
            }
        });

        setupEditorFontAndColors(parent);
        setupLineNumbers();
        setupSyntaxHighlighting();

        // Code Completion
        completionProvider = new CodeCompletionProvider(codeText, "", dictionary);
        completionProvider.setHyperlinkElements(selectionProvider);

        // Re-highlight on every text change
        codeText.addModifyListener(e -> {
            if (!suppressListener) {
                if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                    entries.get(selectedIndex).body = codeText.getText();
                    entryViewer.update(entries.get(selectedIndex), null);
                }
            }
        });
    }

    private void setupEditorFontAndColors(Composite parent) {
        Display display = parent.getDisplay();
        // Monospace font
        monoFont = new Font(display, new FontData("Menlo", 12, SWT.NORMAL));
        codeText.setFont(monoFont);
        codeText.setTabs(4);
        
        // Add left margin to reserve space for line numbers
        codeText.setMargins(45, 5, 5, 5);

        // Theme-specific colors
        boolean dark = isDarkTheme(parent);
        final Color lineNumColor;
        final Color separatorColor;
        
        if (dark) {
            Color darkBg = new Color(display, new RGB(30, 30, 30));
            Color darkFg = new Color(display, new RGB(212, 212, 212));
            Color darkSelBg = new Color(display, new RGB(38, 79, 120));
            Color darkSelFg = new Color(display, new RGB(255, 255, 255));
            
            // TODO: Fix this issue upstream in the Eclipse IDE itself.
            // WORKAROUND: The Eclipse E4 CSS engine runs a delayed layout pass on macOS that 
            // aggressively overwrites StyledText backgrounds back to the OS-default grey.
            // Trick 1: We assign a dummy CSS ID so the engine's generic StyledText rules ignore this widget.
            codeText.setData("org.eclipse.e4.ui.css.id", "UMLOpaqueBehaviourEditorText");
            
            codeText.setBackground(darkBg);
            codeText.setForeground(darkFg);
            codeText.setSelectionBackground(darkSelBg);
            codeText.setSelectionForeground(darkSelFg);
            
            // Trick 2: We forcefully re-apply the background asynchronously (after the event loop processes 
            // the initial Shell layout) to guarantee our dark background survives any late CSS styling.
            display.asyncExec(() -> {
                if (codeText != null && !codeText.isDisposed()) {
                    codeText.setBackground(darkBg);
                    codeText.setForeground(darkFg);
                    codeText.setSelectionBackground(darkSelBg);
                    codeText.setSelectionForeground(darkSelFg);
                }
            });
            
            lineNumColor = new Color(display, new RGB(133, 133, 133));
            separatorColor = new Color(display, new RGB(64, 64, 64));
            
            umlTypeColor = new org.eclipse.swt.graphics.Color(display, 78, 201, 176);
            methodColor = new org.eclipse.swt.graphics.Color(display, 220, 220, 170);
            variableColor = new org.eclipse.swt.graphics.Color(display, 156, 220, 254);
        } else {
            lineNumColor = new Color(display, new RGB(43, 145, 175));
            separatorColor = new Color(display, new RGB(200, 200, 200));
            
            // VS Code light theme semantic colors
            umlTypeColor = new org.eclipse.swt.graphics.Color(display, 38, 127, 153); // Dark teal (#267f99)
            methodColor = new org.eclipse.swt.graphics.Color(display, 121, 94, 38);   // Dark yellow/brown (#795e26)
            variableColor = new org.eclipse.swt.graphics.Color(display, 0, 16, 128);  // Dark blue (#001080)
        }

        // Clean up colors
        codeText.addDisposeListener(e -> {
            if (lineNumColor != null) lineNumColor.dispose();
            if (separatorColor != null) separatorColor.dispose();
            if (umlTypeColor != null) umlTypeColor.dispose();
            if (methodColor != null) methodColor.dispose();
            if (variableColor != null) variableColor.dispose();
        });
        
        // Save these for line number painter
        codeText.setData("lineNumColor", lineNumColor);
        codeText.setData("separatorColor", separatorColor);
    }

    private void setupLineNumbers() {
        Color lineNumColor = (Color) codeText.getData("lineNumColor");
        Color separatorColor = (Color) codeText.getData("separatorColor");
        
        // Draw line numbers inside the reserved left margin
        codeText.addPaintListener(new LineNumberPainter(codeText, lineNumColor, separatorColor));
    }

    private void setupSyntaxHighlighting() {
        // Initialize TM4E Reconciler for generic syntax highlighting (keywords, strings, etc.)
        try {
            tmReconciler = new TMPresentationReconciler();
            sourceViewer.configure(new SourceViewerConfiguration() {
                @Override
                public IPresentationReconciler getPresentationReconciler(org.eclipse.jface.text.source.ISourceViewer sourceViewer) {
                    return tmReconciler;
                }
            });
        } catch (Throwable t) {
            t.printStackTrace();
        }

        if (sourceViewer instanceof org.eclipse.jface.text.ITextViewerExtension4 ext4) {
            // Apply semantic highlighting (custom colors for UML types, methods, and variables)
            // AFTER the TM4E grammar has been applied, overriding it where necessary.
            ext4.addTextPresentationListener(new org.eclipse.jface.text.ITextPresentationListener() {
                @Override
                public void applyTextPresentation(org.eclipse.jface.text.TextPresentation textPresentation) {
                    // Type Highlighting
                    java.util.List<TextRange> typeRanges = semanticHighlighter.getUMLTypeRanges(codeText.getText(), LanguageMapping.getLanguageDef(languageCombo.getText()));
                    for (TextRange tr : typeRanges) {
                        org.eclipse.swt.custom.StyleRange style = new org.eclipse.swt.custom.StyleRange(tr.offset, tr.length, umlTypeColor, null);
                        textPresentation.mergeStyleRange(style);
                    }
                    
                    // Method Highlighting
                    java.util.List<TextRange> methodRanges = semanticHighlighter.getMethodRanges(codeText.getText(), LanguageMapping.getLanguageDef(languageCombo.getText()));
                    for (TextRange mr : methodRanges) {
                        org.eclipse.swt.custom.StyleRange style = new org.eclipse.swt.custom.StyleRange(mr.offset, mr.length, methodColor, null);
                        textPresentation.mergeStyleRange(style);
                    }
                    
                    // Variable Highlighting
                    java.util.List<TextRange> varRanges = semanticHighlighter.getVariableRanges(codeText.getText(), LanguageMapping.getLanguageDef(languageCombo.getText()));
                    for (TextRange vr : varRanges) {
                        org.eclipse.swt.custom.StyleRange style = new org.eclipse.swt.custom.StyleRange(vr.offset, vr.length, variableColor, null);
                        textPresentation.mergeStyleRange(style);
                    }
                    
                    // Errors
                    java.util.List<TextRange> errors = modelValidator.validateUMLMemberAccess(codeText.getText(), LanguageMapping.getLanguageDef(languageCombo.getText()));
                    errors.addAll(modelValidator.validateSyntax(codeText.getText(), LanguageMapping.getLanguageDef(languageCombo.getText())));
                    
                    for (TextRange err : errors) {
                        org.eclipse.swt.custom.StyleRange style = new org.eclipse.swt.custom.StyleRange(err.offset, err.length, null, null);
                        style.underline = true;
                        style.underlineStyle = org.eclipse.swt.SWT.UNDERLINE_ERROR;
                        style.underlineColor = codeText.getDisplay().getSystemColor(org.eclipse.swt.SWT.COLOR_RED);
                        textPresentation.mergeStyleRange(style);
                    }
                }
            });
        }
    }

    private void updateSyntaxLanguage(String lang) {
        if (tmReconciler == null) return;
        try {
            String lower = lang == null ? "" : lang.toLowerCase();
            String ext = "txt";
            if (lower.startsWith("c++") || lower.equals("cpp")) ext = "cpp";
            else if (lower.equals("java")) ext = "java";
            else if (lower.equals("python") || lower.equals("py")) ext = "py";
            else if (lower.equals("c") || lower.matches("c[0-9]+")) ext = "c";
            
            IGrammar grammar = TMEclipseRegistryPlugin.getGrammarRegistryManager().getGrammarForFileExtension(ext);
            tmReconciler.setGrammar(grammar);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ---- Entry operations ----

    private void onAdd() {
        commitCurrentEditor();
        BodyEntry newEntry = new BodyEntry("CPP", "");
        entries.add(newEntry);
        entryViewer.refresh();
        entryViewer.setSelection(new StructuredSelection(newEntry), true);
        loadEntry(entries.size() - 1);
    }

    private void onRemove() {
        if (selectedIndex < 0 || selectedIndex >= entries.size()) return;
        entries.remove(selectedIndex);
        entryViewer.refresh();
        if (entries.isEmpty()) {
            selectedIndex = -1;
            suppressListener = true;
            codeText.setText("");
            languageCombo.setText("");
            suppressListener = false;
        } else {
            int newIdx = Math.min(selectedIndex, entries.size() - 1);
            entryViewer.getTable().select(newIdx);
            loadEntry(newIdx);
        }
        updateButtonStates();
    }

    private void onMove(int direction) {
        if (selectedIndex < 0) return;
        int target = selectedIndex + direction;
        if (target < 0 || target >= entries.size()) return;
        commitCurrentEditor();
        BodyEntry entry = entries.remove(selectedIndex);
        entries.add(target, entry);
        selectedIndex = target;
        entryViewer.refresh();
        entryViewer.getTable().select(target);
        updateButtonStates();
    }

    // ---- Sync ----

    private void commitCurrentEditor() {
        if (selectedIndex >= 0 && selectedIndex < entries.size() && codeText != null) {
            entries.get(selectedIndex).body = codeText.getText();
        }
    }

    private void loadEntry(int index) {
        selectedIndex = index;
        if (index < 0 || index >= entries.size()) return;
        BodyEntry entry = entries.get(index);
        
        updateSyntaxLanguage(entry.language);
        if (completionProvider != null) completionProvider.setLanguage(entry.language);

        suppressListener = true;
        try {
            sourceViewer.getDocument().set(entry.body);
            languageCombo.setText(entry.language);
        } finally {
            suppressListener = false;
        }
        
        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean hasSel = selectedIndex >= 0;
        removeButton.setEnabled(hasSel);
        upButton.setEnabled(hasSel && selectedIndex > 0);
        downButton.setEnabled(hasSel && selectedIndex < entries.size() - 1);
    }

    // ---- Helpers ----

    private static Button createPushButton(Composite parent, String label) {
        Button btn = new Button(parent, SWT.PUSH);
        btn.setText(label);
        btn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return btn;
    }

    private static boolean isDarkTheme(Composite parent) {
        Display display = parent.getDisplay();
        
        // 1. Check newer Eclipse API for system dark theme (fixes Mac SWT bugs)
        try {
            Boolean isDark = (Boolean) display.getClass().getMethod("isSystemDarkTheme").invoke(display);
            if (isDark != null) return isDark;
        } catch (Throwable t) {}

        // 2. Check Eclipse E4 CSS Theme
        try {
            Object themeEngine = display.getData("org.eclipse.e4.ui.css.swt.theme");
            if (themeEngine != null) {
                Object activeTheme = themeEngine.getClass().getMethod("getActiveTheme").invoke(themeEngine);
                if (activeTheme != null) {
                    String themeId = (String) activeTheme.getClass().getMethod("getId").invoke(activeTheme);
                    if (themeId != null) {
                        String lower = themeId.toLowerCase();
                        if (lower.contains("dark")) return true;
                        if (lower.contains("light")) return false;
                    }
                }
            }
        } catch (Throwable t) {}
        
        // 3. Fallback for macOS: Check native OS preference
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            try {
                Process p = Runtime.getRuntime().exec(new String[] {"defaults", "read", "-g", "AppleInterfaceStyle"});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.trim().equalsIgnoreCase("Dark")) {
                    return true;
                }
            } catch (Throwable t) {}
        }
        
        // 4. Fallback: Check parent composite background color
        Color bg = parent.getBackground();
        if (bg != null) {
            double brightness = getBrightness(bg);
            if (brightness < 128) return true;
        }
        
        return false;
    }

    private static double getBrightness(Color c) {
        return (c.getRed() * 299.0 + c.getGreen() * 587.0 + c.getBlue() * 114.0) / 1000.0;
    }

    // ---- Inner types ----
    private class BodyEntryLabelProvider extends LabelProvider {
        @Override
        public String getText(Object element) {
            if (!(element instanceof BodyEntry entry)) return super.getText(element);
            int idx = entries.indexOf(entry);
            String preview = entry.body.replace('\n', ' ').replace('\r', ' ').strip();
            if (preview.length() > 60) preview = preview.substring(0, 57) + "...";
            String lang = entry.language.isBlank() ? "(no language)" : entry.language;
            return String.format("%d: [%s]  %s", idx, lang, preview);
        }
    }
}
