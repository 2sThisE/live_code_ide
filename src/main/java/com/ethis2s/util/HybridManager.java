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
    private List<StyleToken> lastTm4eTokens; // TM4E 결과를 저장할 필드
    private List<StyleToken> lastErrorTokens; // ANTLR 에러 결과를 저장할 필드
    private List<StyleToken> lastBracketTokens; // 괄호 강조 결과를 저장할 필드
    private AnalysisResult lastAnalysisResult; // ANTLR 분석 결과를 저장할 필드
    private CompletableFuture<AnalysisResult> currentAntlrFuture; // 현재 진행중인 ANTLR 분석 작업
    private long tm4eRequestCounter = 0; // TM4E 요청 번호표

    private boolean isLargeUpdate = false;
    private int expectedLargeUpdateSize = 0;
    private int currentLargeUpdateSize = 0;

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
                if (isLargeUpdate) {
                    for (var change : changes) {
                        currentLargeUpdateSize += change.getInserted().length();
                    }
                    if (currentLargeUpdateSize >= expectedLargeUpdateSize) {
                        isLargeUpdate = false;
                        requestImmediateAnalysis();
                    }
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
                            // ★★★ 예측 스타일링에 괄호 토큰 추가 ★★★
                            if (lastBracketTokens != null) {
                                shiftTokens(lastBracketTokens, change.getPosition(), diff);
                            }
                        }
                    }
                    applyHighlighting();
                }
                analysisDebouncer.playFromStart();
            });
        
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> updateBracketHighlighting());
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

    private void runAntlrAnalysis() {
        if (analyzer == null) return;

        if (currentAntlrFuture != null && !currentAntlrFuture.isDone()) {
            currentAntlrFuture.cancel(true);
        }

        String text = codeArea.getText();
        int caretPosition = codeArea.getCaretPosition();

        currentAntlrFuture = analyzer.analyze(text, caretPosition);
        
        if (completionService != null) {
            completionService.updateAnalysisResult(currentAntlrFuture);
        }

        currentAntlrFuture.thenAcceptAsync(analysisResult -> {
            this.lastAnalysisResult = analysisResult;
            
            this.lastErrorTokens = new java.util.ArrayList<>();
            if (analysisResult.errors != null) {
                for (SyntaxError error : analysisResult.errors) {
                    int line = error.line - 1;
                    if (line < 0 || line >= codeArea.getParagraphs().size()) continue;
                    int start = codeArea.getAbsolutePosition(line, error.charPositionInLine);
                    int end = start + error.length;
                    if (end > codeArea.getLength()) end = codeArea.getLength();
                    if (start < end) {
                        lastErrorTokens.add(new StyleToken(start, end, Collections.singletonList("syntax-error")));
                    }
                }
            }

            if (this.lastTm4eTokens == null) {
                analysisDebouncer.playFromStart(); 
                return;
            }

            this.onErrorUpdate.accept(analysisResult.errors);
            
            // ★★★ 분석 완료 후, 괄호 강조도 새로고침 ★★★
            updateBracketHighlighting();
            
        }, Platform::runLater)
        .exceptionally(ex -> {
            if (ex.getCause() instanceof java.util.concurrent.CancellationException) {
                System.out.println("[HybridManager] Analysis task was successfully cancelled.");
            } else {
                System.err.println("[HybridManager] Analysis task failed unexpectedly.");
                ex.printStackTrace();
            }
            return null;
        });
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

    private void updateBracketHighlighting() {
        if (lastAnalysisResult == null) {
            this.lastBracketTokens = null;
        } else {
            int caretPosition = codeArea.getCaretPosition();
            Optional<BracketPair> bracketPairOpt = lastAnalysisResult.bracketMapping.findEnclosingPair(caretPosition);
            
            if (bracketPairOpt.isPresent()) {
                BracketPair pair = bracketPairOpt.get();
                Token start = pair.start;
                Token end = pair.end;
                
                this.lastBracketTokens = new ArrayList<>();
                this.lastBracketTokens.add(new StyleToken(start.getStartIndex(), start.getStopIndex() + 1, Collections.singletonList("bracket-highlight")));
                this.lastBracketTokens.add(new StyleToken(end.getStartIndex(), end.getStopIndex() + 1, Collections.singletonList("bracket-highlight")));
            } else {
                this.lastBracketTokens = null;
            }
        }
        
        // 괄호 정보가 업데이트되었으니, 즉시 화면에 다시 그립니다.
        applyHighlighting();
    }

    private StyleSpans<Collection<String>> tokensToSpans(List<StyleToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return StyleSpans.singleton(Collections.emptyList(), codeArea.getLength());
        }

        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        for (StyleToken token : tokens) {
            int start = token.start;
            int end = token.end;
            if (start > lastEnd) {
                spansBuilder.add(Collections.emptyList(), start - lastEnd);
            }
            spansBuilder.add(token.styleClasses, end - start);
            lastEnd = end;
        }
        int remaining = codeArea.getLength() - lastEnd;
        if (remaining > 0) {
            spansBuilder.add(Collections.emptyList(), remaining);
        }
        return spansBuilder.create();
    }

    private void applyHighlighting() {
        if (lastTm4eTokens == null) {
            return;
        }

        // 1. TM4E의 기본 스타일을 기반으로 StyleSpans를 생성합니다.
        StyleSpans<Collection<String>> finalSpans = tokensToSpans(lastTm4eTokens);

        // 2. ANTLR 분석 결과가 있다면, 심볼, 에러, 괄호 스타일을 순서대로 덧칠합니다.
        if (lastAnalysisResult != null) {
            StyleSpans<Collection<String>> symbolSpans = tokensToSpans(lastAnalysisResult.symbolTokens);
            StyleSpans<Collection<String>> errorSpans = tokensToSpans(lastErrorTokens);
            StyleSpans<Collection<String>> bracketSpans = tokensToSpans(lastBracketTokens);
            
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
    }

    public void shutdown() {
        if (highlighter != null) highlighter.shutdown();
        if (analyzer != null) analyzer.shutdown();
        analysisExecutor.shutdown();
    }
}