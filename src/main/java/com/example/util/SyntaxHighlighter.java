package com.example.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.InputStream;
import java.time.Duration;
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
    private HighlightingTask lastTask;

    private static final Map<String, Pattern> COMPILED_PATTERNS = new HashMap<>();
    private static final Map<String, List<String>> PATTERN_GROUP_NAMES = new HashMap<>();
    private static final Map<String, Map<String, String>> GROUP_TO_STYLE_MAPS = new HashMap<>();
    private static final Properties GRAMMAR_FILES = new Properties();

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
        this.codeArea = codeArea;
        this.executor = Executors.newSingleThreadExecutor();
        
        Pattern highlightingPattern = getPatternForExtension(fileExtension);

        if (highlightingPattern != null) {
            codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(200))
                .subscribe(ignore -> {
                    String currentText = codeArea.getText();
                    if (!currentText.isEmpty()) {
                        if (lastTask != null) {
                            lastTask.cancel();
                        }
                        lastTask = new HighlightingTask(currentText, highlightingPattern, fileExtension);
                        executor.submit(lastTask);
                    }
                });
            
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
            if (isCancelled()) return null;
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
            if (isCancelled()) return;
            System.err.println("Highlighting task failed!");
            getException().printStackTrace();
        }
    }

    private static Pattern getPatternForExtension(String extension) {
        if (COMPILED_PATTERNS.containsKey(extension)) {
            return COMPILED_PATTERNS.get(extension);
        }

        String grammarFile = GRAMMAR_FILES.getProperty(extension);
        if (grammarFile == null) {
            System.err.println("CRITICAL: Could not find key '" + extension + "' in grammars.properties.");
            return null;
        }

        try (InputStream is = SyntaxHighlighter.class.getResourceAsStream(grammarFile)) {
            if (is == null) {
                System.err.println("CRITICAL: Grammar file not found at path: " + grammarFile);
                return null;
            }

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

    private static Pattern compilePatternFromGrammar(InputStream grammarStream, List<String> groupNames, Map<String, String> groupToStyleMap) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(grammarStream);

        Map<String, JsonNode> repository = new HashMap<>();
        JsonNode repoNode = root.path("repository");
        if (repoNode.isObject()) {
            repoNode.fields().forEachRemaining(entry -> repository.put(entry.getKey(), entry.getValue()));
        }

        StringBuilder regexBuilder = new StringBuilder();
        JsonNode patternsNode = root.path("patterns");
        if (patternsNode.isArray()) {
            for (JsonNode patternInfo : patternsNode) {
                processPattern(patternInfo, repository, regexBuilder, groupNames, groupToStyleMap);
            }
        }

        if (regexBuilder.length() == 0) {
            return Pattern.compile("");
        }
        
        return Pattern.compile(regexBuilder.toString(), Pattern.MULTILINE);
    }

    private static void processPattern(JsonNode patternInfo, Map<String, JsonNode> repository, StringBuilder regexBuilder, List<String> groupNames, Map<String, String> groupToStyleMap) {
        String include = patternInfo.path("include").asText(null);
        if (include != null && include.startsWith("#")) {
            String repoKey = include.substring(1);
            JsonNode repoPatternInfo = repository.get(repoKey);
            if (repoPatternInfo != null) {
                processPattern(repoPatternInfo, repository, regexBuilder, groupNames, groupToStyleMap);
            }
            return;
        }
        
        String regex;
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

        while (matcher.find()) {
            String styleClass = "text";
            for (String group : groupNames) {
                if (matcher.group(group) != null) {
                    styleClass = groupToStyleMap.get(group);
                    break;
                }
            }
            
            spansBuilder.add(Collections.singleton("text"), matcher.start() - lastKwEnd);
            String finalStyle = styleClass.replace('.', '_');
            spansBuilder.add(Collections.singleton(finalStyle), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.singleton("text"), text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }
}