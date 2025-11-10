package com.ethis2s.util;

import com.ethis2s.service.AntlrCompletionService;
import com.ethis2s.service.AntlrLanguageService;
import com.ethis2s.service.AntlrLanguageService.AnalysisResult;
import com.ethis2s.service.AntlrLanguageService.BracketPair;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
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
    
    private final CodeArea codeArea;
    private final Tm4eSyntaxHighlighter highlighter;
    private final AntlrLanguageService analyzer;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private AntlrCompletionService completionService = null;
    private final Consumer<List<SyntaxError>> onErrorUpdate;
    private final PauseTransition antlrDebouncer;
    private StyleSpans<Collection<String>> lastTm4eSpans; // TM4E 결과를 저장할 필드
    private AnalysisResult lastAnalysisResult; // ANTLR 분석 결과를 저장할 필드
    private CompletableFuture<AnalysisResult> currentAntlrFuture; // 현재 진행중인 ANTLR 분석 작업

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
        EditorInputManager inputManager = new EditorInputManager(codeArea, enhancer, this.completionService);
        inputManager.registerEventHandlers();

        // ANTLR 분석을 1초 지연 실행하기 위한 Debouncer 설정
        this.antlrDebouncer = new PauseTransition(Duration.millis(100));
        this.antlrDebouncer.setOnFinished(e -> runAntlrAnalysis());

        // 텍스트가 변경될 때마다 즉시 TM4E 하이라이팅을 실행하고, ANTLR 분석은 1초 뒤로 예약합니다.
        codeArea.multiPlainChanges()
            .successionEnds(java.time.Duration.ofMillis(50)) // TM4E는 더 빠르게 반응
            .subscribe(ignore -> {
                runTm4eHighlighting();
                antlrDebouncer.playFromStart(); // ANTLR 분석 타이머 재시작
            });
        
        // 커서 위치가 바뀔 때마다 즉시 괄호 강조를 업데이트합니다.
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> updateBracketHighlighting());
    }

    private void runTm4eHighlighting() {
        String text = codeArea.getText();
        CompletableFuture.supplyAsync(() -> highlighter.computeHighlighting(text), analysisExecutor)
            .thenAcceptAsync(tm4eSpans -> {
                this.lastTm4eSpans = tm4eSpans;
                // ANTLR 분석을 기다리지 않고, TM4E 결과라도 먼저 화면에 적용합니다.
                codeArea.setStyleSpans(0, tm4eSpans);
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
            
            if (this.lastTm4eSpans == null) {
                antlrDebouncer.playFromStart(); 
                return;
            }

            this.onErrorUpdate.accept(analysisResult.errors);
            
            // 모든 스타일을 종합하여 화면에 적용합니다.
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

    private void updateBracketHighlighting() {
        // ANTLR 분석 결과가 없으면 아무것도 하지 않습니다.
        if (lastAnalysisResult == null || lastTm4eSpans == null) {
            return;
        }
        // 모든 스타일을 다시 계산하되, 괄호 강조만 새 커서 위치를 기준으로 다시 계산합니다.
        applyHighlighting();
    }

    private void applyHighlighting() {
        if (lastAnalysisResult == null || lastTm4eSpans == null) {
            return;
        }

        int caretPosition = codeArea.getCaretPosition();
        Optional<BracketPair> bracketPair = lastAnalysisResult.bracketMapping.findEnclosingPair(caretPosition);

        StyleSpans<Collection<String>> symbolSpans = lastAnalysisResult.symbolSpans;
        StyleSpans<Collection<String>> errorSpans = computeErrorSpans(lastAnalysisResult.errors);
        StyleSpans<Collection<String>> bracketSpans = computeBracketHighlightSpans(bracketPair);
        
        StyleSpans<Collection<String>> finalSpans = this.lastTm4eSpans
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

    private StyleSpans<Collection<String>> computeErrorSpans(List<SyntaxError> errors) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastKwEnd = 0;
        int totalLength = codeArea.getLength();

        if (errors == null || errors.isEmpty()) {
            spansBuilder.add(Collections.emptyList(), totalLength);
            return spansBuilder.create();
        }

        for (SyntaxError error : errors) {
            int line = error.line - 1;
            if (line < 0 || line >= codeArea.getParagraphs().size()) continue;
            int start = codeArea.getAbsolutePosition(line, error.charPositionInLine);
            int length = error.length;
            if (start + length > totalLength) length = totalLength - start;
            if (length < 0) continue;
            int end = start + length;
            if (start < lastKwEnd) continue;
            
            spansBuilder.add(Collections.emptyList(), start - lastKwEnd);
            spansBuilder.add(Collections.singleton("syntax-error"), length);
            lastKwEnd = end;
        }
        
        if (totalLength > lastKwEnd) {
            spansBuilder.add(Collections.emptyList(), totalLength - lastKwEnd);
        }
        
        return spansBuilder.create();
    }
    
    public void shutdown() {
        if (highlighter != null) highlighter.shutdown();
        if (analyzer != null) analyzer.shutdown();
        analysisExecutor.shutdown();
    }
}