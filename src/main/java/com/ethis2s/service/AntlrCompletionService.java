package com.ethis2s.service;

import com.ethis2s.service.AntlrLanguageService.AnalysisResult;
import com.ethis2s.service.AntlrLanguageService.BracketMapping;
import com.ethis2s.service.AntlrLanguageService.SymbolTable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AntlrCompletionService implements CompletionService {

    private final AntlrLanguageService antlrService;
    private AnalysisResult lastKnownResult; // 과거 분석 결과를 저장할 캐시
    private Consumer<List<String>> onCompletionUpdated; // 분석 완료 후 호출할 콜백

    public AntlrCompletionService(AntlrLanguageService antlrService) {
        this.antlrService = antlrService;
        this.lastKnownResult = new AnalysisResult(null, Collections.emptyList(), new SymbolTable(), Collections.emptyList(), new BracketMapping(Collections.emptyMap(), Collections.emptyMap()));
    }

    public void setOnCompletionUpdated(Consumer<List<String>> onCompletionUpdated) {
        this.onCompletionUpdated = onCompletionUpdated;
    }

    public void updateAnalysisResult(CompletableFuture<AnalysisResult> newResultFuture) {
        newResultFuture.thenAccept(newResult -> {
            this.lastKnownResult = newResult;
            // 분석이 완료되면, 콜백을 호출하여 EditorEnhancer에게 알림
            if (onCompletionUpdated != null) {
                // 현재 상태에서 유효한 제안 목록을 다시 계산하여 전달
                // 이 부분은 실제 사용 시점의 코드와 커서 위치를 알아야 더 정확해짐
                // 지금은 일단 빈 값으로 트리거만 해주는 방식으로 구현
                onCompletionUpdated.accept(Collections.emptyList()); 
            }
        });
    }

    @Override
    public List<String> getSuggestions(String code, int caretPosition) {
        // 항상 캐시된 과거의 결과(lastKnownResult)를 사용하여 즉시 반환
        return getCompletions(lastKnownResult, code, caretPosition);
    }

    // AntlrLanguageService에서 가져온 로직
    private List<String> getCompletions(AnalysisResult result, String text, int caretPosition) {
        if (result == null) return Collections.emptyList();
        
        int start = caretPosition - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) {
            start--;
        }
        String prefix = text.substring(start + 1, caretPosition).toLowerCase();

        Set<String> suggestions = new HashSet<>();
        
        if (result.symbolTable != null) {
            suggestions.addAll(result.symbolTable.symbols.keySet());
        }
        
        if (antlrService != null) {
            String fileExtension = antlrService.getFileExtension(); 
            List<String> keywords = antlrService.getLanguageKeywords().get(fileExtension);
            if (keywords != null) {
                suggestions.addAll(keywords);
            }
        }
        
        return suggestions.stream()
            .filter(s -> s.toLowerCase().startsWith(prefix))
            .sorted()
            .collect(Collectors.toList());
    }
}