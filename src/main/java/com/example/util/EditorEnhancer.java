package com.example.util;

import com.example.service.CompletionService;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.ListView;
import javafx.scene.control.PopupControl;
import org.fxmisc.richtext.CodeArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EditorEnhancer {

    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^\\s*");
    private final AtomicInteger suggestionRequestCounter = new AtomicInteger(0);
    private final CodeArea codeArea;
    private final CompletionService completionService;
    private final PopupControl suggestionsPopup;
    private final ListView<String> suggestionsListView;

    public EditorEnhancer(CodeArea codeArea, CompletionService completionService) {
        this.codeArea = codeArea;
        this.completionService = completionService;
        
        this.suggestionsListView = new ListView<>();
        this.suggestionsListView.setId("suggestion-list-view"); // ID 부여
        this.suggestionsPopup = new PopupControl();
        this.suggestionsPopup.setAutoHide(true);
        this.suggestionsPopup.getScene().setRoot(suggestionsListView);

        // ListView에서 항목을 마우스로 클릭했을 때의 동작
        this.suggestionsListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                commitSelectedSuggestion();
                event.consume();
            }
        });
    }

    public static void enable(CodeArea codeArea, CompletionService completionService) {
        EditorEnhancer enhancer = new EditorEnhancer(codeArea, completionService);
        enhancer.registerEventHandlers();
    }
    
    private void registerEventHandlers() {
        // --- KEY_TYPED 이벤트 처리 ---
        codeArea.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            // 괄호/따옴표 자동 완성은 항상 작동합니다.
            handleAutoPairing(e);
            
            String typedChar = e.getCharacter();
            if (completionService != null && !typedChar.isEmpty() && Character.isJavaIdentifierPart(typedChar.charAt(0))) {
                showSuggestionsAsync();
            } else if (suggestionsPopup.isShowing()) {
                suggestionsPopup.hide();
            }
        });

        // --- KEY_PRESSED 이벤트 처리 (변경 없음, 이미 안전함) ---
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (suggestionsPopup.isShowing()) {
                handlePopupKeyPress(e);
            } else {
                handleNormalKeyPress(e);
            }
        });
    }

    private void handleNormalKeyPress(KeyEvent e) {
        if (e.getCode() == KeyCode.TAB) {
            codeArea.replaceSelection("\t");
            e.consume();
        } else if (e.getCode() == KeyCode.ENTER) {
            handleAutoIndent(e);
        } else if (e.getCode() == KeyCode.BACK_SPACE) {
            handleTabBackspace(e);
        }
    }
    

    private void handleTabBackspace(KeyEvent e) {
        int caretPosition = codeArea.getCaretPosition();
        if (caretPosition == 0) return;

        // 커서 바로 앞의 한 글자를 확인합니다.
        String precedingChar = codeArea.getText(caretPosition - 1, caretPosition);

        // 그 글자가 탭('\t') 문자인 경우에만 전체를 지웁니다.
        if (precedingChar.equals("\t")) {
            codeArea.deleteText(caretPosition - 1, caretPosition);
            e.consume(); // 이벤트 소비
        }
        // 탭이 아닌 다른 문자(공백 포함)는 기본 백스페이스 동작에 맡깁니다.
    }

    private void handlePopupKeyPress(KeyEvent e) {
        switch (e.getCode()) {
            case UP:
                selectPreviousSuggestion();
                e.consume();
                break;
            case DOWN:
                selectNextSuggestion();
                e.consume();
                break;
            case ENTER:
            case TAB:
                commitSelectedSuggestion();
                e.consume();
                break;
            case ESCAPE:
                suggestionsPopup.hide();
                e.consume();
                break;
            default:
                break;
        }
    }

    private void showSuggestionsAsync() {
        final int requestId = suggestionRequestCounter.incrementAndGet();

        Platform.runLater(() -> {
            if (requestId != suggestionRequestCounter.get()) return;

            String code = codeArea.getText();
            int caretPosition = codeArea.getCaretPosition();
            List<String> suggestions = completionService.getSuggestions(code, caretPosition);

            if (requestId != suggestionRequestCounter.get()) return;

            if (suggestions == null || suggestions.isEmpty()) {
                suggestionsPopup.hide();
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

    private void populatePopup(List<String> suggestions) {
        suggestionsListView.getItems().setAll(suggestions);
        // 팝업 높이를 일정하게 고정하여, 추천 항목 수에 따라 크기가 변하는 것을 방지합니다.
        suggestionsListView.setPrefHeight(150);
    }

    private void selectSuggestion(int index) {
        if (index < 0 || index >= suggestionsListView.getItems().size()) return;
        suggestionsListView.getSelectionModel().select(index);
        suggestionsListView.scrollTo(index);
    }

    private void selectNextSuggestion() {
        int currentIndex = suggestionsListView.getSelectionModel().getSelectedIndex();
        int newIndex = currentIndex + 1;
        if (newIndex >= suggestionsListView.getItems().size()) {
            newIndex = 0;
        }
        selectSuggestion(newIndex);
    }

    private void selectPreviousSuggestion() {
        int currentIndex = suggestionsListView.getSelectionModel().getSelectedIndex();
        int newIndex = currentIndex - 1;
        if (newIndex < 0) {
            newIndex = suggestionsListView.getItems().size() - 1;
        }
        selectSuggestion(newIndex);
    }

    private void commitSelectedSuggestion() {
        String suggestion = suggestionsListView.getSelectionModel().getSelectedItem();
        if (suggestion != null) {
            insertSuggestion(suggestion);
            suggestionsPopup.hide();
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

    private void handleAutoPairing(KeyEvent e) {
        String typedChar = e.getCharacter();
        if (typedChar.isEmpty() || typedChar.length() > 1) return;
        
        int caretPosition = codeArea.getCaretPosition();
        String closingChar = getClosingChar(typedChar);

        if (closingChar != null) {
            if (suggestionsPopup.isShowing()) {
                suggestionsPopup.hide();
            }
            codeArea.insertText(caretPosition, closingChar);
            codeArea.moveTo(caretPosition);
            e.consume();
            return;
        }

        if (isClosingChar(typedChar)) {
            if (suggestionsPopup.isShowing()) return;
            if (caretPosition < codeArea.getLength()) {
                String nextChar = codeArea.getText(caretPosition, caretPosition + 1);
                if (typedChar.equals(nextChar)) {
                    codeArea.moveTo(caretPosition + 1);
                    e.consume();
                }
            }
        }
    }

    private void handleAutoIndent(KeyEvent e) {
        int caretPosition = codeArea.getCaretPosition();
        int currentParagraph = codeArea.getCurrentParagraph();
        String currentLine = codeArea.getParagraph(currentParagraph).getText();
        int caretColumn = codeArea.getCaretColumn();
        String textBeforeCaret = currentLine.substring(0, caretColumn);

        Matcher matcher = LEADING_WHITESPACE.matcher(currentLine);
        String indent = matcher.find() ? matcher.group() : "";
        String extraIndent = "\t";

        boolean isBetweenBraces = caretPosition > 0 &&
                                  caretPosition < codeArea.getLength() &&
                                  codeArea.getText(caretPosition - 1, caretPosition).equals("{") &&
                                  codeArea.getText(caretPosition, caretPosition + 1).equals("}");

        if (isBetweenBraces) {
            StringBuilder toInsert = new StringBuilder()
                    .append("\n").append(indent).append(extraIndent)
                    .append("\n").append(indent);
            codeArea.replaceSelection(toInsert.toString());
            codeArea.moveTo(caretPosition + 1 + indent.length() + extraIndent.length());
        } else {
            String bracesOnly = textBeforeCaret.replaceAll("[^{\\}]", "");
            if (!bracesOnly.isEmpty() && bracesOnly.endsWith("{")) {
                codeArea.replaceSelection("\n" + indent + extraIndent);
            }
            else {
                codeArea.replaceSelection("\n" + indent);
            }
        }
        e.consume();
    }
    
    private String getClosingChar(String s) {
        switch (s) {
            case "(": return ")";
            case "{": return "}";
            case "[": return "]";
            case "'": return "'";
            case "\"": return "\"";
            default: return null;
        }
    }

    private boolean isClosingChar(String s) {
        return ")".equals(s) || "}".equals(s) || "]".equals(s) || "'".equals(s) || "\"".equals(s);
    }
}