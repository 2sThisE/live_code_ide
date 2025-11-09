package com.ethis2s.util;

import com.ethis2s.service.CompletionService;
import javafx.application.Platform;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.fxmisc.richtext.model.TwoDimensional.Position;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EditorInputManager {

    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^\\s*");
    private static final Map<String, String> PAIR_MAP = Map.of(
            "(", ")",
            "{", "}",
            "[", "]",
            "'", "'",
            "\"", "\""
    );

    private final CodeArea codeArea;
    private final EditorEnhancer enhancer;
    private final CompletionService completionService;

    private boolean suggestionsHiddenManually = false;

    public EditorInputManager(CodeArea codeArea, EditorEnhancer enhancer, CompletionService completionService) {
        this.codeArea = codeArea;
        this.enhancer = enhancer;
        this.completionService = completionService;
    }

    public void registerEventHandlers() {
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, this::handleKeyTyped);
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            // 붙여넣기는 보통 한 번에 여러 줄이 변경되므로, 변경된 텍스트 전체를 검사합니다.
            // 현재 캐럿 위치를 기준으로 변경이 일어난 범위를 추정합니다.
            int currentParagraph = codeArea.getCurrentParagraph();
            int startParagraph = Math.max(0, currentParagraph - 50); // 예: 변경된 줄 위로 50줄
            int endParagraph = Math.min(codeArea.getParagraphs().size() - 1, currentParagraph + 50); // 아래로 50줄

            // Platform.runLater를 사용하여 UI 업데이트가 끝난 후 안정적인 상태에서 실행
            Platform.runLater(() -> {
                for (int i = startParagraph; i <= endParagraph; i++) {
                    optimizeIndent(i);
                }
            });
        });
    }
    public void optimizeIndent(int paragraphIndex) {
    int tabSize = ConfigManager.TAB_SIZE;
    if (tabSize <= 1) return;

    // 라인이 유효한지 확인
    if (paragraphIndex < 0 || paragraphIndex >= codeArea.getParagraphs().size()) {
        return;
    }

    String line = codeArea.getParagraph(paragraphIndex).getText();

    // 1. 라인의 시작 부분에서 들여쓰기 영역을 추출합니다.
    Matcher matcher = LEADING_WHITESPACE.matcher(line);
    if (matcher.find()) {
        String indentText = matcher.group();
        if (indentText.isEmpty()) return; // 들여쓰기가 없으면 종료

        // 2. 들여쓰기를 분석하여 최적의 탭 조합으로 변환합니다.
        //    (이전 답변의 로직과 동일)
        StringBuilder optimalIndent = new StringBuilder();
        int spaceCount = 0;
        for (char c : indentText.toCharArray()) {
            if (c == '\t') {
                optimalIndent.append("\t".repeat(spaceCount / tabSize));
                optimalIndent.append(" ".repeat(spaceCount % tabSize));
                optimalIndent.append('\t');
                spaceCount = 0;
            } else if (c == ' ') {
                spaceCount++;
            }
        }
        optimalIndent.append("\t".repeat(spaceCount / tabSize));
        optimalIndent.append(" ".repeat(spaceCount % tabSize));

        String newIndent = optimalIndent.toString();

        // 3. 변환된 들여쓰기가 기존과 다를 경우에만 교체합니다.
        if (!indentText.equals(newIndent)) {
            int paragraphStart = codeArea.getAbsolutePosition(paragraphIndex, 0);
            codeArea.replaceText(paragraphStart, paragraphStart + indentText.length(), newIndent);
        }
    }
}

    private void handleKeyTyped(KeyEvent e) {
        String typedChar = e.getCharacter();
        if (typedChar.isEmpty() || Character.isISOControl(typedChar.charAt(0))) {
            return;
        }

        if (" ".equals(typedChar)) {
            handleSpaceToTabConversion(e);
            if (e.isConsumed()) {
                return;
            }
        }

        handleAutoPairing(e);
        if (e.isConsumed()) {
            return;
        }

        if ("}".equals(typedChar)) {
            autoFormatBlock();
        }

        if (completionService != null && Character.isJavaIdentifierPart(typedChar.charAt(0))) {
            suggestionsHiddenManually = false;
            enhancer.showSuggestionsAsync();
        } else if (enhancer.isPopupShowing()) {
            enhancer.hideSuggestions();
        }
    }

    private void handleSpaceToTabConversion(KeyEvent e) {
        // Platform.runLater를 사용하여 스페이스가 삽입된 후에 실행
        Platform.runLater(() -> {
            // 새로 만든 최적화 메소드를 현재 라인에 대해 호출하기만 하면 됩니다.
            optimizeIndent(codeArea.getCurrentParagraph());
        });
    }
    
    private void handleAutoPairing(KeyEvent e) {
        String typedChar = e.getCharacter();
        
        if (isClosingChar(typedChar)) {
            int caretPosition = codeArea.getCaretPosition();
            if (caretPosition < codeArea.getLength()) {
                String charAfter = codeArea.getText(caretPosition, caretPosition + 1);
                if (typedChar.equals(charAfter)) {
                    codeArea.moveTo(caretPosition + 1);
                    e.consume();
                    return;
                }
            }
        }

        String closingChar = getClosingChar(typedChar);
        if (closingChar != null) {
            if (enhancer.isPopupShowing()) {
                enhancer.hideSuggestions();
            }
            int caretPosition = codeArea.getCaretPosition();
            codeArea.insertText(caretPosition, closingChar);
            codeArea.moveTo(caretPosition);
        }
    }

    private void autoFormatBlock() {
        int closingBracePos = codeArea.getCaretPosition() - 1;
        if (closingBracePos < 0) return;

        int openingBracePos = findMatchingOpeningBrace(closingBracePos);
        if (openingBracePos == -1) return;

        int startLine = codeArea.offsetToPosition(openingBracePos, Bias.Forward).getMajor();
        int endLine = codeArea.offsetToPosition(closingBracePos, Bias.Forward).getMajor();

        String startLineText = codeArea.getParagraph(startLine).getText();
        Matcher matcher = LEADING_WHITESPACE.matcher(startLineText);
        String baseIndent = matcher.find() ? matcher.group() : "";

        StringBuilder formattedText = new StringBuilder();
        int indentLevel = countTabs(baseIndent);

        for (int i = startLine; i <= endLine; i++) {
            String line = codeArea.getParagraph(i).getText().trim();

            if (line.startsWith("}")) {
                indentLevel--;
            }

            if (indentLevel < 0) indentLevel = 0; 
            
            for (int j = 0; j < indentLevel; j++) {
                formattedText.append('\t');
            }
            formattedText.append(line);

            if (line.endsWith("{")) {
                indentLevel++;
            }
            
            if (i < endLine) {
                formattedText.append('\n');
            }
        }

        int replaceStart = codeArea.getAbsolutePosition(startLine, 0);
        int replaceEnd = codeArea.getAbsolutePosition(endLine, codeArea.getParagraph(endLine).length());
        
        codeArea.replaceText(replaceStart, replaceEnd, formattedText.toString());
    }

    private int findMatchingOpeningBrace(int closingBracePos) {
        if (closingBracePos <= 0) return -1;
        String text = codeArea.getText(0, closingBracePos);
        int braceCounter = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}') {
                braceCounter++;
            } else if (c == '{') {
                braceCounter--;
                if (braceCounter < 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int countTabs(String s) {
        int count = 0;
        for (char c : s.toCharArray()) {
            if (c == '\t') {
                count++;
            }
        }
        return count;
    }

    private void handleKeyPressed(KeyEvent e) {
        if (enhancer.isPopupShowing()) {
            handlePopupKeyPress(e);
        } else {
            handleNormalKeyPress(e);
        }
    }

    private void handleNormalKeyPress(KeyEvent e) {
        if (e.getCode() == KeyCode.TAB) {
            IndexRange selection = codeArea.getSelection();
            if (selection.getLength() > 0) {
                handleBlockIndent(e.isShiftDown(), selection);
            } else {
                if (!e.isShiftDown()) {
                    codeArea.insertText(codeArea.getCaretPosition(), "\t");
                }
            }
            e.consume();
        } else if (e.getCode() == KeyCode.ENTER) {
            handleAutoIndent(e);
        } else if (e.getCode() == KeyCode.BACK_SPACE) {
            handlePairBackspace(e);
            if (!e.isConsumed()) {
                handleTabBackspace(e);
            }
        } else if (e.getCode() == KeyCode.HOME) {
            int currentParagraph = codeArea.getCurrentParagraph();
            String currentLine = codeArea.getParagraph(currentParagraph).getText();
            Matcher matcher = LEADING_WHITESPACE.matcher(currentLine);
            int firstCharIndex = 0;
            if (matcher.find()) {
                firstCharIndex = matcher.end();
            }

            int targetColumn = (codeArea.getCaretColumn() != firstCharIndex) ? firstCharIndex : 0;

            if (e.isShiftDown()) {
                int caretPosition = codeArea.getCaretPosition();
                int targetPosition = codeArea.getAbsolutePosition(currentParagraph, targetColumn);
                codeArea.moveTo(targetPosition);
                codeArea.selectRange(targetPosition, caretPosition);
            } else {
                codeArea.moveTo(currentParagraph, targetColumn);
            }
            e.consume();
        }
    }

    private void handlePairBackspace(KeyEvent e) {
        if (codeArea.getSelection().getLength() > 0) {
            return;
        }

        int caretPosition = codeArea.getCaretPosition();
        if (caretPosition > 0 && caretPosition < codeArea.getLength()) {
            String charBefore = codeArea.getText(caretPosition - 1, caretPosition);
            String charAfter = codeArea.getText(caretPosition, caretPosition + 1);

            String expectedClosing = getClosingChar(charBefore);
            if (expectedClosing != null && expectedClosing.equals(charAfter)) {
                codeArea.deleteText(caretPosition - 1, caretPosition + 1);
                e.consume();
            }
        }
    }

    private void handleBlockIndent(boolean unIndent, IndexRange selection) {
        int start = selection.getStart();
        int end = selection.getEnd();

        Position startPos = codeArea.offsetToPosition(start, Bias.Forward);
        Position endPos = codeArea.offsetToPosition(end, Bias.Forward);

        int startLine = startPos.getMajor();
        int endLine = endPos.getMajor();

        if (endPos.getMinor() == 0 && endLine > startLine) {
            endLine--;
        }

        StringBuilder newText = new StringBuilder();
        int totalLengthChange = 0;
        int firstLineLengthChange = 0;

        for (int i = startLine; i <= endLine; i++) {
            String line = codeArea.getParagraph(i).getText();
            if (unIndent) {
                if (line.startsWith("\t")) {
                    newText.append(line.substring(1));
                    if (i == startLine) firstLineLengthChange = -1;
                    totalLengthChange--;
                } else {
                    newText.append(line);
                }
            } else {
                newText.append("\t").append(line);
                if (i == startLine) firstLineLengthChange = 1;
                totalLengthChange++;
            }
            if (i < endLine) {
                newText.append("\n");
            }
        }

        int replaceStart = codeArea.getAbsolutePosition(startLine, 0);
        int replaceEnd = codeArea.getAbsolutePosition(endLine, codeArea.getParagraph(endLine).length());
        
        codeArea.replaceText(replaceStart, replaceEnd, newText.toString());

        int newStart = start + firstLineLengthChange;
        int newEnd = end + totalLengthChange;
        codeArea.selectRange(newStart, newEnd);
    }

    private void handlePopupKeyPress(KeyEvent e) {
        switch (e.getCode()) {
            case UP:
                enhancer.selectPreviousSuggestion();
                e.consume();
                break;
            case DOWN:
                enhancer.selectNextSuggestion();
                e.consume();
                break;
            case ENTER:
            case TAB:
                enhancer.commitSelectedSuggestion();
                e.consume();
                break;
            case ESCAPE:
                suggestionsHiddenManually = true;
                enhancer.hideSuggestions();
                e.consume();
                break;
            default:
                break;
        }
    }

    private void handleTabBackspace(KeyEvent e) {
        int caretPosition = codeArea.getCaretPosition();
        if (caretPosition > 0 && codeArea.getText(caretPosition - 1, caretPosition).equals("\t")) {
            codeArea.deleteText(caretPosition - 1, caretPosition);
            e.consume();
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
            } else {
                codeArea.replaceSelection("\n" + indent);
            }
        }
        e.consume();
    }

    private String getOpeningChar(String closingChar) {
        return PAIR_MAP.entrySet().stream()
                .filter(entry -> entry.getValue().equals(closingChar))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private String getClosingChar(String openingChar) {
        return PAIR_MAP.get(openingChar);
    }

    private boolean isClosingChar(String s) {
        return PAIR_MAP.containsValue(s);
    }
}
