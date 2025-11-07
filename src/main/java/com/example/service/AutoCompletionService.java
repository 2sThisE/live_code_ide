package com.example.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class AutoCompletionService {

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "continue", "for", "new", "switch", "assert", "default", "goto", "package", "synchronized",
            "boolean", "do", "if", "private", "this", "break", "double", "implements", "protected", "throw", "byte",
            "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient", "catch",
            "extends", "int", "short", "try", "char", "final", "interface", "static", "void", "class", "finally",
            "long", "strictfp", "volatile", "const", "float", "native", "super", "while"
    );

    private static final Set<String> COMMON_CLASSES = Set.of(
            "String", "System", "Object", "Integer", "Double", "Boolean", "Character", "Byte", "Short", "Long", "Float",
            "List", "ArrayList", "LinkedList", "Map", "HashMap", "Set", "HashSet", "Exception", "RuntimeException",
            "File", "Math", "StringBuilder", "Thread", "Runnable"
    );

    private final JavaParser javaParser;

    public AutoCompletionService() {
        // Configure the parser to be more lenient
        ParserConfiguration config = new ParserConfiguration();
        this.javaParser = new JavaParser(config);
    }

    public List<String> getSuggestions(String code, int caretPosition) {
        if (code == null || code.isEmpty()) {
            return Collections.emptyList();
        }

        String prefix = getPrefix(code, caretPosition);
        if (prefix.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> prioritizedSuggestions = new TreeSet<>();
        Set<String> classSuggestions = new TreeSet<>();
        Set<String> keywordSuggestions = new TreeSet<>();

        // 1. Add keywords to their own set
        JAVA_KEYWORDS.stream()
                .filter(keyword -> keyword.startsWith(prefix))
                .forEach(keywordSuggestions::add);

        // 2. Add common classes to their own set
        COMMON_CLASSES.stream()
                .filter(className -> className.startsWith(prefix))
                .forEach(classSuggestions::add);

        // 3. Parse the code to find variables and methods for the prioritized set
        // 파서가 불완전한 코드를 처리할 수 있도록, 현재 입력 중인 접두사를 제거한 코드를 생성합니다.
        int prefixStart = caretPosition - prefix.length();
        String sanitizedCode = code.substring(0, prefixStart) + code.substring(caretPosition);
        ParseResult<CompilationUnit> parseResult = javaParser.parse(sanitizedCode);

        // --- 디버깅 로그 추가 ---
        if (!parseResult.isSuccessful()) {
            System.out.println("Parsing failed. Problems:");
            parseResult.getProblems().forEach(p -> System.out.println("- " + p.toString()));
        }
        // --- 여기까지 ---

        parseResult.getResult().ifPresent(cu -> {
            SuggestionVisitor visitor = new SuggestionVisitor();
            visitor.visit(cu, null);

            // Add local variables
            visitor.getVariables().stream()
                    .filter(var -> var.startsWith(prefix))
                    .forEach(prioritizedSuggestions::add);

            // Add method names
            visitor.getMethods().stream()
                    .filter(method -> method.startsWith(prefix))
                    .forEach(prioritizedSuggestions::add);
        });

        // Combine the lists, with prioritized suggestions first
        List<String> finalSuggestions = new ArrayList<>(prioritizedSuggestions);
        finalSuggestions.addAll(classSuggestions);
        finalSuggestions.addAll(keywordSuggestions);

        return finalSuggestions;
    }

    private String getPrefix(String text, int caretPosition) {
        if (caretPosition > text.length()) {
            caretPosition = text.length();
        }
        int start = caretPosition - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) {
            start--;
        }
        return text.substring(start + 1, caretPosition);
    }

    private static class SuggestionVisitor extends VoidVisitorAdapter<Void> {
        private final Set<String> variables = new TreeSet<>();
        private final Set<String> methods = new TreeSet<>();

        @Override
        public void visit(VariableDeclarator n, Void arg) {
            super.visit(n, arg);
            variables.add(n.getNameAsString());
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            super.visit(n, arg);
            methods.add(n.getNameAsString());
        }

        public Set<String> getVariables() {
            return variables;
        }

        public Set<String> getMethods() {
            return methods;
        }
    }
}

