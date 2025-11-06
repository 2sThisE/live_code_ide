package com.example.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import java.time.Duration; // import 추가
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyntaxHighlighter {

    private final ExecutorService executor;
    private final CodeArea codeArea;
    private static final Map<String, Pattern> COMPILED_PATTERNS = new HashMap<>();
    private static final Map<String, List<String>> PATTERN_GROUP_NAMES = new HashMap<>();
    private static final Map<String, Map<String, String>> GROUP_TO_STYLE_MAPS = new HashMap<>();
    private static final Properties GRAMMAR_FILES = new Properties();
    private HighlightingTask lastTask; // 이 줄 추가

    static {
        try (InputStream input = SyntaxHighlighter.class.getResourceAsStream("/grammars.properties")) {
            if (input == null) {
                System.err.println("CRITICAL: Could not find grammars.properties");
            } else {
                GRAMMAR_FILES.load(input);
            }
        } catch (Exception e) {
            System.err.println("Error loading grammars.properties");
            e.printStackTrace();
        }
    }

    public SyntaxHighlighter(CodeArea codeArea, String fileExtension) {
        System.out.println("SyntaxHighlighter created with extension: [" + fileExtension + "]");
        this.codeArea = codeArea;
        this.executor = Executors.newSingleThreadExecutor();
        
        Pattern highlightingPattern = getPatternForExtension(fileExtension);

        if (highlightingPattern != null) {
                codeArea.multiPlainChanges()
                    .successionEnds(Duration.ofMillis(200))
                    .subscribe(ignore -> {
                        String currentText = codeArea.getText();
                        if (!currentText.isEmpty()) {
                            // 이전 작업이 있다면 취소
                            if (lastTask != null) {
                                lastTask.cancel();
                            }
                            // 새 작업을 만들고 멤버 변수에 저장
                            lastTask = new HighlightingTask(currentText, highlightingPattern, fileExtension);
                            executor.submit(lastTask);
                        }
                    });
            
            // 초기 텍스트에 대한 하이라이팅
            if (!codeArea.getText().isEmpty()) {
                executor.submit(new HighlightingTask(codeArea.getText(), highlightingPattern, fileExtension));
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
    }

    private class HighlightingTask extends Task<StyleSpans<Collection<String>>> {
        private final String text;
        private final Pattern highlightingPattern;
        private final String extension;

        public HighlightingTask(String text, Pattern highlightingPattern, String extension) {
            this.text = text;
            this.highlightingPattern = highlightingPattern;
            this.extension = extension;
        }

        @Override
        protected StyleSpans<Collection<String>> call() {
            return computeHighlighting(text, highlightingPattern, extension);
        }

        @Override
        protected void succeeded() {
            if (isCancelled()) return;
            Platform.runLater(() -> {
                if (getValue() != null) {
                    codeArea.setStyleSpans(0, getValue());
                }
            });
        }
        @Override
        protected void failed() {
            System.err.println("Highlighting task failed!");
            getException().printStackTrace(); // 백그라운드에서 발생한 에러를 출력
        }
    }

    // 이 메소드 전체를 아래 내용으로 바꿔치기 하세요.
    private static Pattern getPatternForExtension(String extension) {
        // --- 1단계: 넘어온 확장자와 현재 캐시 상태 확인 ---
        System.out.println("--- Checking for extension: '" + extension + "' ---");
        if (COMPILED_PATTERNS.containsKey(extension)) {
            System.out.println("Found pattern in cache. Reusing it.");
            return COMPILED_PATTERNS.get(extension);
        }

        // --- 2단계: grammars.properties 파일이 제대로 로드되었는지 확인 ---
        System.out.println("Loaded grammar properties size: " + GRAMMAR_FILES.size());
        if (GRAMMAR_FILES.isEmpty()) {
            System.err.println("CRITICAL: grammars.properties file might be empty or not loaded correctly.");
            return null;
        }

        // --- 3단계: properties 파일에서 확장자에 해당하는 경로를 가져오는지 확인 ---
        String grammarFile = GRAMMAR_FILES.getProperty(extension);
        System.out.println("Found grammar file path from properties: " + grammarFile);
        if (grammarFile == null) {
            System.err.println("CRITICAL: Could not find key '" + extension + "' in grammars.properties.");
            return null;
        }

        // --- 4단계: 가져온 경로로 실제 .json 파일을 찾을 수 있는지 확인 ---
        try (InputStream is = SyntaxHighlighter.class.getResourceAsStream(grammarFile)) {
            if (is == null) {
                System.err.println("CRITICAL: Grammar file not found at path: " + grammarFile);
                System.err.println("Please check if the file exists in 'src/main/resources" + grammarFile + "'");
                return null;
            }

            // --- 5단계: 파싱 및 컴파일 시도 ---
            System.out.println("Grammar file found. Attempting to compile pattern...");
            List<String> groupNames = new ArrayList<>();
            Map<String, String> groupToStyleMap = new HashMap<>();
            Pattern pattern = compilePatternFromGrammar(is, groupNames, groupToStyleMap);

            COMPILED_PATTERNS.put(extension, pattern);
            PATTERN_GROUP_NAMES.put(extension, groupNames);
            GROUP_TO_STYLE_MAPS.put(extension, groupToStyleMap);

            return pattern;
        } catch (Exception e) {
            System.err.println("An exception occurred during pattern compilation for '" + extension + "'");
            e.printStackTrace();
            return null;
        }
    }
    // 이 메소드로 교체하세요.
    private static Pattern compilePatternFromGrammar(InputStream grammarStream, List<String> groupNames, Map<String, String> groupToStyleMap) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(grammarStream);

        // repository의 타입을 String에서 JsonNode로 변경
        Map<String, JsonNode> repository = new HashMap<>();
        JsonNode repoNode = root.path("repository");
        if (repoNode.isObject()) {
            repoNode.fields().forEachRemaining(entry -> {
                repository.put(entry.getKey(), entry.getValue()); // 노드 전체를 저장
            });
        }

        StringBuilder regexBuilder = new StringBuilder();
        JsonNode patternsNode = root.path("patterns");
        if (patternsNode.isArray()) {
            for (JsonNode patternInfo : patternsNode) {
                // 수정한 processPattern 메소드를 호출
                processPattern(patternInfo, repository, regexBuilder, groupNames, groupToStyleMap);
            }
        }

        if (regexBuilder.length() == 0) {
            System.err.println("WARNING: RegexBuilder was empty. No patterns were processed.");
            return Pattern.compile("");
        }
        
        System.out.println(">>> FINAL COMPILED REGEX: " + regexBuilder.toString());
        
        return Pattern.compile(regexBuilder.toString(), Pattern.MULTILINE);
    }

    // 이 메소드도 교체하세요. (메소드 시그니처의 Map 타입이 변경되었습니다)
    private static void processPattern(JsonNode patternInfo, Map<String, JsonNode> repository, StringBuilder regexBuilder, List<String> groupNames, Map<String, String> groupToStyleMap) {
        // 1. 'include' 규칙 처리
        String include = patternInfo.path("include").asText(null);
        if (include != null && include.startsWith("#")) {
            String repoKey = include.substring(1);
            JsonNode repoPatternInfo = repository.get(repoKey);

            if (repoPatternInfo != null) {
                // repository에서 가져온 노드를 재귀적으로 처리
                processPattern(repoPatternInfo, repository, regexBuilder, groupNames, groupToStyleMap);
            }
            return; // include 규칙 처리가 끝나면 여기서 종료
        }
        
        // 2. 'begin'/'end' 또는 'match' 규칙 처리
        String regex = null;
        String styleName = patternInfo.path("name").asText(null);
        
        String begin = patternInfo.path("begin").asText(null);
        String end = patternInfo.path("end").asText(null);
        if (begin != null && end != null) {
            regex = begin + ".*?" + end;
        } else {
            regex = patternInfo.path("match").asText(null);
        }

        if (regex != null && styleName != null) {
            String sanitizedGroupName = styleName.replaceAll("[^a-zA-Z0-9]", "");
            if (sanitizedGroupName.isEmpty() || !Character.isLetter(sanitizedGroupName.charAt(0))) {
                sanitizedGroupName = "g" + sanitizedGroupName;
            }

            int suffix = 1;
            String finalGroupName = sanitizedGroupName;
            while (groupNames.contains(finalGroupName)) {
                finalGroupName = sanitizedGroupName + (suffix++);
            }
            groupNames.add(finalGroupName);
            
            groupToStyleMap.put(finalGroupName, styleName);
            
            if (regexBuilder.length() > 0) {
                regexBuilder.append("|");
            }
            regexBuilder.append("(?<").append(finalGroupName).append(">").append(regex).append(")");
        }
    }
    
    private static StyleSpans<Collection<String>> computeHighlighting(String text, Pattern pattern, String extension) {
        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        List<String> groupNames = PATTERN_GROUP_NAMES.get(extension);
        Map<String, String> groupToStyleMap = GROUP_TO_STYLE_MAPS.get(extension);

        if (groupNames == null || groupToStyleMap == null) {
            spansBuilder.add(Collections.singleton("text"), text.length());
            return spansBuilder.create();
        }

        System.out.println("--- Starting Highlighting Computation ---");
        while (matcher.find()) {
            String styleClass = "text"; // Default style
            for (String group : groupNames) {
                if (matcher.group(group) != null) {
                    styleClass = groupToStyleMap.get(group);
                    break;
                }
            }
            
            // 매칭되지 않은 이전 부분(gap)에 'text' 스타일 적용
            spansBuilder.add(Collections.singleton("text"), matcher.start() - lastKwEnd);
            
            // 매칭된 부분에 찾은 스타일 적용
            String finalStyle = styleClass.replace('.', '_');
            spansBuilder.add(Collections.singleton(finalStyle), matcher.end() - matcher.start());
            
            // 디버깅: 어떤 스타일이 어느 텍스트에 적용되는지 출력
            System.out.println("Applied style '" + finalStyle + "' to text: '" + matcher.group() + "'");
            
            lastKwEnd = matcher.end();
        }
        // 마지막 매치 이후의 남은 텍스트에 'text' 스타일 적용
        spansBuilder.add(Collections.singleton("text"), text.length() - lastKwEnd);
        System.out.println("--- Finished Highlighting Computation ---");
        
        return spansBuilder.create();
    }
}
