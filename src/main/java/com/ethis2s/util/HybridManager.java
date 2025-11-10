package com.ethis2s.util;

import com.ethis2s.service.AntlrCompletionService;
import com.ethis2s.service.AntlrLanguageService;
import com.ethis2s.service.AntlrLanguageService.AnalysisResult;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import javafx.application.Platform;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import com.ethis2s.service.AntlrLanguageService.BracketPair;
import org.antlr.v4.runtime.Token;
/**
 * CodeArea에 대한 모든 언어 서비스(하이라이팅, 문법 검사 등)를 총괄하는 최종 지휘자 클래스.
 * TM4E로 기본 하이라이팅을, ANTLR로 심볼 하이라이팅과 오류 검사를 수행하여 결합합니다.
 */



public class HybridManager {
    
    private final CodeArea codeArea;
    private final Tm4eSyntaxHighlighter highlighter;
    private final AntlrLanguageService analyzer;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private AntlrCompletionService completionService = null;
    private final Consumer<List<SyntaxError>> onErrorUpdate; // 에러 정보를 UI에 전달할 콜백

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

        // 텍스트가 변경되거나 커서 위치가 바뀔 때마다 분석 및 스타일링을 다시 실행합니다.
        codeArea.multiPlainChanges()
            .successionEnds(Duration.ofMillis(200))
            .subscribe(ignore -> runAnalysesAndApplyStyling());
        
        codeArea.caretPositionProperty()
            .addListener((obs, oldPos, newPos) -> runAnalysesAndApplyStyling());
    }

    /**
     * ���든 분석을 실행하고 그 결과를 종합하여 CodeArea에 스타일을 적용하는 핵심 메소드.
     */
    private void runAnalysesAndApplyStyling() {
        String text = codeArea.getText();
        int caretPosition = codeArea.getCaretPosition();

        // TM4E 기본 하이라이팅 (비동기)
        CompletableFuture<StyleSpans<Collection<String>>> tm4eFuture = 
            CompletableFuture.supplyAsync(() -> highlighter.computeHighlighting(text), analysisExecutor);

        // ANTLR 심층 분석 (비동기), 커서 위치 전달
        CompletableFuture<AnalysisResult> antlrFuture = 
            (analyzer != null) ? analyzer.analyze(text, caretPosition) : 
            CompletableFuture.completedFuture(new AnalysisResult(null, Collections.emptyList(), new AntlrLanguageService.SymbolTable(), new StyleSpansBuilder<Collection<String>>().add(Collections.emptyList(), 0).create(), Optional.empty()));
        
        if (completionService != null) {
            completionService.updateAnalysisResult(antlrFuture);
        }

        // 두 분석이 모두 끝나면 결과를 종합하여 UI 스레드에서 스타일 적용
        tm4eFuture.thenCombineAsync(antlrFuture, (baseSpans, analysisResult) -> {
            Platform.runLater(() -> this.onErrorUpdate.accept(analysisResult.errors));

            StyleSpans<Collection<String>> symbolSpans = analysisResult.symbolSpans;
            StyleSpans<Collection<String>> errorSpans = computeErrorSpans(analysisResult.errors);
            StyleSpans<Collection<String>> bracketSpans = computeBracketHighlightSpans(analysisResult.bracketPair);
            
            // 모든 스타일 레이어를 순서대로 겹쳐 최종 스타일을 만듭니다.
            return baseSpans
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
                    // 괄호 하이라이트는 다른 스타일과 겹칠 수 있으므로, 스타일을 추가합니다.
                    if (!bracket.isEmpty()) {
                        Set<String> combined = new HashSet<>(base);
                        combined.addAll(bracket);
                        return combined;
                    }
                    return base;
                });

        }, Platform::runLater)
        .thenAcceptAsync(finalSpans -> {
            codeArea.setStyleSpans(0, finalSpans);
        }, Platform::runLater);
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