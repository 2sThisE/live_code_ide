package com.ethis2s.service;

import com.ethis2s.util.Tm4eSyntaxHighlighter.StyleToken;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

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
    
    private static class AntlrConfig { 
        String lexer, parser, startRule, visitor, identifierRuleName; 
    }

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
        public final List<StyleToken> symbolTokens;
        public final BracketMapping bracketMapping;

        public AnalysisResult(ParseTree ast, List<SyntaxError> errors, SymbolTable symbolTable, List<StyleToken> symbolTokens, BracketMapping bracketMapping) {
            this.ast = ast;
            this.errors = errors;
            this.symbolTable = symbolTable;
            this.symbolTokens = symbolTokens;
            this.bracketMapping = bracketMapping;
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

    // +++ 새로운 BracketPair 클래스 정의 +++
    public static class BracketPair {
        public final Token start;
        public final Token end;

        public BracketPair(Token start, Token end) {
            this.start = start;
            this.end = end;
        }
    }

    // +++ 새로운 BracketMapping 클래스 정의 +++
    public static class BracketMapping {
        // key: 여는 괄호 토큰의 시작 위치, value: 닫는 괄호 토큰
        private final Map<Integer, Token> pairMap; 
        // key: 닫는 괄호 토큰의 시작 위치, value: 여는 괄호 토큰
        private final Map<Integer, Token> reversePairMap;

        public BracketMapping(Map<Integer, Token> pairMap, Map<Integer, Token> reversePairMap) {
            this.pairMap = pairMap;
            this.reversePairMap = reversePairMap;
        }

        public Optional<BracketPair> findEnclosingPair(int caretPosition) {
            BracketPair bestMatch = null;
            int smallestSpan = Integer.MAX_VALUE;

            // 모든 괄호 쌍을 순회합니다.
            for (Map.Entry<Integer, Token> entry : pairMap.entrySet()) {
                int openingPos = entry.getKey();
                Token closingToken = entry.getValue();
                int closingPos = closingToken.getStopIndex() + 1;

                // 1. 현재 커서 위치가 이 괄호 쌍 안에 포함되는지 확인합니다.
                if (caretPosition > openingPos && caretPosition <= closingPos) {
                    int currentSpan = closingPos - openingPos;

                    // 2. 만약 이 괄호 쌍이 이전에 찾은 것보다 더 '안쪽'에 있다면 (즉, 범위가 더 좁다면)
                    //    이것을 새로운 '최적의 쌍'으로 선택합니다.
                    if (currentSpan < smallestSpan) {
                        smallestSpan = currentSpan;
                        // reversePairMap을 사용하여 여는 괄호의 전체 Token 객체를 가져옵니다.
                        Token openingToken = reversePairMap.get(closingToken.getStartIndex());
                        bestMatch = new BracketPair(openingToken, closingToken);
                    }
                }
            }
            
            // 찾은 최적의 쌍을 Optional로 감싸 반환합니다.
            return Optional.ofNullable(bestMatch);
        }
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

    public CompletableFuture<AnalysisResult> analyze(String text, int caretPosition) {
        if (config == null) {
            // ANTLR 지원 안되면 비어있는 결과를 즉시 반환
            return CompletableFuture.completedFuture(new AnalysisResult(null, Collections.emptyList(), new SymbolTable(), Collections.emptyList(), new BracketMapping(Collections.emptyMap(), Collections.emptyMap())));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Class<?> lexerClass = Class.forName(config.lexer, true, pluginClassLoader);
                Lexer lexer = (Lexer) lexerClass.getConstructor(CharStream.class).newInstance(CharStreams.fromString(text));
                
                ErrorCollectingErrorListener errorListener = new ErrorCollectingErrorListener();
                lexer.removeErrorListeners();
                lexer.addErrorListener(errorListener);

                CommonTokenStream tokens = new CommonTokenStream(lexer);
                tokens.fill(); // 모든 토큰을 즉시 로드해야 전체 목록을 가져올 수 있음
                
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
                
                List<StyleToken> symbolTokens = computeSymbolSpans(tokens, parser.getVocabulary(), symbolTable, config.identifierRuleName, text.length());

                List<SyntaxError> mergedErrors = mergeConsecutiveErrors(errorListener.getErrors());

                // +++ 새로운 로직: 토큰 스트림으로 괄호 쌍 미리 계산 +++
                BracketMapping bracketMapping = precomputeBracketPairs(tokens);

                // 모든 분석 결과를 하나의 객체에 담아 반환합니다.
                return new AnalysisResult(ast, mergedErrors, symbolTable, symbolTokens, bracketMapping);
            } catch (Exception e) {
                e.printStackTrace();
                return new AnalysisResult(null, Collections.singletonList(new SyntaxError(0, 0, 0, "Parser failed: " + e.getMessage())), new SymbolTable(), Collections.emptyList(), null);
            }
        }, executor);
    }

    /**
     * 동일한 라인에서 발생하는 연속된 에러들을 하나의 에러로 병합합니다.
     */
    private List<SyntaxError> mergeConsecutiveErrors(List<SyntaxError> rawErrors) {
        if (rawErrors == null || rawErrors.size() <= 1) {
            return rawErrors;
        }

        // 라인과 시작 위치를 기준으로 에러를 정렬합니다.
        rawErrors.sort(Comparator.comparingInt((SyntaxError e) -> e.line)
                                 .thenComparingInt(e -> e.charPositionInLine));

        List<SyntaxError> mergedErrors = new ArrayList<>();
        SyntaxError currentError = rawErrors.get(0);

        for (int i = 1; i < rawErrors.size(); i++) {
            SyntaxError nextError = rawErrors.get(i);

            // 같은 라인, 같은 메시지, 그리고 서로 붙어있는 에러인지 확인
            if (nextError.line == currentError.line &&
                nextError.message.equals(currentError.message) &&
                (currentError.charPositionInLine + currentError.length) >= nextError.charPositionInLine) {
                
                // 에러를 병합: 시작 위치는 currentError, 끝 위치는 nextError
                int newLength = (nextError.charPositionInLine + nextError.length) - currentError.charPositionInLine;
                currentError = new SyntaxError(currentError.line, currentError.charPositionInLine, newLength, currentError.message);
            } else {
                // 병합 조건이 깨지면, 현재까지 병합된 에러를 목록에 추가하고 새 에러에서 다시 시작
                mergedErrors.add(currentError);
                currentError = nextError;
            }
        }
        // 마지막으로 처리된 에러를 목록에 추가
        mergedErrors.add(currentError);

        return mergedErrors;
    }
    
    private List<StyleToken> computeSymbolSpans(CommonTokenStream tokens, Vocabulary vocabulary, SymbolTable symbolTable, String identifierRuleName, int totalLength) {
        List<StyleToken> symbolTokens = new ArrayList<>();
        if (identifierRuleName == null || identifierRuleName.isEmpty()) {
            return symbolTokens;
        }

        for (Token token : tokens.getTokens()) {
            String symbolicName = vocabulary.getSymbolicName(token.getType());
            
            if (symbolicName != null && symbolicName.equals(identifierRuleName)) {
                Symbol symbol = symbolTable.resolve(token.getText());
                
                if (symbol != null) {
                    String styleClass = switch (symbol.kind) {
                        case CLASS -> "entity-name-type";
                        case METHOD -> "entity-name-function";
                        case VARIABLE -> "variable";
                    };
                    
                    int start = token.getStartIndex();
                    int end = token.getStopIndex() + 1;

                    symbolTokens.add(new StyleToken(start, end, Arrays.asList("text", styleClass)));
                }
            }
        }
        return symbolTokens;
    }
    
    public String getFileExtension() {
        return fileExtension;
    }

    public Map<String, List<String>> getLanguageKeywords() {
        return LANGUAGE_KEYWORDS;
    }

    private BracketMapping precomputeBracketPairs(CommonTokenStream tokens) {
        Map<Integer, Token> pairMap = new HashMap<>();
        Map<Integer, Token> reversePairMap = new HashMap<>();
        Stack<Token> stack = new Stack<>();

        Map<String, String> tokenPairs = Map.of("(", ")", "{", "}", "[", "]");
        Set<String> closingBrackets = new HashSet<>(tokenPairs.values());

        for (Token token : tokens.getTokens()) {
            String tokenText = token.getText();
            if (tokenPairs.containsKey(tokenText)) {
                stack.push(token);
            } else if (closingBrackets.contains(tokenText)) {
                if (!stack.isEmpty()) {
                    Token openToken = stack.pop();
                    // 짝이 맞는지 확인 (예: '('와 ')')
                    if (tokenPairs.get(openToken.getText()).equals(tokenText)) {
                        pairMap.put(openToken.getStartIndex(), token);
                        reversePairMap.put(token.getStartIndex(), openToken);
                    } else {
                        // 짝이 안맞으면 스택을 비우고 다시 시작 (에러 복구 로직)
                        stack.clear();
                    }
                }
            }
        }
        return new BracketMapping(pairMap, reversePairMap);
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