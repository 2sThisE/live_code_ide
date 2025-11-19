package com.ethis2s.service;

import com.ethis2s.model.Operation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.IndexRange;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.fxmisc.richtext.model.TwoDimensional.Position;

import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.EditorEnhancer;
import com.ethis2s.util.HybridManager;

import java.util.List;
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
    private final HybridManager manager;
    private final PauseTransition lineLockDebouncer;
    private final InputInterpreter interpreter;

    private final StringBuilder currentWord = new StringBuilder();
    private boolean suggestionsHiddenManually = false;
    private int lastCaretLine = -1;
    private ChangeInitiator lastInitiator = ChangeInitiator.USER;
    private boolean isProcessingServerChange = false;
    private boolean isTyping = false;

    public EditorInputManager(CodeArea codeArea, EditorEnhancer enhancer, CompletionService completionService, HybridManager manager) {
        this.codeArea = codeArea;
        this.enhancer = enhancer;
        this.completionService = completionService;
        this.manager = manager;
        this.interpreter = new InputInterpreter(manager, codeArea);

        this.lineLockDebouncer = new PauseTransition(Duration.millis(500));
        this.lineLockDebouncer.setOnFinished(event -> {
            int currentLine = codeArea.getCurrentParagraph();
            if (currentLine != lastCaretLine) {
                 manager.requestLineLock(currentLine + 1);
                 lastCaretLine = currentLine;
            }
        });
    }

    public void resetInitiatorToUser() {
        this.lastInitiator = ChangeInitiator.USER;
    }

    public ChangeInitiator getLastInitiator() {
        return this.lastInitiator;
    }

    public void controlledReplaceText(int start, int end, String text, ChangeInitiator initiator) {
        if (initiator == ChangeInitiator.SERVER) {
            isProcessingServerChange = true;

            // 1. Store original caret position.
            int originalCaretPosition = codeArea.getCaretPosition();
            
            // 2. Calculate the final caret position.
            int finalCaretPosition = originalCaretPosition;
            int lengthDiff = text.length() - (end - start);

            // If the change occurred entirely before our caret, adjust our caret position.
            if (end <= originalCaretPosition) {
                finalCaretPosition += lengthDiff;
            }
            // Note: The server rule prevents edits on the same line, so we don't need to handle
            // cases where the change engulfs the caret.

            // 3. Apply the text change.
            this.lastInitiator = initiator;
            codeArea.replaceText(start, end, text);

            // 4. Restore the caret to its correctly transformed position.
            codeArea.moveTo(finalCaretPosition);

        } else { // For USER or SYSTEM initiators
            this.lastInitiator = initiator;
            codeArea.replaceText(start, end, text);
        }
    }

    public void registerEventHandlers() {
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, this::handleKeyTyped);
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);

        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            int currentParagraph = codeArea.getCurrentParagraph();
            int startParagraph = Math.max(0, currentParagraph - 50);
            int endParagraph = Math.min(codeArea.getParagraphs().size() - 1, currentParagraph + 50);

            Platform.runLater(() -> {
                for (int i = startParagraph; i <= endParagraph; i++) {
                    optimizeIndent(i);
                }
            });
        });

        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            if(lastInitiator == ChangeInitiator.USER && !isProcessingServerChange){
                codeArea.requestFollowCaret();
                if (!isTyping) manager.cursorMoveRequest(newPos); //파일 연산에 의한 캐럿움직임은 전달하지 않음
                
                // 디바운싱
                int currentLine = codeArea.getCurrentParagraph();
                if (currentLine != lastCaretLine) {
                    lineLockDebouncer.stop();
                    lineLockDebouncer.play();
                }
            }
        });

        codeArea.plainTextChanges().subscribe(change -> {
            isTyping = true;
            ChangeInitiator initiator = this.lastInitiator;
            if (initiator == ChangeInitiator.USER) {
                if (manager.isLineLockedByOther(codeArea.getCurrentParagraph())) {
                    
                    return;
                }

                Platform.runLater(() -> {
                    if (interpreter != null) {
                        List<Operation> ops = interpreter.interpret(change);
                        for (Operation op : ops) {
                            if (op != null) {
                                manager.getOtManager().sendOperation(op);
                            }
                        }
                    }
                });
            }
            Platform.runLater(() -> {
                this.lastInitiator = ChangeInitiator.USER;
                isTyping = false; // ★★★ 텍스트 변경 끝! ★★★
            });

            if (isProcessingServerChange) {
                Platform.runLater(() -> isProcessingServerChange = false);
            }
            
            // Reset the initiator in the next UI cycle to allow other listeners to process the original state.
            Platform.runLater(() -> this.lastInitiator = ChangeInitiator.USER);
        });
    }

    
    private void updateWordBoxAndSuggest() {
        if (completionService == null) return;

        int caretPosition = codeArea.getCaretPosition();
        String text = codeArea.getText();
        
        int start = caretPosition - 1;
        while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) {
            start--;
        }
        start++; // 단어의 시작점으로 이동

        currentWord.setLength(0);
        if (caretPosition > start) {
            currentWord.append(text, start, caretPosition);
        }

        if (currentWord.length() > 0) {
            if (!suggestionsHiddenManually) {
                enhancer.showSuggestionsAsync();
            }
        } else {
            suggestionsHiddenManually = false; // 단어가 끝나면 수동 숨김 상태 리셋
            enhancer.hideSuggestions();
        }
    }
    
    public void optimizeIndent(int paragraphIndex) {

        int tabSize = ConfigManager.getInstance().get("editor","tabSize",Integer.class,4);
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
                controlledReplaceText(paragraphStart, paragraphStart + indentText.length(), newIndent, ChangeInitiator.SYSTEM);
            }
        }
    }

    private void handleKeyTyped(KeyEvent e) {
        // Hybrid Debouncing: If a key is typed while the debouncer is waiting,
        // cancel the wait and send the lock request immediately.
        if (lineLockDebouncer.getStatus() == PauseTransition.Status.RUNNING) {
            lineLockDebouncer.stop();
            int currentLine = codeArea.getCurrentParagraph();
            manager.requestLineLock(currentLine + 1);
            lastCaretLine = currentLine;
        }

        if (manager.isLineLockedByOther(codeArea.getCurrentParagraph())) {
            e.consume();
            return;
        }
        String typedChar = e.getCharacter();
        if (typedChar.isEmpty() || Character.isISOControl(typedChar.charAt(0))) {
            // 제어 문자가 입력되면 자동 완성을 시도하지 않고 바로 종료합니다.
            return;
        }

        // 자동 완성 로직을 다른 기능보다 먼저, 그리고 즉시 호출합니다.
        // Platform.runLater를 사용하여 UI 변경이 완료된 후에 실행되도록 합니다.
        Platform.runLater(this::updateWordBoxAndSuggest);

        if (" ".equals(typedChar)) {
            handleSpaceToTabConversion(e);
            if (e.isConsumed()) return;
        }

        handleAutoPairing(e);
        if (e.isConsumed()) return;

        if ("}".equals(typedChar)) {
            Platform.runLater(this::autoFormatBlock);
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
            String insertedText = typedChar + closingChar;

            // Fire a USER change to insert the pair and notify the server.
            controlledReplaceText(caretPosition, caretPosition, insertedText, ChangeInitiator.USER);
            
            // The caret will be moved by the CodeArea's reaction to the text change.
            // We just need to place it in the middle of the pair.
            Platform.runLater(() -> codeArea.moveTo(caretPosition + 1));
            
            e.consume(); // Consume the event as we handled it manually
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
        int indentLevel = calculateIndentLevel(baseIndent);

        for (int i = startLine; i <= endLine; i++) {
            String line = codeArea.getParagraph(i).getText();
            String trimmedLine = line.trim();
            // ---- 핵심 수정 로직 ----
            // 닫는 중괄호로 "시작"하거나 "끝나는" 줄은 들여쓰기 레벨을 먼저 감소시킨다.
            // 단, 한 줄에 여닫는 괄호가 모두 있는 경우는 제외한다 (예: if (c) { return; })
            if (trimmedLine.startsWith("}") || (trimmedLine.endsWith("}") && !trimmedLine.contains("{"))) {
                indentLevel--;
                if (indentLevel < 0) indentLevel = 0;
            }
            
            // 계산된 레벨로 현재 줄의 들여쓰기를 적용한다.
            for (int j = 0; j < indentLevel; j++) {
                formattedText.append('\t');
            }
            formattedText.append(trimmedLine);

            // 여는 중괄호로 "끝나는" 줄은, 다음 줄을 위해 들여쓰기 레벨을 증가시킨다.
            if (trimmedLine.endsWith("{")) indentLevel++;
            
            if (i < endLine) formattedText.append('\n');
        }

        int replaceStart = codeArea.getAbsolutePosition(startLine, 0);
        int replaceEnd = codeArea.getAbsolutePosition(endLine, codeArea.getParagraph(endLine).length());
        String originalText = codeArea.getText(replaceStart, replaceEnd);
        String newText = formattedText.toString();
        
        controlledReplaceText(replaceStart, replaceEnd, newText, ChangeInitiator.USER);
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

    private int calculateIndentLevel(String indentText) {
        int tabSize = ConfigManager.getInstance().get("editor","tabSize",Integer.class,4);
        if (tabSize <= 1) {
            tabSize = 4; // 혹시 설정이 잘못되어도 괜찮아요. 제가 기본값으로 지켜드릴게요!
        }

        int level = 0;
        int spaceCount = 0;
        for (char c : indentText.toCharArray()) {
            if (c == '\t') {
                // 탭은 언제나 한 레벨이죠!
                level++;
                spaceCount = 0; // 탭을 만나면 스페이스는 리셋!
            } else if (c == ' ') {
                spaceCount++;
                // 스페이스가 모여 탭 크기만큼 되면, 그것도 한 레벨로 인정해줘야죠!
                if (spaceCount >= tabSize) {
                    level++;
                    spaceCount = 0; // 레벨이 올랐으니 스페이스는 다시 0부터!
                }
            }
        }
        return level;
    }

    private void handleKeyPressed(KeyEvent e) {
        // Hybrid Debouncing: Same logic as handleKeyTyped for special keys.
        if (lineLockDebouncer.getStatus() == PauseTransition.Status.RUNNING) {
            lineLockDebouncer.stop();
            int currentLine = codeArea.getCurrentParagraph();
            manager.requestLineLock(currentLine + 1);
            lastCaretLine = currentLine;
        }

        if (manager.isLineLockedByOther(codeArea.getCurrentParagraph())) {
            // Allow navigation keys (arrows, home, end, page up/down)
            if (e.getCode().isNavigationKey()) {
                // Potentially allow selection with shift, but block modifications
                if (e.isShiftDown()) {
                    // This is complex; for now, let's just allow movement.
                    // A more advanced implementation might allow selection but prevent typing/deleting.
                }
            } else {
                e.consume();
                return;
            }
        }

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
            } else if (!e.isShiftDown()) {
                int caretPosition = codeArea.getCaretPosition();
                boolean handled = false;

                // '괄호 탈출' 로직
                if (caretPosition > 0 && caretPosition < codeArea.getLength()) {
                    String charBefore = codeArea.getText(caretPosition - 1, caretPosition);
                    String charAfter = codeArea.getText(caretPosition, caretPosition + 1);
                    String expectedClosing = getClosingChar(charBefore);

                    if (expectedClosing != null && expectedClosing.equals(charAfter) && !"'\"".contains(charBefore)) {
                        codeArea.moveTo(caretPosition + 1);
                        handled = true;
                    }
                }

                // '괄호 탈출'이 아니면 기본 탭 삽입
                if (!handled) {
                    controlledReplaceText(caretPosition, caretPosition, "\t", ChangeInitiator.USER);
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
            Platform.runLater(() -> this.updateWordBoxAndSuggest());
        } else if (e.getCode() == KeyCode.DELETE) {
            Platform.runLater(() -> this.updateWordBoxAndSuggest());
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
                controlledReplaceText(caretPosition - 1, caretPosition + 1, "", ChangeInitiator.USER);
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
        String originalText = codeArea.getText(replaceStart, replaceEnd);
        String newFormattedText = newText.toString();

        controlledReplaceText(replaceStart, replaceEnd, newFormattedText, ChangeInitiator.USER);

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
                // 팝업이 처리하지 않는 키는 일반 키 입력으로 넘김
                handleNormalKeyPress(e);
                break;
        }
    }

    private void handleTabBackspace(KeyEvent e) {
        int caretPosition = codeArea.getCaretPosition();
        if (caretPosition > 0 && codeArea.getText(caretPosition - 1, caretPosition).equals("\t")) {
            controlledReplaceText(caretPosition - 1, caretPosition, "", ChangeInitiator.USER);
            e.consume();
        }
    }

    private void handleAutoIndent(KeyEvent e) {
        int caretPosition = codeArea.getCaretPosition();
        int currentParagraph = codeArea.getCurrentParagraph();
        String currentLine = codeArea.getParagraph(currentParagraph).getText();

        Matcher matcher = LEADING_WHITESPACE.matcher(currentLine);
        String indent = matcher.find() ? matcher.group() : "";

        String finalInsert;

        boolean isBetweenBraces = caretPosition > 0 &&
                                  caretPosition < codeArea.getLength() &&
                                  codeArea.getText(caretPosition - 1, caretPosition).equals("{") &&
                                  codeArea.getText(caretPosition, caretPosition + 1).equals("}");

        if (isBetweenBraces) {
            String extraIndent = "\t";
            finalInsert = "\n" + indent + extraIndent + "\n" + indent;
            
            // Let the USER change handle the text insertion
            controlledReplaceText(caretPosition, caretPosition, finalInsert, ChangeInitiator.USER);

            // Position the caret correctly after the change
            int finalCaretPosition = caretPosition + 1 + indent.length() + extraIndent.length();
            Platform.runLater(() -> codeArea.moveTo(finalCaretPosition));

        } else {
            String textBeforeCaret = currentLine.substring(0, codeArea.getCaretColumn());
            boolean endsWithOpeningBrace = textBeforeCaret.trim().endsWith("{");

            finalInsert = "\n" + indent;
            if (endsWithOpeningBrace) {
                finalInsert += "\t";
            }
            
            controlledReplaceText(codeArea.getSelection().getStart(), codeArea.getSelection().getEnd(), finalInsert, ChangeInitiator.USER);
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
