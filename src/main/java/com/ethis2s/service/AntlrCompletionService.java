package com.ethis2s.service;

import com.ethis2s.service.AntlrLanguageService.AnalysisResult;
import com.ethis2s.service.AntlrLanguageService.BracketMapping;
import com.ethis2s.service.AntlrLanguageService.SymbolTable;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * AntlrLanguageService의 분석 결과를 EditorEnhancer가 이해할 수 있는 형태로 변환해주는
 * "어댑터(Adapter)" 또는 "중개인" 클래스. CompletionService 인터페이스를 구현합니다.
 */
public class AntlrCompletionService implements CompletionService {

    private final AntlrLanguageService antlrService;
    private CompletableFuture<AnalysisResult> lastAnalysis;

    public AntlrCompletionService(AntlrLanguageService antlrService) {
        this.antlrService = antlrService;
        // NullPointerException을 방지하기 위해, 초기 분석 결과를 안전한 빈 상태로 설정합니다.
        this.lastAnalysis = CompletableFuture.completedFuture(
            new AnalysisResult(null, Collections.emptyList(), new SymbolTable(), new StyleSpansBuilder<Collection<String>>().add(Collections.emptyList(), 0).create(), new BracketMapping(Collections.emptyMap(), Collections.emptyMap()))
        );
    }
    
    /**
     * HybridManager가 백그라운드에서 새로운 분석을 완료할 때마다
     * 이 메소드를 호출하여 최신 분석 결과를 업데이트해 줍니다.
     * @param newResult 새로운 분석 결과를 담은 CompletableFuture
     */
    public void updateAnalysisResult(CompletableFuture<AnalysisResult> newResult) {
        this.lastAnalysis = newResult;
    }

    /**
     * EditorEnhancer가 자동 완성 제안이 필요할 때 호출하는 메소드.
     * @param code 전체 코드 텍스트
     * @param caretPosition 현재 커서의 절대 위치
     * @return 제안 목록
     */
    @Override
    public List<String> getSuggestions(String code, int caretPosition) {
        // 이 메소드는 UI 스레드에서 호출될 수 있으므로, 절대 오래 걸리는 작업을 하면 안 됩니다.
        try {
            // lastAnalysis.get()을 사용하면 UI가 멈출 수 있으므로,
            // getNow(null)을 사용하여 "즉시" 결과를 가져오고, 아직 분석 중이면 null을 받습니다.
            AnalysisResult result = lastAnalysis.getNow(null); 
            
            if (result != null && antlrService != null) {
                return antlrService.getCompletions(result, code, caretPosition);
            }
        } catch (Exception e) {
            // 만약의 사태에 대비한 예외 처리
            e.printStackTrace();
        }
        
        // 준비가 안됐거나 오류가 발생하면 빈 목록을 반환합니다.
        return Collections.emptyList();
    }
}