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
        
        // completionService가 null이어도 EditorEnhancer와 EditorInputManager를 항상 설정합니다.
        // 이렇게 하면 자동 완성을 제외한 편의 기능(괄호, 탭 등)이 항상 동작합니다.
        EditorEnhancer enhancer = new EditorEnhancer(codeArea, this.completionService);
        EditorInputManager inputManager = new EditorInputManager(codeArea, enhancer, this.completionService);
        inputManager.registerEventHandlers();

        codeArea.multiPlainChanges()
            .successionEnds(Duration.ofMillis(100))
            .subscribe(ignore -> {
                String text = codeArea.getText();

                CompletableFuture<StyleSpans<Collection<String>>> tm4eFuture = 
                    CompletableFuture.supplyAsync(() -> highlighter.computeHighlighting(text), analysisExecutor);

                CompletableFuture<AnalysisResult> antlrFuture = 
                    (analyzer != null) ? analyzer.analyze(text) : 
                    CompletableFuture.completedFuture(new AnalysisResult(null, Collections.emptyList(), new AntlrLanguageService.SymbolTable(), new StyleSpansBuilder<Collection<String>>().add(Collections.emptyList(), 0).create()));
                
                if (completionService != null) {
                    completionService.updateAnalysisResult(antlrFuture);
                }

                tm4eFuture.thenCombineAsync(antlrFuture, (baseSpans, analysisResult) -> {
                    // 에러 정보를 콜백을 통해 UI 계층으로 전달
                    Platform.runLater(() -> this.onErrorUpdate.accept(analysisResult.errors));

                    // [수정] 이제 ANTLR 서비스가 만들어준 완성된 symbolSpans를 바로 사용합니다.
                    StyleSpans<Collection<String>> symbolSpans = analysisResult.symbolSpans;
                    StyleSpans<Collection<String>> errorSpans = computeErrorSpans(analysisResult.errors);
                    
                    // 3. TM4E 기본 설계도 -> 심볼 덧칠 -> 오류 덧칠 순서로 합친다.
                    return baseSpans.overlay(symbolSpans, (base, symbol) -> !symbol.isEmpty() ? symbol : base)
                                    .overlay(errorSpans, (base, error) -> {
                                        if (!error.isEmpty()) {
                                            Set<String> combined = new HashSet<>(base);
                                            combined.addAll(error);
                                            return combined;
                                        }
                                        return base;
                                    });
                }, Platform::runLater)
                .thenAcceptAsync(finalSpans -> {
                    codeArea.setStyleSpans(0, finalSpans);
                }, Platform::runLater);
            });
    }

    /**
     * ANTLR 분석 결과(심볼 테이블, 토큰)를 바탕으로 사용자 정의 심볼에 대한
     * '덧칠 설계도'를 생성합니다.
     */
    // private StyleSpans<Collection<String>> computeSymbolSpans(AnalysisResult result) {
    //     int totalLength = codeArea.getLength();
    //     StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        
    //     if (result == null || result.tokens == null || result.vocabulary == null || result.symbolTable == null) {
    //         return spansBuilder.add(Collections.emptyList(), totalLength).create();
    //     }

    //     Vocabulary vocabulary = result.vocabulary;
    //     // [수정] Java g4 문법의 실제 Identifier 이름
    //     String identifierRuleName = "Identifier"; 
        
    //     int lastKwEnd = 0;
    //     for (Token token : result.tokens) {
    //         String symbolicName = vocabulary.getSymbolicName(token.getType());

    //         // [수정] symbolicName이 null이 아니고, IdentifierRuleName과 일치할 때만
    //         if (symbolicName != null && symbolicName.equals(identifierRuleName)) {
    //             String tokenText = token.getText();
    //             Symbol symbol = result.symbolTable.resolve(tokenText);
                
    //             if (symbol != null) {
    //                 String styleClass = switch (symbol.kind) {
    //                     case CLASS -> "entity-name-type";
    //                     case METHOD -> "entity-name-function";
    //                     case VARIABLE -> "variable";
    //                     default -> "";
    //                 };
                    
    //                 if (!styleClass.isEmpty()) {
    //                     int start = token.getStartIndex();
    //                     int end = token.getStopIndex() + 1;

    //                     if (start >= lastKwEnd) {
    //                          spansBuilder.add(Collections.emptyList(), start - lastKwEnd);
    //                          spansBuilder.add(Arrays.asList("text", styleClass), end - start);
    //                          lastKwEnd = end;
    //                     }
    //                 }
    //             }
    //         }
    //     }
    //     spansBuilder.add(Collections.emptyList(), totalLength - lastKwEnd);
    //     return spansBuilder.create();
    // }

    /**
     * ANTLR 오류 목록을 바탕으로 '오류 덧칠 설계도'를 생성합니다. (기존과 동일)
     */
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