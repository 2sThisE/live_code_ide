package com.example.util;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IRegistryOptions;
import org.eclipse.tm4e.core.registry.Registry;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TM4E 문법을 사용하여, 설정 파일 기반으로 여러 언어를 동적으로 지원하는 단일 구문 강조 클래스.
 */
public class Tm4eSyntaxHighlighter {

    // --- 1. 프로퍼티 파일 로딩 로직 ---
    private static class LanguageConfig {
        final String scopeName;
        final String tmLanguagePath;
        LanguageConfig(String scopeName, String path) { this.scopeName = scopeName; this.tmLanguagePath = path; }
    }
    private static final Map<String, LanguageConfig> LANGUAGES = new HashMap<>();

    static {
        Properties props = new Properties();
        try (InputStream is = Tm4eSyntaxHighlighter.class.getResourceAsStream("/languages.properties")) {
            if (is != null) {
                props.load(is);
                for (String ext : props.stringPropertyNames()) {
                    String[] parts = props.getProperty(ext).split(",");
                    if (parts.length == 2) {
                        LANGUAGES.put(ext.trim(), new LanguageConfig(parts[0].trim(), parts[1].trim()));
                    }
                }
                System.out.println("[DEBUG] TM4E: Language configurations loaded for: " + LANGUAGES.keySet());
            } else {
                System.err.println("FATAL: languages.properties file not found in resources.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // --- 2. Registry는 모든 하이라이터 인스턴스가 공유 ---
    private static final Registry GRAMMAR_REGISTRY;
    static {
        IRegistryOptions options = new IRegistryOptions() {
            public String getFilePath(String scopeName) { return null; }
            public InputStream getInputStream(String scopeName) {
                return null;
            }
        };
        GRAMMAR_REGISTRY = new Registry(options);
    }

    private final ExecutorService executor;
    private final CodeArea codeArea;
    private IGrammar grammar;

    public Tm4eSyntaxHighlighter(CodeArea codeArea, String fileExtension) {
        this.codeArea = codeArea;
        this.executor = Executors.newSingleThreadExecutor();

        LanguageConfig config = LANGUAGES.get(fileExtension.toLowerCase());

        if (config != null) {
            try {
                final String path = config.tmLanguagePath;
                
                // 1. 우리가 직접 "설명서" 객체(IGrammarSource)를 만듭니다.
                IGrammarSource source = new IGrammarSource() {
                    @Override
                    public String getFilePath() {
                        return path.substring(path.lastIndexOf('/') + 1);
                    }
                    @Override
                    public Reader getReader() throws IOException {
                        InputStream is = Tm4eSyntaxHighlighter.class.getResourceAsStream(path);
                        if (is == null) throw new IOException("Cannot find resource at path: " + path);
                        return new InputStreamReader(is, StandardCharsets.UTF_8);
                    }
                };

                // 2. addGrammar를 호출하고, 그 "결과"를 this.grammar에 저장합니다.
                this.grammar = GRAMMAR_REGISTRY.addGrammar(source);
                
                if (this.grammar == null) {
                    throw new IllegalStateException("FATAL: addGrammar returned null. Check scopeName inside '" + path + "' matches '" + config.scopeName + "'");
                }

                setupHighlighting();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void setupHighlighting() {
        codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(200))
                .subscribe(ignore -> highlight());
        highlight();
    }

    public void highlight() {
        if (grammar == null) return;
        String text = codeArea.getText();
        HighlightingTask task = new HighlightingTask(text);
        executor.submit(task);
    }
    
    public void shutdown() { executor.shutdown(); }

    private class HighlightingTask extends Task<StyleSpans<Collection<String>>> {
        private final String text;
        public HighlightingTask(String text) { this.text = text; }
        @Override protected StyleSpans<Collection<String>> call() { return computeHighlighting(text); }
        @Override protected void succeeded() { Platform.runLater(() -> { if (getValue() != null) codeArea.setStyleSpans(0, getValue()); }); }
        @Override protected void failed() { getException().printStackTrace(); }
    }

    private String scopeToGenericStyleClass(String scope) {
        if (scope == null || scope.isEmpty()) {
            return "";
        }
        if (scope.startsWith("punctuation.definition.comment")) return "comment";
        // [핵심 수정] 더 구체적인 규칙을 항상 먼저 확인합니다.
        if (scope.startsWith("keyword.control")) return "keyword-control";
        
        // [신규] 수정자(modifier)를 먼저 감지합니다.
        if (scope.startsWith("storage.modifier")||scope.startsWith("keyword.other.package")||scope.startsWith("variable.language")) return "storage-modifier";
        
        // [수정] 타입(type)을 감지합니다.
        if (scope.startsWith("storage.type")) return "storage-type";
        
        // 기존 규칙들은 그대로 유지
        if (scope.startsWith("constant.numeric")) return "numeric";
        if (scope.startsWith("constant.language")) return "language-constant";
        if (scope.startsWith("entity.name.function")) return "entity-name-function";
        if (scope.startsWith("entity.name.type") || scope.startsWith("support.class")) return "entity-name-type";
        if (scope.startsWith("variable")) return "variable";
        if (scope.startsWith("string")||scope.startsWith("punctuation.definition.string")) return "string";
        if (scope.startsWith("comment")) return "comment";
        // 위에 해당하지 않는 나머지 모든 'keyword'는 일반 'storage-type'으로 처리합니다.
        if (scope.startsWith("keyword")) return "";
        
        return "";
    }
    
    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        // "No spans have been added" 오류를 해결하는 안전장치
        if (text.isEmpty()) {
            return StyleSpans.singleton(Collections.singleton("text"), 0);
        }

        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        String[] lines = text.split("\n", -1);
        
        IStateStack ruleStack = null; // [기억력 담당] 이전 줄의 상태를 저장할 변수

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // [핵심 수정] 상태를 저장하는, 3개의 인자를 받는 tokenizeLine 호출!
            ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, ruleStack, null);
            ruleStack = result.getRuleStack(); // [기억력 담당] 다음 줄을 위해 상태 업데이트

            // getTokens()가 IToken[]을 반환하므로 더 이상 Object 캐스팅 불필요
            IToken[] tokens = result.getTokens();

            int lastTokenEnd = 0;
            for (IToken token : tokens) {
                int start = token.getStartIndex();
                int end = token.getEndIndex();
                if (end > line.length()) {
                    end = line.length();
                    if (start >= end) continue;
                }
                
                if (start > lastTokenEnd) {
                    spansBuilder.add(Collections.singleton("text"), start - lastTokenEnd);
                }
                String word = line.substring(start, end); // 토큰에 해당하는 실제 단어 추출
                 String finalGenericClass = ""; // 최종 번역된 클래스를 저장할 변수

                List<String> styleClasses = new ArrayList<>();
                styleClasses.add("text");
                if (!token.getScopes().isEmpty()) {
                    String scope = token.getScopes().get(token.getScopes().size() - 1);
                    String genericClass = scopeToGenericStyleClass(scope);
                    String originalScope = token.getScopes().get(token.getScopes().size() - 1);
                    // "번역기"를 통해 범용 CSS 클래스로 변환
                    finalGenericClass = scopeToGenericStyleClass(originalScope);
                    System.out.printf("[DEBUG]   - Word: '%s'\t Original Scope: %s\t -> Translated to CSS Class: '%s'\n",
                        word.replace("\t", " "), // 탭 문자는 공백으로 치환하여 출력
                        originalScope,
                        finalGenericClass.isEmpty() ? "(none - default text)" : finalGenericClass
                    );
                    if (!genericClass.isEmpty()) {
                        styleClasses.add(genericClass);
                    }
                }
                spansBuilder.add(styleClasses, end - start);
                lastTokenEnd = end;
            }
            if (line.length() > lastTokenEnd) {
                spansBuilder.add(Collections.singleton("text"), line.length() - lastTokenEnd);
            }
            if (i < lines.length - 1) {
                spansBuilder.add(Collections.singleton("text"), 1);
            }
        }
        return spansBuilder.create();
    }
}