package com.ethis2s.util;

import com.ethis2s.controller.MainController;
import com.ethis2s.util.Tm4eSyntaxHighlighter.StyleToken;

import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 활성 에디터의 텍스트 검색 기능을 담당하는 클래스입니다.
 * 검색 실행, 결과 하이라이팅, 다음/이전 결과로 이동하는 기능을 제공합니다.
 */
public class EditorSearchHandler {

    private final EditorStateManager stateManager;
    private final MainController mainController;

    public EditorSearchHandler(EditorStateManager stateManager, MainController mainController) {
        this.stateManager = stateManager;
        this.mainController = mainController;
    }

    public void performSearch(CodeArea activeCodeArea, String query, boolean caseSensitive) {
        stateManager.findTabIdForCodeArea(activeCodeArea).ifPresent(tabId -> {
            List<Integer> results = stateManager.getSearchResults(tabId);
            results.clear();

            if (query.isEmpty()) {
                highlightMatches(activeCodeArea, tabId, query);
                stateManager.totalMatchesProperty().set(0);
                stateManager.currentMatchIndexProperty().set(0);
                return;
            }

            String text = activeCodeArea.getText();
            Pattern pattern = caseSensitive ? Pattern.compile(Pattern.quote(query))
                                            : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) results.add(matcher.start());
            
            stateManager.totalMatchesProperty().set(results.size());
            highlightMatches(activeCodeArea, tabId, query);

            if (!results.isEmpty()) {
                stateManager.setCurrentMatchIndex(tabId, 0);
                stateManager.currentMatchIndexProperty().set(1);
                goToMatch(activeCodeArea, tabId, 0);
            } else {
                stateManager.currentMatchIndexProperty().set(0);
            }
        });
    }

    public void goToNextMatch(CodeArea activeCodeArea) {
        stateManager.findTabIdForCodeArea(activeCodeArea).ifPresent(tabId -> {
            List<Integer> results = stateManager.getSearchResults(tabId);
            if (results.isEmpty()) return;

            int currentIndex = stateManager.getCurrentMatchIndex(tabId);
            currentIndex = (currentIndex + 1) % results.size();
            stateManager.setCurrentMatchIndex(tabId, currentIndex);
            stateManager.currentMatchIndexProperty().set(currentIndex + 1);
            goToMatch(activeCodeArea, tabId, currentIndex);
        });
    }

    public void goToPreviousMatch(CodeArea activeCodeArea) {
        stateManager.findTabIdForCodeArea(activeCodeArea).ifPresent(tabId -> {
            List<Integer> results = stateManager.getSearchResults(tabId);
            if (results.isEmpty()) return;

            int currentIndex = stateManager.getCurrentMatchIndex(tabId);
            currentIndex = (currentIndex - 1 + results.size()) % results.size();
            stateManager.setCurrentMatchIndex(tabId, currentIndex);
            stateManager.currentMatchIndexProperty().set(currentIndex + 1);
            goToMatch(activeCodeArea, tabId, currentIndex);
        });
    }

    private void highlightMatches(CodeArea codeArea, String tabId, String query) {
        Optional<HybridManager> managerOpt = stateManager.getHybridManager(tabId);
        if (managerOpt.isEmpty()) return;

        List<Integer> searchResults = stateManager.getSearchResults(tabId);
        if (query.isEmpty() || searchResults.isEmpty()) {
            managerOpt.get().updateSearchHighlights(Collections.emptyList());
            return;
        }

        List<StyleToken> searchTokens = new ArrayList<>();
        for (Integer start : searchResults) {
            int end = start + query.length();
            searchTokens.add(new StyleToken(start, end, Collections.singletonList("search-highlight")));
        }
        
        managerOpt.get().updateSearchHighlights(searchTokens);
    }

    private void goToMatch(CodeArea codeArea, String tabId, int index) {
        List<Integer> results = stateManager.getSearchResults(tabId);
        if (index < 0 || index >= results.size()) return;

        int pos = results.get(index);
        String query = mainController.getSearchQuery();
        if (query.isEmpty()) return;

        codeArea.selectRange(pos, pos + query.length());
        codeArea.requestFollowCaret();
    }
}