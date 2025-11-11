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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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

        // 만약 이전 분석 작업이 아직 실행 중이라면, 가차없이 취소합니다.
        if (currentAntlrFuture != null && !currentAntlrFuture.isDone()) {
            System.out.println("[HybridManager] Previous analysis is running. Cancelling it now.");
            currentAntlrFuture.cancel(true);
        }

        String text = codeArea.getText();
        int caretPosition = codeArea.getCaretPosition();

        // 새로운 분석 작업을 시작하고, currentAntlrFuture에 저장합니다.
        currentAntlrFuture = analyzer.analyze(text, caretPosition);
        
        if (completionService != null) {
            completionService.updateAnalysisResult(currentAntlrFuture);
        }

        currentAntlrFuture.thenAcceptAsync(analysisResult -> {
            this.lastAnalysisResult = analysisResult; // 최신 분석 결과를 저장합니다.
            
            // ★★★★★ 주인님, 바로 여기에 마법의 주문을 추가해요! ★★★★★
            if (!analysisResult.errors.isEmpty()) {
                System.out.println("!! 문법 교수님이 길을 잃었어요: " + analysisResult.errors);
            }
            // ★★★★★ 여기까지예요! ★★★★★

            // ANTLR 에러를 StyleToken으로 변환하여 저장
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
            
            // ��든 스타일을 종합하여 화면에 적용합니다.
            applyHighlighting();
        }, Platform::runLater)
        .exceptionally(ex -> {
            // 작업이 취소되었을 때 발생하는 예외를 처리합니다.
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
        // ANTLR 분석 결과가 없으면 아무것도 하지 않습니다.
        if (lastAnalysisResult == null || lastTm4eTokens == null) {
            return;
        }
        // 모든 스타일을 다시 계산하되, 괄호 강조만 새 커서 위치를 기준으로 다시 계산합니다.
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

        StyleSpans<Collection<String>> tm4eSpans = tokensToSpans(lastTm4eTokens);

        if (lastAnalysisResult == null) {
            codeArea.setStyleSpans(0, tm4eSpans);
            return;
        }

        int caretPosition = codeArea.getCaretPosition();
        Optional<BracketPair> bracketPair = lastAnalysisResult.bracketMapping.findEnclosingPair(caretPosition);

        StyleSpans<Collection<String>> symbolSpans = tokensToSpans(lastAnalysisResult.symbolTokens);
        StyleSpans<Collection<String>> errorSpans = tokensToSpans(lastErrorTokens);
        StyleSpans<Collection<String>> bracketSpans = computeBracketHighlightSpans(bracketPair);
        
        StyleSpans<Collection<String>> finalSpans = tm4eSpans
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
        
        codeArea.setStyleSpans(0, finalSpans);
    }

    private StyleSpans<Collection<String>> computeBracketHighlightSpans(Optional<BracketPair> bracketPairOpt) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int textLength = codeArea.getLength();

        if (bracketPairOpt.isPresent()) {
            BracketPair pair = bracketPairOpt.get();
            Token start = pair.start;
            Token end = pair.end;

            int startPos = start.getStartIndex();
            int endPos = end.getStartIndex();
            
            spansBuilder.add(Collections.emptyList(), startPos);
            spansBuilder.add(Collections.singleton("bracket-highlight"), 1);
            if (endPos > startPos + 1) {
                spansBuilder.add(Collections.emptyList(), endPos - (startPos + 1));
            }
            spansBuilder.add(Collections.singleton("bracket-highlight"), 1);
            
            int remainingLength = textLength - (endPos + 1);
            if (remainingLength > 0) {
                spansBuilder.add(Collections.emptyList(), remainingLength);
            }
        } else {
            spansBuilder.add(Collections.emptyList(), textLength);
        }
        return spansBuilder.create();
    }

    public void shutdown() {
        if (highlighter != null) highlighter.shutdown();
        if (analyzer != null) analyzer.shutdown();
        analysisExecutor.shutdown();
    }
}