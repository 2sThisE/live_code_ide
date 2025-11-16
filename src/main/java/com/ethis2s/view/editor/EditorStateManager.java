package com.ethis2s.view.editor;

import com.ethis2s.service.AntlrLanguageService;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.HybridManager;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import org.fxmisc.richtext.CodeArea;

import java.util.*;

/**
 * 모든 에디터 탭의 상태를 중앙에서 관리하는 클래스입니다.
 * 탭 ID를 키로 사용하여 CodeArea, HybridManager, 에러 목록, 검색 결과 등을 관리합니다.
 */
public class EditorStateManager {

    // --- State Fields ---
    private final Map<String, CodeArea> codeAreaMap = new HashMap<>();
    private final Map<String, HybridManager> hybridManagerMap = new HashMap<>();
    private final Map<String, List<SyntaxError>> tabErrors = new HashMap<>();
    private final Map<String, String> tabFileNames = new HashMap<>();
    private final Map<String, List<Integer>> searchResultsMap = new HashMap<>();
    private final Map<String, Integer> currentMatchIndexMap = new HashMap<>();
    private final List<HybridManager> activeManagers = new ArrayList<>();

    // --- Search Properties ---
    private final IntegerProperty totalMatches = new SimpleIntegerProperty(0);
    private final IntegerProperty currentMatchIndex = new SimpleIntegerProperty(0);

    // --- Public API for State Management ---

    public void registerTab(String tabId, String fileName, CodeArea codeArea, HybridManager manager) {
        codeAreaMap.put(tabId, codeArea);
        hybridManagerMap.put(tabId, manager);
        tabFileNames.put(tabId, fileName);
        tabErrors.put(tabId, new ArrayList<>());
        searchResultsMap.put(tabId, new ArrayList<>());
        currentMatchIndexMap.put(tabId, -1);
        activeManagers.add(manager);
    }

    public void unregisterTab(String tabId) {
        HybridManager manager = hybridManagerMap.remove(tabId);
        if (manager != null) {
            manager.shutdown();
            activeManagers.remove(manager);
        }
        codeAreaMap.remove(tabId);
        tabFileNames.remove(tabId);
        tabErrors.remove(tabId);
        searchResultsMap.remove(tabId);
        currentMatchIndexMap.remove(tabId);
    }

    public void shutdownAllManagers() {
        activeManagers.forEach(HybridManager::shutdown);
        activeManagers.clear();
    }

    // --- Getters and Helpers ---

    public Optional<CodeArea> getCodeArea(String tabId) {
        return Optional.ofNullable(codeAreaMap.get(tabId));
    }

    public Optional<HybridManager> getHybridManager(String tabId) {
        return Optional.ofNullable(hybridManagerMap.get(tabId));
    }

    public Map<String, List<SyntaxError>> getAllTabErrors() {
        return tabErrors;
    }
    
    public List<SyntaxError> getErrorsForTab(String tabId) {
        return tabErrors.getOrDefault(tabId, Collections.emptyList());
    }

    public void updateErrorsForTab(String tabId, List<SyntaxError> errors) {
        tabErrors.put(tabId, errors);
    }

    public Optional<String> getFileName(String tabId) {
        return Optional.ofNullable(tabFileNames.get(tabId));
    }

    public Optional<String> findTabIdForCodeArea(CodeArea codeArea) {
        return codeAreaMap.entrySet().stream()
            .filter(entry -> entry.getValue().equals(codeArea))
            .map(Map.Entry::getKey)
            .findFirst();
    }
    
    public Collection<CodeArea> getAllCodeAreas() {
        return codeAreaMap.values();
    }

    public List<Integer> getSearchResults(String tabId) {
        return searchResultsMap.computeIfAbsent(tabId, k -> new ArrayList<>());
    }
    
    public int getCurrentMatchIndex(String tabId) {
        return currentMatchIndexMap.getOrDefault(tabId, -1);
    }

    public void setCurrentMatchIndex(String tabId, int index) {
        currentMatchIndexMap.put(tabId, index);
    }

    public IntegerProperty totalMatchesProperty() {
        return totalMatches;
    }

    public IntegerProperty currentMatchIndexProperty() {
        return currentMatchIndex;
    }
}