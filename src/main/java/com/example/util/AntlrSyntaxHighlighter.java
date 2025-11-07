package com.example.util;

import com.example.antlr.java.Java20Lexer;
import com.example.antlr.python.Python3Lexer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AntlrSyntaxHighlighter {

    private final ExecutorService executor;
    private final CodeArea codeArea;
    private final String fileExtension;

    public AntlrSyntaxHighlighter(CodeArea codeArea, String fileExtension) {
        this.codeArea = codeArea;
        this.fileExtension = fileExtension;
        this.executor = Executors.newSingleThreadExecutor();

        codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(200))
                .subscribe(ignore -> highlight());

        highlight(); // 초기 하이라이팅
    }

    public void highlight() {
        String text = codeArea.getText();
        if (text.isEmpty()) {
            return;
        }

        HighlightingTask task = new HighlightingTask(text);
        executor.submit(task);
    }

    public void shutdown() {
        executor.shutdown();
    }

    private class HighlightingTask extends Task<StyleSpans<Collection<String>>> {
        private final String text;

        public HighlightingTask(String text) {
            this.text = text;
        }

        @Override
        protected StyleSpans<Collection<String>> call() {
            return computeHighlighting(text);
        }

        @Override
        protected void succeeded() {
            Platform.runLater(() -> {
                if (getValue() != null) {
                    codeArea.setStyleSpans(0, getValue());
                }
            });
        }
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        Lexer lexer = createLexer(text);
        if (lexer == null) {
            System.out.println("DEBUG: No lexer found for extension " + fileExtension);
            StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
            spansBuilder.add(Collections.singleton("text"), text.length());
            return spansBuilder.create();
        }

        System.out.println("DEBUG: Highlighting with " + lexer.getClass().getSimpleName());
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastKwEnd = 0;

        for (Token token : lexer.getAllTokens()) {
            String styleClass = getStyleClassForToken(token);
            // DEBUG: Print token info
            System.out.println("Token: '" + token.getText() + "', Type: " + lexer.getRuleNames()[token.getType() - 1] + ", Style: " + styleClass);

            spansBuilder.add(Collections.singleton("text"), token.getStartIndex() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), token.getStopIndex() - token.getStartIndex() + 1);
            lastKwEnd = token.getStopIndex() + 1;
        }
        spansBuilder.add(Collections.singleton("text"), text.length() - lastKwEnd);
        return spansBuilder.create();
    }

    private Lexer createLexer(String text) {
        switch (fileExtension) {
            case "java":
                return new Java20Lexer(CharStreams.fromString(text));
            case "py":
                return new Python3Lexer(CharStreams.fromString(text));
            default:
                return null;
        }
    }

    private String getStyleClassForToken(Token token) {
        Lexer lexer = (Lexer) token.getTokenSource();
        String[] ruleNames = lexer.getRuleNames();
        String ruleName = ruleNames[token.getType() - 1];

        // 토큰 타입(규칙 이름)에 따라 CSS 스타일 클래스를 반환합니다.
        // 이 부분은 style.css와 연동하여 더 정교하게 만들어야 합니다.
        switch (ruleName) {
            case "MODULE": case "REQUIRES": case "EXPORTS": case "OPENS": case "USES": case "PROVIDES":
            case "ABSTRACT": case "ASSERT": case "BOOLEAN": case "BREAK": case "BYTE": case "CASE":
            case "CATCH": case "CHAR": case "CLASS": case "CONST": case "CONTINUE": case "DEFAULT":
            case "DO": case "DOUBLE": case "ELSE": case "ENUM": case "EXTENDS": case "FINAL":
            case "FINALLY": case "FLOAT": case "FOR": case "IF": case "GOTO": case "IMPLEMENTS":
            case "IMPORT": case "INSTANCEOF": case "INT": case "INTERFACE": case "LONG": case "NATIVE":
            case "NEW": case "PACKAGE": case "PRIVATE": case "PROTECTED": case "PUBLIC": case "RETURN":
            case "SHORT": case "STATIC": case "STRICTFP": case "SUPER": case "SWITCH": case "SYNCHRONIZED":
            case "THIS": case "THROW": case "THROWS": case "TRANSIENT": case "TRY": case "VOID":
            case "VOLATILE": case "WHILE":
            // Python Keywords
            case "AND": case "AS": case "AWAIT": case "ASYNC": case "DEL":
            case "ELIF": case "EXCEPT": case "FROM": case "GLOBAL": case "IS":
            case "LAMBDA": case "NONLOCAL": case "NOT": case "OR": case "PASS":
            case "RAISE": case "YIELD":
                return "keyword";

            case "IntegerLiteral": case "FloatingPointLiteral":
            case "NUMBER":
                return "number";

            case "BooleanLiteral": case "CharacterLiteral": case "StringLiteral": case "TextBlock":
            case "NULL_LITERAL":
            case "STRING":
                return "string";

            case "LINE_COMMENT": case "COMMENT":
                return "comment";

            case "LPAREN": case "RPAREN": case "LBRACE": case "RBRACE": case "LBRACK": case "RBRACK":
            case "SEMI": case "COMMA": case "DOT":
                return "separator";

            case "Identifier":
            case "NAME":
                return "identifier";

            default:
                return "text"; // 기본 스타일
        }
    }
}
