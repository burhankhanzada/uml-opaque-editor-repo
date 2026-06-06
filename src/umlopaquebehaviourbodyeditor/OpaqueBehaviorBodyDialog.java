package umlopaquebehaviourbodyeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import org.eclipse.jface.dialogs.TitleAreaDialog;
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

    private final List<BodyEntry> entries = new ArrayList<>();
    private final String behaviourName;
    private int selectedIndex = -1;

    private TableViewer entryViewer;
    private StyledText codeText;
    private Combo languageCombo;
    private Button addButton;
    private Button removeButton;
    private Button upButton;
    private Button downButton;
    private Font monoFont;
    private SyntaxHighlighter highlighter;
    private CodeCompletionProvider completionProvider;
    private final Set<String> contextTypes;
    private final Set<String> autocompleteWords;
    private final Map<String, Set<String>> typeMembers;

    private boolean suppressListener = false;

    public OpaqueBehaviorBodyDialog(Shell parentShell,
                                    List<String> bodies,
                                    List<String> languages,
                                    String name,
                                    Set<String> contextTypes,
                                    Set<String> autocompleteWords,
                                    Map<String, Set<String>> typeMembers) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
        this.behaviourName = name;
        this.contextTypes = contextTypes;
        this.autocompleteWords = autocompleteWords;
        this.typeMembers = typeMembers;

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
        if (highlighter != null) {
            highlighter.dispose();
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
        row.setLayout(new GridLayout(2, false));

        Label lbl = new Label(row, SWT.NONE);
        lbl.setText("Language:");

        languageCombo = new Combo(row, SWT.DROP_DOWN);
        languageCombo.setItems(LanguageMapping.getAllLanguageNames());
        languageCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        languageCombo.addModifyListener(e -> {
            if (selectedIndex >= 0 && selectedIndex < entries.size() && !suppressListener) {
                entries.get(selectedIndex).language = languageCombo.getText();
                highlighter.setLanguage(languageCombo.getText());
                if (completionProvider != null) completionProvider.setLanguage(languageCombo.getText());
                entryViewer.refresh();
            }
        });
    }

    private void createCodeSection(Composite parent) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText("Body code:");

        codeText = new StyledText(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 350;
        gd.widthHint  = 600;
        codeText.setLayoutData(gd);

        // Monospace font
        Display display = parent.getDisplay();
        monoFont = new Font(display, new FontData("Menlo", 12, SWT.NORMAL));
        codeText.setFont(monoFont);
        codeText.setTabs(4);
        
        // Add left margin to reserve space for line numbers
        codeText.setMargins(45, 5, 5, 5);

        // Theme-specific colors
        boolean dark = isDarkTheme(display);
        final Color lineNumColor;
        final Color separatorColor;
        
        if (dark) {
            codeText.setBackground(new Color(new RGB(30, 30, 30)));
            codeText.setForeground(new Color(new RGB(212, 212, 212)));
            codeText.setSelectionBackground(new Color(new RGB(38, 79, 120)));
            codeText.setSelectionForeground(new Color(new RGB(255, 255, 255)));
            lineNumColor = new Color(new RGB(133, 133, 133));
            separatorColor = new Color(new RGB(64, 64, 64));
        } else {
            lineNumColor = new Color(new RGB(43, 145, 175));
            separatorColor = new Color(new RGB(200, 200, 200));
        }

        // Draw line numbers
        codeText.addPaintListener(e -> {
            int topIndex = codeText.getTopIndex();
            int lineHeight = codeText.getLineHeight();
            int visibleLines = (codeText.getClientArea().height + lineHeight - 1) / lineHeight;
            int bottomIndex = Math.min(topIndex + visibleLines, codeText.getLineCount() - 1);
            
            e.gc.setForeground(lineNumColor);
            
            for (int i = topIndex; i <= bottomIndex; i++) {
                int linePixel = codeText.getLinePixel(i);
                String num = String.valueOf(i + 1);
                Point extent = e.gc.stringExtent(num);
                // Right align within the left 45px margin area
                e.gc.drawString(num, 38 - extent.x, linePixel, true);
            }
            
            // Draw a separator line between numbers and text
            e.gc.setForeground(separatorColor);
            e.gc.drawLine(42, 0, 42, codeText.getClientArea().height);
        });

        // Clean up colors
        codeText.addDisposeListener(e -> {
            if (lineNumColor != null) lineNumColor.dispose();
            if (separatorColor != null) separatorColor.dispose();
        });

        // Syntax highlighter
        highlighter = new SyntaxHighlighter(codeText, "");
        if (contextTypes != null) {
            highlighter.setExtraTypes(contextTypes);
        }

        // Code Completion
        completionProvider = new CodeCompletionProvider(codeText, "");
        if (autocompleteWords != null) {
            completionProvider.setExtraWords(autocompleteWords);
        }
        if (typeMembers != null) {
            completionProvider.setTypeMembers(typeMembers);
        }

        // Re-highlight on every text change
        codeText.addModifyListener(e -> {
            if (!suppressListener) {
                if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                    entries.get(selectedIndex).body = codeText.getText();
                    entryViewer.update(entries.get(selectedIndex), null);
                }
                highlighter.highlight();
            }
        });
    }

    // ---- Entry operations ----

    private void onAdd() {
        commitCurrentEditor();
        BodyEntry newEntry = new BodyEntry("C++", "");
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
        suppressListener = true;
        try {
            codeText.setText(entry.body);
            languageCombo.setText(entry.language);
        } finally {
            suppressListener = false;
        }
        highlighter.setLanguage(entry.language);
        if (completionProvider != null) completionProvider.setLanguage(entry.language);
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

    private static boolean isDarkTheme(Display display) {
        Color bg = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        double brightness = (bg.getRed() * 299.0 + bg.getGreen() * 587.0 + bg.getBlue() * 114.0) / 1000.0;
        return brightness < 128;
    }

    // ---- Inner types ----

    static final class BodyEntry {
        String language;
        String body;
        BodyEntry(String language, String body) {
            this.language = language;
            this.body = body;
        }
    }

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
