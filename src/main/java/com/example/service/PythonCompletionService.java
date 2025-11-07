package com.example.service;

import com.example.antlr.python.Python3Lexer;
import com.example.antlr.python.Python3Parser;
import com.example.antlr.python.Python3ParserBaseVisitor;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PythonCompletionService implements CompletionService {

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
            "with", "yield"
    );

    @Override
    public List<String> getSuggestions(String code, int caretPosition) {
        if (code == null || code.isEmpty()) {
            return Collections.emptyList();
        }

        String prefix = getPrefix(code, caretPosition);
        if (prefix.isEmpty()) {
            return Collections.emptyList();
        }

        // Combine keywords and parsed identifiers
        Set<String> suggestions = new HashSet<>();

        // Add keywords
        PYTHON_KEYWORDS.stream()
                .filter(keyword -> keyword.startsWith(prefix))
                .forEach(suggestions::add);

        // Add identifiers from the code
        IdentifierVisitor visitor = new IdentifierVisitor();
        try {
            Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(code));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            Python3Parser parser = new Python3Parser(tokens);
            ParseTree tree = parser.file_input();
            visitor.visit(tree);
        } catch (Exception e) {
            // Parsing can fail on incomplete code, which is fine.
            // We'll just use the suggestions we have so far.
        }
        
        visitor.getIdentifiers().stream()
               .filter(identifier -> identifier.startsWith(prefix))
               .forEach(suggestions::add);

        return suggestions.stream().filter(s -> !s.equals(prefix)).sorted().collect(Collectors.toList());
    }

    private String getPrefix(String text, int caretPosition) {
        if (caretPosition > text.length()) {
            caretPosition = text.length();
        }
        int start = caretPosition - 1;
        while (start >= 0 && (Character.isLetterOrDigit(text.charAt(start)) || text.charAt(start) == '_')) {
            start--;
        }
        return text.substring(start + 1, caretPosition);
    }

    /**
     * An ANTLR visitor to find all identifiers (like function and variable names) in the parse tree.
     */
    private static class IdentifierVisitor extends Python3ParserBaseVisitor<Void> {
        private final Set<String> identifiers = new HashSet<>();

        public Set<String> getIdentifiers() {
            return identifiers;
        }

        @Override
        public Void visitFuncdef(Python3Parser.FuncdefContext ctx) {
            if (ctx.name() != null) {
                identifiers.add(ctx.name().getText());
            }
            return super.visitFuncdef(ctx);
        }

        @Override
        public Void visitVfpdef(Python3Parser.VfpdefContext ctx) {
            if (ctx.name() != null) {
                identifiers.add(ctx.name().getText());
            }
            return super.visitVfpdef(ctx);
        }
        
        @Override
        public Void visitTfpdef(Python3Parser.TfpdefContext ctx) {
            if (ctx.name() != null) {
                identifiers.add(ctx.name().getText());
            }
            return super.visitTfpdef(ctx);
        }
        
        @Override
        public Void visitExpr_stmt(Python3Parser.Expr_stmtContext ctx) {
            // This is a simple way to get variable assignments
            // e.g., my_var = 10
            if (ctx.testlist_star_expr() != null && ctx.testlist_star_expr().size() > 0) {
                 // This logic can be improved to be more precise
                identifiers.add(ctx.testlist_star_expr(0).getText());
            }
            return super.visitExpr_stmt(ctx);
        }
    }
}