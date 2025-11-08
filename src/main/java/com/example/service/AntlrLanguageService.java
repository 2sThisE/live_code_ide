package com.example.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * ANTLR을 사용하여 백그라운드에서 코드 분석을 수행하는 동적 언어 서비스.
 * 문법 오류 감지, AST 생성, 심볼 테이블 생성, 자동 완성을 담당합니다.
 */
public class AntlrLanguageService {

    // --- 1. 설정 및 분석 결과 데이터 클래스들 ---
    
    private static class AntlrConfig { String lexer, parser, startRule, visitor; }

    public static class Symbol {
        public enum Kind { CLASS, METHOD, VARIABLE }
        public final String name;
        public final Kind kind;
        public final String type;
        public Symbol(String name, Kind kind, String type) { this.name = name; this.kind = kind; this.type = type; }
    }

    public static class SymbolTable {
        public final Map<String, Symbol> symbols = new HashMap<>();
        public void define(Symbol symbol) { symbols.put(symbol.name, symbol); }
        public Symbol resolve(String name) { return symbols.get(name); }
    }

    public static class AnalysisResult {
        public final ParseTree ast;
        public final List<SyntaxError> errors;
        public final SymbolTable symbolTable;
        public AnalysisResult(ParseTree ast, List<SyntaxError> errors, SymbolTable symbolTable) {
            this.ast = ast; this.errors = errors; this.symbolTable = symbolTable;
        }
    }

    public static class SyntaxError {
        public final int line, charPositionInLine, length;
        public final String message;
        public SyntaxError(int line, int charPos, int length, String msg) {
            this.line = line; this.charPositionInLine = charPos;
            this.length = (length > 0) ? length : 1;
            this.message = msg;
        }
        @Override public String toString() { return "Error at " + line + ":" + charPositionInLine + " - " + message; }
    }

    private static final Map<String, AntlrConfig> CONFIGS = new HashMap<>();
    private static final Map<String, List<String>> LANGUAGE_KEYWORDS = new HashMap<>();
    private static final ClassLoader pluginClassLoader;
    
    static {
        // 1. 'plugins/antlr' 폴더에서 .jar 파일들을 로드할 ClassLoader 생성
        Path pluginDir = Paths.get(System.getProperty("user.dir"), "plugins", "antlr");
        List<URL> jarUrls = new ArrayList<>();
        if (Files.isDirectory(pluginDir)) {
            try (Stream<Path> stream = Files.list(pluginDir)) {
                stream.filter(path -> path.toString().endsWith(".jar")).forEach(path -> {
                    try { jarUrls.add(path.toUri().toURL()); } catch (MalformedURLException e) { e.printStackTrace(); }
                });
            } catch (Exception e) { System.err.println("WARNING: Could not scan ANTLR plugin directory."); }
        }
        pluginClassLoader = new URLClassLoader(jarUrls.toArray(new URL[0]), AntlrLanguageService.class.getClassLoader());

        // 2. 'plugins/antlr/antlr-config.json' 파일을 읽어 설정 로드
        Path configPath = pluginDir.resolve("antlr-config.json");
        if (Files.exists(configPath)) {
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configPath))) {
                CONFIGS.putAll(new Gson().fromJson(reader, new TypeToken<HashMap<String, AntlrConfig>>() {}.getType()));
            } catch (Exception e) { System.err.println("FATAL: Failed to load 'antlr-config.json' from plugin directory."); }
        } else {
            System.err.println("INFO: 'antlr-config.json' not found in plugin directory. No ANTLR support will be available.");
        }

        // 3. 로드된 설정을 바탕으로 각 언어의 키워드를 동적으로 추출
        for (Map.Entry<String, AntlrConfig> entry : CONFIGS.entrySet()) {
            String extension = entry.getKey();
            AntlrConfig config = entry.getValue();
            try {
                Class<?> lexerClass = Class.forName(config.lexer, true, pluginClassLoader);
                java.lang.reflect.Field vocabField = lexerClass.getField("VOCABULARY");
                Vocabulary vocabulary = (Vocabulary) vocabField.get(null);
                List<String> keywords = new ArrayList<>();
                for (int i = 1; i <= vocabulary.getMaxTokenType(); i++) {
                    String literalName = vocabulary.getLiteralName(i);
                    if (literalName != null && literalName.startsWith("'") && literalName.endsWith("'")) {
                        String keyword = literalName.substring(1, literalName.length() - 1);
                        
                        // [핵심 수정] 추출된 키워드가 순수 알파벳으로만 구성되었는지 확인합니다.
                        // 이렇게 하면 '+', '==' 같은 연산자들이 키워드 목록에 포함되지 않습니다.
                        if (keyword.chars().allMatch(Character::isLetter)) {
                            keywords.add(keyword);
                        }
                    }
                }
                LANGUAGE_KEYWORDS.put(extension, keywords);
            } catch (Exception e) {
                System.err.println("WARNING: Failed to extract keywords for '" + extension + "'.");
                LANGUAGE_KEYWORDS.put(extension, Collections.emptyList());
            }
        }
    }

    public static boolean isSupported(String fileExtension) {
        return fileExtension != null && CONFIGS.containsKey(fileExtension.toLowerCase());
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AntlrConfig config;
    private final String fileExtension;

    public AntlrLanguageService(String fileExtension) {
        this.fileExtension = fileExtension.toLowerCase();
        this.config = CONFIGS.get(this.fileExtension);
    }

    public CompletableFuture<AnalysisResult> analyze(String text) {
        if (config == null) {
            return CompletableFuture.completedFuture(new AnalysisResult(null, Collections.emptyList(), new SymbolTable()));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                // [핵심] 모든 클래스를 pluginClassLoader를 통해 로드합니다.
                Class<?> lexerClass = Class.forName(config.lexer, true, pluginClassLoader);
                Lexer lexer = (Lexer) lexerClass.getConstructor(CharStream.class).newInstance(CharStreams.fromString(text));
                
                ErrorCollectingErrorListener errorListener = new ErrorCollectingErrorListener();
                lexer.removeErrorListeners();
                lexer.addErrorListener(errorListener);

                CommonTokenStream tokens = new CommonTokenStream(lexer);
                
                Class<?> parserClass = Class.forName(config.parser, true, pluginClassLoader);
                Parser parser = (Parser) parserClass.getConstructor(TokenStream.class).newInstance(tokens);
                
                parser.removeErrorListeners();
                parser.addErrorListener(errorListener);

                Method startRuleMethod = parser.getClass().getMethod(config.startRule);
                ParseTree ast = (ParseTree) startRuleMethod.invoke(parser);
                
                SymbolTable symbolTable = new SymbolTable();
                if (config.visitor != null && !config.visitor.isEmpty()) {
                    Class<?> visitorClass = Class.forName(config.visitor, true, pluginClassLoader);
                    Constructor<?> constructor = visitorClass.getConstructor(SymbolTable.class);
                    Object visitor = constructor.newInstance(symbolTable);
                    Method visitMethod = visitorClass.getMethod("visit", ParseTree.class);
                    visitMethod.invoke(visitor, ast);
                }
                
                return new AnalysisResult(ast, errorListener.getErrors(), symbolTable);
            } catch (Exception e) {
                e.printStackTrace();
                return new AnalysisResult(null, Collections.singletonList(new SyntaxError(0, 0, 0, "Parser failed: " + e.getMessage())), new SymbolTable());
            }
        }, executor);
    }
    
    public List<String> getCompletions(AnalysisResult result, String text, int caretPosition) {
        if (result == null) return Collections.emptyList();
        
        // 1. 사용자가 자동 완성을 요청한 단어의 "시작 부분"을 알아냅니다.
        int start = caretPosition - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) {
            start--;
        }
        String prefix = text.substring(start + 1, caretPosition).toLowerCase();

        Set<String> suggestions = new HashSet<>();
        
        // 2. 심볼 테이블과 키워드 목록을 모두 수집합니다 (기존과 동일).
        if (result.symbolTable != null) {
            suggestions.addAll(result.symbolTable.symbols.keySet());
        }
        List<String> keywords = LANGUAGE_KEYWORDS.get(this.fileExtension);
        if (keywords != null) {
            suggestions.addAll(keywords);
        }
        
        // 3. [핵심] 수집된 모든 제안 목록을 "필터링"하고 "정렬"합니다.
        List<String> filteredSuggestions = suggestions.stream()
            // 3a. 사용자가 입력한 'prefix'로 시작하는 단어만 남깁니다.
            .filter(s -> s.toLowerCase().startsWith(prefix))
            // 3b. (선택적) 더 정교한 정렬 로직을 추가할 수 있습니다.
            //      예: 키워드보다 변수 이름을 더 위로, 짧은 단어를 더 위로 등
            .sorted() 
            .toList(); // Java 16+
            // .collect(Collectors.toList()); // Java 8+

        return filteredSuggestions;
    }
    
    public void shutdown() { executor.shutdown(); }

    private static class ErrorCollectingErrorListener extends BaseErrorListener {
        private final List<SyntaxError> errors = new ArrayList<>();
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object o, int line, int charPos, String msg, RecognitionException e) {
            int length = (o instanceof Token) ? ((Token) o).getText().length() : 0;
            errors.add(new SyntaxError(line, charPos, length, msg));
        }
        public List<SyntaxError> getErrors() { return errors; }
    }

    
}