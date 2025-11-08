package com.example.util;

import com.example.service.AntlrCompletionService;
import com.example.service.AntlrLanguageService;
import com.example.service.AntlrLanguageService.AnalysisResult;
import com.example.service.AntlrLanguageService.SyntaxError;
import javafx.application.Platform;
import javafx.scene.input.KeyEvent;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CodeArea에 대한 모든 언어 서비스(하이라이팅, 문법 검사 등)를 총괄하는 최종 지휘자 클래스.
 * TM4E와 ANTLR의 실행 순서를 제어하여 충돌을 방지합니다.
 */
public class HybridManager {
    
    private final CodeArea codeArea;
    private final Tm4eSyntaxHighlighter highlighter;
    private final AntlrLanguageService analyzer;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private AntlrCompletionService completionService = null;

    public HybridManager(CodeArea codeArea, String fileExtension) {
        this.codeArea = codeArea;
        
        System.out.println("[DEBUG] HybridManager: Initializing for extension '" + fileExtension + "'...");
        
        this.highlighter = new Tm4eSyntaxHighlighter(codeArea, fileExtension);

        if (AntlrLanguageService.isSupported(fileExtension)) {
            this.analyzer = new AntlrLanguageService(fileExtension);
            this.completionService = new AntlrCompletionService(this.analyzer);
            System.out.println("[DEBUG] HybridManager: ANTLR support is ENABLED.");
        } else {
            this.analyzer = null;
            this.completionService = null;
            System.out.println("[DEBUG] HybridManager: ANTLR support is DISABLED for this language.");
        }
        
        EditorEnhancer.enable(codeArea, this.completionService);
        System.out.println("[DEBUG] HybridManager: EditorEnhancer has been enabled.");

        codeArea.multiPlainChanges()
            .successionEnds(Duration.ofMillis(100)) // 반응성을 위해 딜레이 조절
            .subscribe(ignore -> {
                String text = codeArea.getText();

                // 1. 두 개의 비동기 작업을 동시에 시작합니다.
                CompletableFuture<StyleSpans<Collection<String>>> tm4eFuture = 
                    CompletableFuture.supplyAsync(() -> highlighter.computeHighlighting(text), analysisExecutor);

                CompletableFuture<AnalysisResult> antlrFuture = 
                    (analyzer != null) ? analyzer.analyze(text) : 
                    CompletableFuture.completedFuture(new AnalysisResult(null, Collections.emptyList(), null));
                
                if (completionService != null) {
                    completionService.updateAnalysisResult(antlrFuture);
                }

                // 2. [핵심] 두 작업이 "모두" 끝나기를 기다렸다가,
                //    두 결과물을 완벽하게 합쳐서 최종 스타일을 만듭니다.
                tm4eFuture.thenCombineAsync(antlrFuture, (baseSpans, analysisResult) -> {
                    // 2a. ANTLR의 오류 정보로 '오류 덧칠 설계도'를 만듭니다.
                    StyleSpans<Collection<String>> errorSpans = computeErrorSpans(analysisResult.errors);
                    System.out.println("[DEBUG]   - Created " + errorSpans.getSpanCount() + " error spans.");
                    // 2b. TM4E의 기본 설계도 위에 오류 설계도를 덧칠하여 "최종 마스터 설계도"를 완성합니다.
                    //     이것이 매번 새로 그려지는 '완벽한 그림'입니다.
                    return baseSpans.overlay(errorSpans, (baseStyle, errorStyle) -> {
                        // errorStyle이 비어있지 않으면 (즉, 오류가 있으면), 두 스타일을 합칩니다.
                        if (!errorStyle.isEmpty()) {
                            Set<String> combined = new HashSet<>(baseStyle);
                            combined.addAll(errorStyle);
                            return combined;
                        }
                        // 오류가 없으면, 기존 TM4E 스타일을 그대로 사용합니다.
                        return baseStyle;
                    });
                }, Platform::runLater) // 덧칠 작업은 UI 스레드에서
                .thenAcceptAsync(finalSpans -> {
                    // 3. 완성된 최종 설계도로 "단 한 번만" 화면 전체를 새로 그립니다.
                    //    이 작업은 이전의 모든 스타일(유령 오류 포함)을 완전히 지우고 새로 칠합니다.
                    codeArea.setStyleSpans(0, finalSpans);
                }, Platform::runLater);
            });
            
        // 초기 하이라이팅은 EditorTabView에서 텍스트를 삽입할 때 자동으로 트리거됩니다.
        System.out.println("[DEBUG] HybridManager: Initialization complete. Waiting for text changes.");
    }

    // HybridManager.java

    /**
     * ANTLR이 반환한 문법 오류 목록을 바탕으로, 'syntax-error' 스타일만 포함하는
     * 순수한 "오류 덧칠 설계도(StyleSpans)"를 생성하는 헬퍼 메소드.
     * @param errors AntlrLanguageService가 반환한 오류 목록
     * @return 오류 위치에만 스타일이 적용된 StyleSpans 객체
     */
    private StyleSpans<Collection<String>> computeErrorSpans(List<SyntaxError> errors) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastKwEnd = 0;
        int totalLength = codeArea.getLength();

        // 오류가 없다면, 빈 스타일을 가진 StyleSpans를 반환합니다.
        if (errors == null || errors.isEmpty()) {
            spansBuilder.add(Collections.emptyList(), totalLength);
            return spansBuilder.create();
        }

        for (SyntaxError error : errors) {
            int line = error.line - 1;
            // 안전장치: 존재하지 않는 라인을 참조할 경우 건너뜁니다.
            if (line < 0 || line >= codeArea.getParagraphs().size()) {
                continue;
            }

            int start = codeArea.getAbsolutePosition(line, error.charPositionInLine);
            int length = error.length;
            
            // 안전장치: 계산된 위치가 텍스트 범위를 벗어나지 않도록 보정합니다.
            if (start + length > totalLength) {
                length = totalLength - start;
            }
            if (length < 0) {
                continue;
            }
            int end = start + length;
            
            if (start < lastKwEnd) {
                // 오류 위치가 겹치는 매우 드문 경우에 대한 안전장치
                continue;
            }
            
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