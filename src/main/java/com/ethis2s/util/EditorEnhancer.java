package com.ethis2s.util;

import com.ethis2s.service.CompletionService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.ListView;
import javafx.scene.control.PopupControl;
import javafx.scene.input.MouseButton;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 자동 완성 제안 팝업의 UI를 생성하고 관리하는 클래스.
 * EditorInputManager의 요청에 따라 팝업을 보여주거나 숨기는 등 UI 관련 작업만 처리한다.
 */
public class EditorEnhancer {

    private final AtomicInteger suggestionRequestCounter = new AtomicInteger(0);
    private final CodeArea codeArea;
    private final CompletionService completionService;
    private final PopupControl suggestionsPopup;
    private final ListView<String> suggestionsListView;

    public EditorEnhancer(CodeArea codeArea, CompletionService completionService) {
        this.codeArea = codeArea;
        this.completionService = completionService;

        this.suggestionsListView = new ListView<>();
        this.suggestionsListView.setId("suggestion-list-view");
        this.suggestionsPopup = new PopupControl();
        this.suggestionsPopup.setAutoHide(true);
        this.suggestionsPopup.getScene().setRoot(suggestionsListView);

        this.suggestionsListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                commitSelectedSuggestion();
                event.consume();
            }
        });
    }

    public void showSuggestionsAsync() {
        final int requestId = suggestionRequestCounter.incrementAndGet();

        Platform.runLater(() -> {
            if (requestId != suggestionRequestCounter.get()) {
                return;
            }

            String code = codeArea.getText();
            int caretPosition = codeArea.getCaretPosition();
            List<String> suggestions = completionService.getSuggestions(code, caretPosition);

            if (requestId != suggestionRequestCounter.get()) {
                return;
            }

            if (suggestions == null || suggestions.isEmpty()) {
                hideSuggestions();
                return;
            }

            populatePopup(suggestions);

            Optional<Bounds> caretBounds = codeArea.getCaretBounds();
            if (caretBounds.isPresent()) {
                Bounds bounds = caretBounds.get();
                suggestionsPopup.show(codeArea, bounds.getMinX(), bounds.getMaxY());
                Platform.runLater(() -> selectSuggestion(0));
            }
        });
    }

    public void hideSuggestions() {
        suggestionRequestCounter.incrementAndGet();
        suggestionsPopup.hide();
    }

    public boolean isPopupShowing() {
        return suggestionsPopup.isShowing();
    }

    public void commitSelectedSuggestion() {
        String suggestion = suggestionsListView.getSelectionModel().getSelectedItem();
        if (suggestion != null) {
            insertSuggestion(suggestion);
            hideSuggestions();
        }
    }

    public void selectNextSuggestion() {
        int currentIndex = suggestionsListView.getSelectionModel().getSelectedIndex();
        int newIndex = currentIndex + 1;
        if (newIndex >= suggestionsListView.getItems().size()) {
            newIndex = 0;
        }
        selectSuggestion(newIndex);
    }

    public void selectPreviousSuggestion() {
        int currentIndex = suggestionsListView.getSelectionModel().getSelectedIndex();
        int newIndex = currentIndex - 1;
        if (newIndex < 0) {
            newIndex = suggestionsListView.getItems().size() - 1;
        }
        selectSuggestion(newIndex);
    }

    private void populatePopup(List<String> suggestions) {
        suggestionsListView.getItems().setAll(suggestions);
        suggestionsListView.setPrefHeight(150);
    }

    private void selectSuggestion(int index) {
        if (index >= 0 && index < suggestionsListView.getItems().size()) {
            suggestionsListView.getSelectionModel().select(index);
            suggestionsListView.scrollTo(index);
        }
    }

    private void insertSuggestion(String suggestion) {
        int caretPosition = codeArea.getCaretPosition();
        String text = codeArea.getText();

        int start = caretPosition - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) {
            start--;
        }

        codeArea.replaceText(start + 1, caretPosition, suggestion);
    }
}
