package com.ethis2s.util;

import com.ethis2s.service.AntlrCompletionService;
import com.ethis2s.service.AntlrLanguageService;
import com.ethis2s.service.AntlrLanguageService.AnalysisResult;
import com.ethis2s.service.AntlrLanguageService.BracketPair;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.Tm4eSyntaxHighlighter.StyleToken;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.antlr.v4.runtime.Token;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HybridManager {
    
    private static final int LARGE_UPDATE_THRESHOLD = 1000;
    private final CodeArea codeArea;
    private final Tm4eSyntaxHighlighter highlighter;
    private final AntlrLanguageService analyzer;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private AntlrCompletionService completionService = null;
    private final Consumer<List<SyntaxError>> onErrorUpdate;
    private final PauseTransition analysisDebouncer;
    private List<StyleToken> lastTm4eTokens;
    private List<StyleToken> lastErrorTokens;
    private List<StyleToken> lastBracketTokens; // 괄호 위치 데이터의 단일 소스
    private AnalysisResult lastAnalysisResult;
    private CompletableFuture<AnalysisResult> currentAntlrFuture;
    private long tm4eRequestCounter = 0;

    private boolean isLargeUpdate = false;
    private int expectedLargeUpdateSize = 0;
    private int currentLargeUpdateSize = 0;
    private List<StyleToken> previouslyRenderedBrackets; // 경량 렌더러가 이전에 그렸던 위치를 기억
    private boolean isTyping = false; // 타이핑 상태를 추적할 깃발

    public HybridManager(CodeArea codeArea, String fileExtension, Consumer<List<SyntaxError>> onErrorUpdate) {
        this.codeArea = codeArea;
        this.onErrorUpdate = onErrorUpdate;
        
        this.highlighter = new Tm4eSyntaxHighlighter(codeArea, fileExtension);

        if (AntlrLanguageService.isSupported(fileExtension)) {
            this.analyzer = new AntlrLanguageService(fileExtension);
            this.completionService = new AntlrCompletionService(this.analyzer);
        } else {
            this.analyzer = null;
            this.completionService = null;
        }
        
        EditorEnhancer enhancer = new EditorEnhancer(codeArea, this.completionService, this);
        EditorInputManager inputManager = new EditorInputManager(codeArea, enhancer, this.completionService, this);
        inputManager.registerEventHandlers();

        this.analysisDebouncer = new PauseTransition(Duration.millis(300));
        this.analysisDebouncer.setOnFinished(e -> {
            runTm4eHighlighting();
            runAntlrAnalysis();
        });

        codeArea.multiPlainChanges()
            .subscribe(changes -> {
                isTyping = true; // 텍스트 변경 시작 -> 깃발 올리기
                if (isLargeUpdate) { /* ... large update logic ... */ 
                    Platform.runLater(() -> isTyping = false);
                    return; 
                }

                if (lastTm4eTokens != null) {
                    for (var change : changes) {
                        int diff = change.getInserted().length() - change.getRemoved().length();
                        if (diff != 0) {
                            shiftTokens(lastTm4eTokens, change.getPosition(), diff);
                            if (lastAnalysisResult != null && lastAnalysisResult.symbolTokens != null) {
                                shiftTokens(lastAnalysisResult.symbolTokens, change.getPosition(), diff);
                            }
                            if (lastErrorTokens != null) {
                                shiftTokens(lastErrorTokens, change.getPosition(), diff);
                            }
                            // ★★★ 괄호 예측 스타일링 부활! ★★★
                            if (lastBracketTokens != null) {
                                shiftTokens(lastBracketTokens, change.getPosition(), diff);
                            }
                        }
                    }
                    // 타이핑 시에는 무거운 전체 렌더러 호출
                    applyHighlighting();
                }
                analysisDebouncer.playFromStart();
                Platform.runLater(() -> isTyping = false); // 모든 작업 후 -> 깃발 내리기 예약
            });
        
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            if (isTyping) { // 깃발 확인
                return; // 타이핑 중이면 아무것도 하지 않음
            }
            // 커서 이동 시에는 데이터 업데이트 후, 가벼운 괄호 전용 렌더러 호출
            updateBracketHighlightingData();
            renderBracketHighlightOnly();
        });
    }

    public void prepareForLargeUpdate(int expectedSize) {
        if (expectedSize < LARGE_UPDATE_THRESHOLD) {
            return;
        }
        this.isLargeUpdate = true;
        this.expectedLargeUpdateSize = expectedSize;
        this.currentLargeUpdateSize = 0;
        analysisDebouncer.stop();
    }

    public void requestImmediateAnalysis() {
        analysisDebouncer.stop();
        if (currentAntlrFuture != null && !currentAntlrFuture.isDone()) {
            currentAntlrFuture.cancel(true);
        }
        runTm4eHighlighting();
        runAntlrAnalysis();
    }

    private void runTm4eHighlighting() {
        long requestId = ++tm4eRequestCounter;
        String text = codeArea.getText();
        CompletableFuture.supplyAsync(() -> highlighter.computeHighlighting(text), analysisExecutor)
            .thenAcceptAsync(tokens -> {
                if (requestId == tm4eRequestCounter) {
                    this.lastTm4eTokens = tokens;
                    applyHighlighting();
                }
            }, Platform::runLater);
    }

    // ... (prepareForLargeUpdate, requestImmediateAnalysis, runTm4eHighlighting methods are unchanged) ...

    private void runAntlrAnalysis() {
        if (analyzer == null) return;
        if (currentAntlrFuture != null && !currentAntlrFuture.isDone()) {
            currentAntlrFuture.cancel(true);
        }
        String text = codeArea.getText();
        currentAntlrFuture = analyzer.analyze(text, codeArea.getCaretPosition());
        if (completionService != null) {
            completionService.updateAnalysisResult(currentAntlrFuture);
        }
        currentAntlrFuture.thenAcceptAsync(analysisResult -> {
            this.lastAnalysisResult = analysisResult;
            this.lastErrorTokens = new ArrayList<>();
            if (analysisResult.errors != null) {
                for (SyntaxError error : analysisResult.errors) {
                    int line = error.line - 1;
                    if (line < 0 || line >= codeArea.getParagraphs().size()) continue;
                    int start = codeArea.getAbsolutePosition(line, error.charPositionInLine);
                    int end = start + error.length;
                    if (start < end) {
                        lastErrorTokens.add(new StyleToken(start, Math.min(end, codeArea.getLength()), Collections.singletonList("syntax-error")));
                    }
                }
            }
            if (this.lastTm4eTokens == null) {
                analysisDebouncer.playFromStart(); 
                return;
            }
            this.onErrorUpdate.accept(analysisResult.errors);
            applyHighlighting(); // 분석 완료 후 전체 스타일링 다시 적용
        }, Platform::runLater);
    }
    
    private void shiftTokens(List<StyleToken> tokens, int position, int diff) {
        if (tokens == null) return;
        for (StyleToken token : tokens) {
            if (token.start >= position) {
                token.start += diff;
                token.end += diff;
            } else if (token.end > position) {
                token.end += diff;
            }
        }
    }

    // 1. 데이터만 업데이트하는 메서드
    private void updateBracketHighlightingData() {
        if (lastAnalysisResult == null) {
            this.lastBracketTokens = null;
            return;
        }
        Optional<BracketPair> bracketPairOpt = lastAnalysisResult.bracketMapping.findEnclosingPair(codeArea.getCaretPosition());
        if (bracketPairOpt.isPresent()) {
            BracketPair pair = bracketPairOpt.get();
            this.lastBracketTokens = new ArrayList<>();
            this.lastBracketTokens.add(new StyleToken(pair.start.getStartIndex(), pair.start.getStopIndex() + 1, Collections.singletonList("bracket-highlight")));
            this.lastBracketTokens.add(new StyleToken(pair.end.getStartIndex(), pair.end.getStopIndex() + 1, Collections.singletonList("bracket-highlight")));
        } else {
            this.lastBracketTokens = null;
        }
    }

    // 2. 괄호만 그리는 가벼운 렌더러
    private void renderBracketHighlightOnly() {
        // 이전에 그렸던 괄호 스타일 지우기
        if (previouslyRenderedBrackets != null) {
            for (StyleToken oldToken : previouslyRenderedBrackets) {
                List<String> style = codeArea.getStyleOfChar(oldToken.start).stream().filter(s -> !s.equals("bracket-highlight")).collect(Collectors.toList());
                codeArea.setStyle(oldToken.start, oldToken.end, style);
            }
        }

        // 새로 그릴 괄호 스타일 적용하기
        if (lastBracketTokens != null) {
            for (StyleToken newToken : lastBracketTokens) {
                List<String> style = Stream.concat(codeArea.getStyleOfChar(newToken.start).stream(), Stream.of("bracket-highlight")).distinct().collect(Collectors.toList());
                codeArea.setStyle(newToken.start, newToken.end, style);
            }
        }
        
        // 현재 그린 괄호 위치�� 다음을 위해 기억
        this.previouslyRenderedBrackets = this.lastBracketTokens;
    }

    private StyleSpans<Collection<String>> tokensToSpans(List<StyleToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return StyleSpans.singleton(Collections.emptyList(), codeArea.getLength());
        }
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        for (StyleToken token : tokens) {
            if (token.start > lastEnd) {
                spansBuilder.add(Collections.emptyList(), token.start - lastEnd);
            }
            if (token.end > token.start) {
                spansBuilder.add(token.styleClasses, token.end - token.start);
            }
            lastEnd = Math.max(lastEnd, token.end);
        }
        int remaining = codeArea.getLength() - lastEnd;
        if (remaining > 0) {
            spansBuilder.add(Collections.emptyList(), remaining);
        }
        return spansBuilder.create();
    }

    // 3. 모든 것을 그리는 무거운 렌더러
    private void applyHighlighting() {
        if (lastTm4eTokens == null) return;

        StyleSpans<Collection<String>> tm4eSpans = tokensToSpans(lastTm4eTokens);
        StyleSpans<Collection<String>> finalSpans = tm4eSpans;

        if (lastAnalysisResult != null) {
            StyleSpans<Collection<String>> symbolSpans = tokensToSpans(lastAnalysisResult.symbolTokens);
            StyleSpans<Collection<String>> errorSpans = tokensToSpans(lastErrorTokens);
            StyleSpans<Collection<String>> bracketSpans = tokensToSpans(lastBracketTokens); // 예측된 괄호 위치 포함
            
            finalSpans = finalSpans
                .overlay(symbolSpans, (base, symbol) -> !symbol.isEmpty() ? symbol : base)
                .overlay(errorSpans, (base, error) -> {
                    if (!error.isEmpty()) {
                        Set<String> combined = new HashSet<>(base);
                        combined.addAll(error);
                        return combined;
                    }
                    return base;
                })
                .overlay(bracketSpans, (base, bracket) -> {
                    if (!bracket.isEmpty()) {
                        Set<String> combined = new HashSet<>(base);
                        combined.addAll(bracket);
                        return combined;
                    }
                    return base;
                });
        }
        
        codeArea.setStyleSpans(0, finalSpans);
        // 무거운 렌더링 후에는, 그려진 괄호 위치를 기억해둬야 가벼운 렌더러가 지울 수 있음
        this.previouslyRenderedBrackets = this.lastBracketTokens;
    }

    public void shutdown() {
        if (highlighter != null) highlighter.shutdown();
        if (analyzer != null) analyzer.shutdown();
        analysisExecutor.shutdown();
    }
}