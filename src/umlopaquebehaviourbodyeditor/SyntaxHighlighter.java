package umlopaquebehaviourbodyeditor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import umlopaquebehaviourbodyeditor.LanguageMapping.LanguageDef;

/**
 * Applies syntax highlighting to a {@link StyledText} widget
 * using {@link StyleRange}s. No JFace Text dependency needed.
 * <p>
 * Supports keywords, types, strings, comments, numbers, and preprocessor
 * directives. Auto-detects dark/light theme.
 */
public class SyntaxHighlighter {

    private final StyledText styledText;
    private LanguageDef langDef;

    // Colours
    private final Color keywordColor;
    private final Color typeColor;
    private final Color stringColor;
    private final Color commentColor;
    private final Color numberColor;
    private final Color preprocessorColor;
    private final Color methodColor;

    // Lookup sets for fast matching
    private Set<String> keywordSet = new HashSet<>();
    private Set<String> typeSet = new HashSet<>();
    private Set<String> extraTypeSet = new HashSet<>();

    public void setExtraTypes(Set<String> extraTypes) {
        extraTypeSet.clear();
        if (extraTypes != null) {
            extraTypeSet.addAll(extraTypes);
        }
        highlight();
    }

    public SyntaxHighlighter(StyledText styledText, String language) {
        this.styledText = styledText;
        boolean dark = isDarkTheme(styledText.getDisplay());

        if (dark) {
            keywordColor      = new Color(new RGB( 86, 156, 214));
            typeColor         = new Color(new RGB( 78, 201, 176));
            stringColor       = new Color(new RGB(206, 145, 120));
            commentColor      = new Color(new RGB(106, 153,  85));
            numberColor       = new Color(new RGB(181, 206, 168));
            preprocessorColor = new Color(new RGB(155, 155, 155));
            methodColor       = new Color(new RGB(220, 220, 170));
        } else {
            keywordColor      = new Color(new RGB(  0,   0, 255));
            typeColor         = new Color(new RGB( 43, 145, 175));
            stringColor       = new Color(new RGB(163,  21,  21));
            commentColor      = new Color(new RGB(  0, 128,   0));
            numberColor       = new Color(new RGB(  9, 134,  88));
            preprocessorColor = new Color(new RGB(128, 128, 128));
            methodColor       = new Color(new RGB(121,  94,  38));
        }

        setLanguage(language);
    }

    /** Change the language and re-highlight. */
    public void setLanguage(String language) {
        this.langDef = LanguageMapping.getLanguageDef(language);
        keywordSet = new HashSet<>();
        typeSet = new HashSet<>();
        for (String kw : langDef.keywords) keywordSet.add(kw);
        for (String tp : langDef.types) typeSet.add(tp);
        highlight();
    }

    /** Re-apply highlighting to the current text. */
    public void highlight() {
        String text = styledText.getText();
        if (text.isEmpty() || langDef.isPlainText()) {
            styledText.setStyleRanges(new StyleRange[0]);
            return;
        }

        List<StyleRange> ranges = new ArrayList<>();
        int len = text.length();
        int i = 0;

        while (i < len) {
            char c = text.charAt(i);

            // ---- Multi-line comments ----
            if (langDef.multiLineCommentStart != null
                    && text.startsWith(langDef.multiLineCommentStart, i)) {
                int end = text.indexOf(langDef.multiLineCommentEnd,
                        i + langDef.multiLineCommentStart.length());
                if (end == -1) end = len - langDef.multiLineCommentEnd.length();
                end += langDef.multiLineCommentEnd.length();
                ranges.add(styleRange(i, end - i, commentColor, SWT.ITALIC));
                i = end;
                continue;
            }

            // ---- Single-line comments ----
            if (langDef.singleLineComment != null
                    && text.startsWith(langDef.singleLineComment, i)) {
                int end = text.indexOf('\n', i);
                if (end == -1) end = len;
                ranges.add(styleRange(i, end - i, commentColor, SWT.ITALIC));
                i = end;
                continue;
            }

            // ---- Strings (double-quoted) ----
            if (c == '"') {
                // Check for Python triple-quotes
                if ("Python".equalsIgnoreCase(langDef.name)
                        && i + 2 < len && text.charAt(i+1) == '"' && text.charAt(i+2) == '"') {
                    int end = text.indexOf("\"\"\"", i + 3);
                    if (end == -1) end = len - 3;
                    end += 3;
                    ranges.add(styleRange(i, end - i, stringColor, SWT.NONE));
                    i = end;
                    continue;
                }
                int end = findStringEnd(text, i, '"');
                ranges.add(styleRange(i, end - i, stringColor, SWT.NONE));
                i = end;
                continue;
            }

            // ---- Strings (single-quoted) ----
            if (c == '\'') {
                if ("Python".equalsIgnoreCase(langDef.name)
                        && i + 2 < len && text.charAt(i+1) == '\'' && text.charAt(i+2) == '\'') {
                    int end = text.indexOf("'''", i + 3);
                    if (end == -1) end = len - 3;
                    end += 3;
                    ranges.add(styleRange(i, end - i, stringColor, SWT.NONE));
                    i = end;
                    continue;
                }
                int end = findStringEnd(text, i, '\'');
                ranges.add(styleRange(i, end - i, stringColor, SWT.NONE));
                i = end;
                continue;
            }

            // ---- Preprocessor directives ----
            if (langDef.preprocessorPrefix != null && c == langDef.preprocessorPrefix.charAt(0)) {
                // Must be at start of line
                if (i == 0 || text.charAt(i - 1) == '\n') {
                    int end = text.indexOf('\n', i);
                    if (end == -1) end = len;
                    ranges.add(styleRange(i, end - i, preprocessorColor, SWT.NONE));
                    i = end;
                    continue;
                }
            }

            // ---- Numbers ----
            if (Character.isDigit(c) && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))) {
                int end = i + 1;
                while (end < len && (Character.isDigit(text.charAt(end))
                        || text.charAt(end) == '.' || text.charAt(end) == 'x'
                        || text.charAt(end) == 'X' || text.charAt(end) == 'f'
                        || text.charAt(end) == 'L'
                        || (text.charAt(end) >= 'a' && text.charAt(end) <= 'f')
                        || (text.charAt(end) >= 'A' && text.charAt(end) <= 'F'))) {
                    end++;
                }
                ranges.add(styleRange(i, end - i, numberColor, SWT.NONE));
                i = end;
                continue;
            }

            // ---- Identifiers (keywords / types) ----
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < len && Character.isJavaIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end);
                if (keywordSet.contains(word)) {
                    ranges.add(styleRange(i, end - i, keywordColor, SWT.BOLD));
                } else if (typeSet.contains(word) || extraTypeSet.contains(word)) {
                    ranges.add(styleRange(i, end - i, typeColor, SWT.NONE));
                } else {
                    // Check if it's a method call (followed by '(' ignoring whitespace)
                    int next = end;
                    while (next < len && Character.isWhitespace(text.charAt(next))) {
                        next++;
                    }
                    if (next < len && text.charAt(next) == '(') {
                        ranges.add(styleRange(i, end - i, methodColor, SWT.NONE));
                    }
                }
                i = end;
                continue;
            }

            i++;
        }

        styledText.setStyleRanges(ranges.toArray(StyleRange[]::new));
    }

    /** Dispose colour resources. */
    public void dispose() {
        keywordColor.dispose();
        typeColor.dispose();
        stringColor.dispose();
        commentColor.dispose();
        numberColor.dispose();
        preprocessorColor.dispose();
        methodColor.dispose();
    }

    // ---- Helpers ----

    private static int findStringEnd(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == quote) return i + 1;
            if (c == '\n') return i; // unterminated string
            i++;
        }
        return text.length();
    }

    private static StyleRange styleRange(int start, int length, Color color, int style) {
        StyleRange sr = new StyleRange();
        sr.start = start;
        sr.length = length;
        sr.foreground = color;
        sr.fontStyle = style;
        return sr;
    }

    private static boolean isDarkTheme(Display display) {
        Color bg = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        double brightness = (bg.getRed() * 299.0 + bg.getGreen() * 587.0 + bg.getBlue() * 114.0) / 1000.0;
        return brightness < 128;
    }
}
