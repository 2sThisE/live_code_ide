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

import com.ethis2s.service.AntlrLanguageService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    private static final Path TM4E_PLUGIN_DIR =
        ConfigManager.getBaseDir().resolve("plugins").resolve("tm4e").normalize();
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

            public static class StyleToken {
                public int start;
                public int end;
                public List<String> styleClasses;
    
                public StyleToken(int start, int end, List<String> styleClasses) {
                    this.start = start;
                    this.end = end;
                    this.styleClasses = styleClasses;
                }
    
                @Override
                public String toString() {
                    return "StyleToken{" +
                           "start=" + start +
                           ", end=" + end +
                           ", styles=" + styleClasses +
                           '}';
                }
            }
     private class HighlightingTask extends Task<List<StyleToken>> {
        private final String text;
        public HighlightingTask(String text) { this.text = text; }
        @Override protected List<StyleToken> call() {return computeHighlighting(text);}
    }

    private String scopeToGenericStyleClass(String scope) {
        if (scope == null || scope.isEmpty()) {
            return "";
        }
        // ANTLR이 담당할 사용자 정의 심볼들은 색칠하지 않는다.
        if (scope.startsWith("entity.name.function")) return ""; // 메소드 이름
        if (scope.startsWith("entity.name.type") || scope.startsWith("support.class")) return ""; // 클래스/타입 이름
        if (scope.startsWith("variable") && !scope.startsWith("variable.language")) return ""; // this, super 제외 변수

        // --- 나머지 규칙은 기존과 동일 ---
        if (scope.startsWith("punctuation.definition.comment")) return "comment";
        if (scope.startsWith("keyword.control")) return "keyword-control";
        if (scope.startsWith("storage.modifier")||scope.startsWith("keyword.other.package")||scope.startsWith("variable.language")) return "storage-modifier";
        if (scope.startsWith("storage.type")) return "storage-type";
        if (scope.startsWith("constant.numeric")) return "numeric";
        if (scope.startsWith("constant.language")) return "language-constant";
        if (scope.startsWith("string")||scope.startsWith("punctuation.definition.string")) return "string";
        if (scope.startsWith("comment")) return "comment";
        if (scope.startsWith("keyword")) return "";
        
        return "";
    }
    
    public List<StyleToken> computeHighlighting(String text) {
        List<StyleToken> tokens = new ArrayList<>();
        if (text.isEmpty()) {
            return tokens;
        }

        String[] lines = text.split("\n", -1);
        IStateStack ruleStack = null;
        int currentOffset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, ruleStack, null);
            ruleStack = result.getRuleStack();
            boolean isErrorLine = this.errorLines.contains(i);
            IToken[] lineTokens = result.getTokens();

            int lastTokenEnd = 0;
            for (IToken token : lineTokens) {
                int start = token.getStartIndex();
                int end = token.getEndIndex();
                if (end > line.length()) {
                    end = line.length();
                    if (start >= end) continue;
                }

                if (start > lastTokenEnd) {
                    tokens.add(new StyleToken(currentOffset + lastTokenEnd, currentOffset + start, Collections.singletonList("text")));
                }

                List<String> styleClasses = new ArrayList<>();
                styleClasses.add("text");
                if (!token.getScopes().isEmpty()) {
                    String scope = token.getScopes().get(token.getScopes().size() - 1);
                    String genericClass = scopeToGenericStyleClass(scope);
                    if (!genericClass.isEmpty()) {
                        styleClasses.add(genericClass);
                    }
                }
                if (isErrorLine) {
                    styleClasses.add("syntax-error");
                }
                tokens.add(new StyleToken(currentOffset + start, currentOffset + end, styleClasses));
                lastTokenEnd = end;
            }

            if (line.length() > lastTokenEnd) {
                List<String> remainingStyles = new ArrayList<>();
                remainingStyles.add("text");
                if (isErrorLine) {
                    remainingStyles.add("syntax-error");
                }
                tokens.add(new StyleToken(currentOffset + lastTokenEnd, currentOffset + line.length(), remainingStyles));
            }
            
            currentOffset += line.length() + 1; // +1 for newline character
        }
        return tokens;
    }
}
