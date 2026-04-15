package dev.m1le.mjrmanager.editor;

import javafx.application.Platform;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.*;

public class JavaSyntaxHighlighter {

    private static final int MAX_SIZE = 100_000;

    private static final String[] KEYWORDS = {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "var", "record",
        "sealed", "permits", "yield", "true", "false", "null"
    };

    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
    private static final String STRING_PATTERN = "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"";
    private static final String COMMENT_PATTERN = "//[^\n]*|/\\*.*?\\*/";
    private static final String NUMBER_PATTERN = "\\b\\d+\\.?\\d*[fFdDlL]?\\b";
    private static final String ANNOTATION_PATTERN = "@\\w+";
    private static final String CLASS_PATTERN = "\\b[A-Z][a-zA-Z0-9_$]*\\b";
    private static final String IDENTIFIER_PATTERN = "\\b[a-zA-Z_][a-zA-Z0-9_]*\\b";

    private static final Pattern PATTERN = Pattern.compile(
        "(?<COMMENT>" + COMMENT_PATTERN + ")"
        + "|(?<KEYWORD>" + KEYWORD_PATTERN + ")"
        + "|(?<ANNOTATION>" + ANNOTATION_PATTERN + ")"
        + "|(?<STRING>" + STRING_PATTERN + ")"
        + "|(?<NUMBER>" + NUMBER_PATTERN + ")"
        + "|(?<CLASS>" + CLASS_PATTERN + ")"
        + "|(?<IDENTIFIER>" + IDENTIFIER_PATTERN + ")"
    );

    public static void applyHighlighting(CodeArea codeArea, String initialText) {
        if (initialText == null || initialText.length() > MAX_SIZE) {
            return;
        }

        Platform.runLater(() -> {
            try {
                codeArea.setStyleSpans(0, computeHighlighting(initialText));
            } catch (Exception e) {
                System.err.println("✗ Ошибка подсветки: " + e.getMessage());
            }
        });

        codeArea.multiPlainChanges()
            .successionEnds(Duration.ofMillis(500))
            .subscribe(ignore -> {
                String text = codeArea.getText();
                if (text.length() <= MAX_SIZE) {
                    codeArea.setStyleSpans(0, computeHighlighting(text));
                }
            });
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        while (matcher.find()) {
            String styleClass =
                matcher.group("COMMENT") != null ? "comment" :
                matcher.group("KEYWORD") != null ? "keyword" :
                matcher.group("ANNOTATION") != null ? "annotation" :
                matcher.group("STRING") != null ? "string" :
                matcher.group("NUMBER") != null ? "number" :
                matcher.group("CLASS") != null ? "classname" :
                matcher.group("IDENTIFIER") != null ? "plain" :
                null;

            int gap = matcher.start() - lastKwEnd;
            if (gap > 0) {
                spansBuilder.add(Collections.singleton("plain"), gap);
            }
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }

        int remaining = text.length() - lastKwEnd;
        if (remaining > 0) {
            spansBuilder.add(Collections.singleton("plain"), remaining);
        }

        if (lastKwEnd == 0 && text.length() > 0) {
            spansBuilder.add(Collections.singleton("plain"), text.length());
        }

        return spansBuilder.create();
    }

    public static boolean canHighlight(int length) {
        return length <= MAX_SIZE;
    }
}