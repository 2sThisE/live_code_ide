package com.ethis2s.util;

import com.ethis2s.service.CompletionService;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.fxmisc.richtext.model.TwoDimensional.Position;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeArea의 모든 키 입력을 중앙에서 관리하고, 그에 따른 액션(자동 완성, 자동 들여쓰기 등)을 결정하는 클래스.
 * EditorEnhancer의 UI 제어 메소드를 호출하는 역할을 한다.
 */
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
    private final EditorEnhancer enhancer; // UI 제어는 Enhancer에게 위임
    private final CompletionService completionService;

    private boolean suggestionsHiddenManually = false;

    public EditorInputManager(CodeArea codeArea, EditorEnhancer enhancer, CompletionService completionService) {
        this.codeArea = codeArea;
        this.enhancer = enhancer;
        this.completionService = completionService;
    }

    public void registerEventHandlers() {
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, this::handleAutoPairingFilter);
        codeArea.addEventHandler(KeyEvent.KEY_TYPED, this::handleKeyTyped);
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
    }

    /**
     * [필터] 괄호/따옴표 입력을 가로채서 처리하는 메소드.
     * 문자가 화면에 표시되기 전에 실행된다.
     */
    private void handleAutoPairingFilter(KeyEvent e) {
        String typedChar = e.getCharacter();

        if (isClosingChar(typedChar)) {
            int caretPosition = codeArea.getCaretPosition();
            if (caretPosition > 0 && caretPosition < codeArea.getLength()) {
                String charBefore = codeArea.getText(caretPosition - 1, caretPosition);
                String charAfter = codeArea.getText(caretPosition, caretPosition + 1);
                String expectedOpening = getOpeningChar(typedChar);

                if (expectedOpening != null && expectedOpening.equals(charBefore) && typedChar.equals(charAfter)) {
                    codeArea.moveTo(caretPosition + 1);
                    e.consume();
                }
            }
        }
    }

    /**
     * [핸들러] 필터를 통과한 문자 입력을 처리하는 메소드.
     */
    private void handleKeyTyped(KeyEvent e) {
        String typedChar = e.getCharacter();
        if (typedChar.isEmpty() || Character.isISOControl(typedChar.charAt(0))) {
            return;
        }

        // 여는 문자를 입력했을 때, 닫는 문자를 자동으로 추가
        String closingChar = getClosingChar(typedChar);
        if (closingChar != null) {
            if (enhancer.isPopupShowing()) {
                enhancer.hideSuggestions();
            }
            int caretPosition = codeArea.getCaretPosition();
            codeArea.insertText(caretPosition, closingChar);
            codeArea.moveTo(caretPosition); // 커서를 다시 원래 위치로 이동
        }

        // 자동 완성 제안 로직
        if (completionService != null && Character.isJavaIdentifierPart(typedChar.charAt(0))) {
            suggestionsHiddenManually = false;
            enhancer.showSuggestionsAsync();
        } else if (enhancer.isPopupShowing()) {
            enhancer.hideSuggestions();
        }
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
