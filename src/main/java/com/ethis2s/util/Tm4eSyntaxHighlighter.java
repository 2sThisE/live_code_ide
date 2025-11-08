package com.ethis2s.util;

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

import com.ethis2s.service.AntlrLanguageService;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TM4E 문법을 사용하여, 설정 파일 기반으로 여러 언어를 동적으로 지원하는 단일 구문 강조 클래스.
 */
public class Tm4eSyntaxHighlighter {
    private final Set<Integer> errorLines = Collections.synchronizedSet(new HashSet<>());
    // --- 1. 프로퍼티 파일 로딩 로직 ---
    private static class LanguageConfig {
        final String scopeName;
        final String tmLanguagePath;
        LanguageConfig(String scopeName, String path) { this.scopeName = scopeName; this.tmLanguagePath = path; }
    }
    private boolean isSetup = false; // [신규] 활성화 상태를 추적할 플래그

    public boolean isSetup() { // [신규] 외부에서 상태를 확인할 수 있는 getter
        return isSetup;
    }
    private static final Map<String, LanguageConfig> LANGUAGES = new HashMap<>();
    private static final Path TM4E_PLUGIN_DIR = Paths.get(System.getProperty("user.dir"), "plugins", "tm4e");
    static {
        Path propsPath = TM4E_PLUGIN_DIR.resolve("languages.properties");
        if (Files.exists(propsPath)) {
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(propsPath)) {
                props.load(is);
                for (String ext : props.stringPropertyNames()) {
                    String[] parts = props.getProperty(ext).split(",");
                    if (parts.length == 2) {
                        LANGUAGES.put(ext.trim(), new LanguageConfig(parts[0].trim(), parts[1].trim()));
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            System.err.println("INFO: 'languages.properties' not found in TM4E plugin directory. Highlighting will be disabled.");
        }
    }

    /**
     * [신규] HybridManager가 ANTLR 분석 결과를 전달해 줄 public 메소드.
     * @param errors ANTLR이 감지한 최신 오류 목록
     */
    public void updateErrorLines(List<AntlrLanguageService.SyntaxError> errors) {
        // 이전 오류 목록을 깨끗하게 지웁니다.
        this.errorLines.clear();
        
        // 새로운 오류 목록에서 줄 번호만 추출하여 Set에 추가합니다.
        for (AntlrLanguageService.SyntaxError error : errors) {
            this.errorLines.add(error.line - 1); // ANTLR은 1-based, 우리는 0-based
        }

        // 오류 정보가 업데이트되었으므로, 즉시 하이라이팅을 다시 실행하여
        // 오류 밑줄을 그리거나 지우도록 합니다.
        // UI 스레드에서 안전하게 실행되도록 Platform.runLater를 사용합니다.
        Platform.runLater(this::highlight);
    }
    
    // --- 2. Registry는 모든 하이라이터 인스턴스가 공유 ---
    private static final Registry GRAMMAR_REGISTRY;
    static {
        IRegistryOptions options = new IRegistryOptions() {
            @Override
            public IGrammarSource getGrammarSource(String scopeName) {
                // scopeName을 기반으로 LanguageConfig를 찾습니다.
                String relativePath = null;
                for (LanguageConfig config : LANGUAGES.values()) {
                    if (config.scopeName.equals(scopeName)) {
                        relativePath = config.tmLanguagePath;
                        break;
                    }
                }

                if (relativePath != null) {
                    // relativePath가 '/syntaxes/java.json' 형태이므로 앞의 '/'를 제거합니다.
                    Path filePath = TM4E_PLUGIN_DIR.resolve(relativePath.substring(1));
                    if (Files.exists(filePath)) {
                        // IGrammarSource의 팩토리 메소드를 사용하여 객체를 생성합니다.
                        return IGrammarSource.fromFile(filePath);
                    }
                }
                // 파일을 찾지 못하면 null을 반환합니다.
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
                // 이제 loadGrammar가 IRegistryOptions를 통해 외부 파일을 성공적으로 찾습니다.
                this.grammar = GRAMMAR_REGISTRY.loadGrammar(config.scopeName);
                if (this.grammar == null) throw new IllegalStateException("Failed to load grammar for: " + config.scopeName);
            } catch (Exception e) { e.printStackTrace(); }
        }
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
        @Override protected StyleSpans<Collection<String>> call() {return computeHighlighting(text);}
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
    
    public StyleSpans<Collection<String>> computeHighlighting(String text) {
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
            boolean isErrorLine = this.errorLines.contains(i);
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
                    
                    if (!genericClass.isEmpty()) {
                        styleClasses.add(genericClass);
                    }
                }
                if (isErrorLine) {
                    styleClasses.add("syntax-error");
                }
                spansBuilder.add(styleClasses, end - start);
                lastTokenEnd = end;
            }
            if (line.length() > lastTokenEnd) {
                // 줄의 나머지 부분에도 오류 스타일을 적용해야 할 수 있음
                List<String> remainingStyles = new ArrayList<>();
                remainingStyles.add("text");
                if (isErrorLine) {
                    remainingStyles.add("syntax-error");
                }
                spansBuilder.add(remainingStyles, line.length() - lastTokenEnd);
            }
            if (i < lines.length - 1) {
                spansBuilder.add(Collections.singleton("text"), 1);
            }
        }
        return spansBuilder.create();
    }
}