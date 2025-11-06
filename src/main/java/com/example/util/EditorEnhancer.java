package com.example.util;

import org.fxmisc.richtext.CodeArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EditorEnhancer {

    private static final Pattern LEADING_WHITESPACE = Pattern.compile("^\\s*");
    private final CodeArea codeArea;

    private EditorEnhancer(CodeArea codeArea) {
        this.codeArea = codeArea;
    }

    /**
     * CodeArea에 자동 완성 및 자동 들여쓰기 기능을 활성화합니다.
     * @param codeArea 기능을 적용할 CodeArea 객체
     */
    public static void enableAutoEnhancements(CodeArea codeArea) {
        // 디버깅 체크 1: 이 메서드가 호출되는지 확인
        System.out.println("EditorEnhancer: Enhancements enabled.");
        EditorEnhancer enhancer = new EditorEnhancer(codeArea);
        enhancer.enableAutoPairing();
        enhancer.enableAutoIndent();
    }

    /**
     * 괄호, 따옴표 자동 쌍 완성 기능을 활성화합니다.
     */
    private void enableAutoPairing() {
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String typedChar = e.getCharacter();
            if (typedChar.isEmpty() || typedChar.length() > 1) {
                return;
            }

            int caretPosition = codeArea.getCaretPosition();
            String closingChar = getClosingChar(typedChar);

            if (closingChar != null) {
                codeArea.insertText(caretPosition, typedChar + closingChar);
                codeArea.moveTo(caretPosition + 1);
                e.consume();
                return;
            }

            if (isClosingChar(typedChar)) {
                if (caretPosition < codeArea.getLength()) {
                    String nextChar = codeArea.getText(caretPosition, caretPosition + 1);
                    if (typedChar.equals(nextChar)) {
                        codeArea.moveTo(caretPosition + 1);
                        e.consume();
                    }
                }
            }
        });
    }

    /**
     * 엔터 키 입력 시 자동 들여쓰기 기능을 활성화합니다.
     * 이제 짝이 맞는 중괄호가 이미 존재하는지 스캔하여 중복 생성을 방지합니다.
     */
    private void enableAutoIndent() {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                int caretPosition = codeArea.getCaretPosition();
                int currentParagraph = codeArea.getCurrentParagraph();
                String currentLine = codeArea.getParagraph(currentParagraph).getText();

                Matcher matcher = LEADING_WHITESPACE.matcher(currentLine);
                String indent = matcher.find() ? matcher.group() : "";
                String extraIndent = "\t";

                // 조건 1: 커서가 자동 완성된 '{}' 사이에 있는지 확인 (가장 우선)
                boolean isBetweenBraces = caretPosition > 0 &&
                                          caretPosition < codeArea.getLength() &&
                                          codeArea.getText(caretPosition - 1, caretPosition).equals("{") &&
                                          codeArea.getText(caretPosition, caretPosition + 1).equals("}");

                if (isBetweenBraces) {
                    StringBuilder toInsert = new StringBuilder()
                            .append("\n")
                            .append(indent)
                            .append(extraIndent)
                            .append("\n")
                            .append(indent);

                    codeArea.insertText(caretPosition, toInsert.toString());
                    codeArea.moveTo(caretPosition + 1 + indent.length() + extraIndent.length());

                }
                // 조건 2: 현재 줄이 '{'로 끝나고, 아직 짝이 맞는 '}'가 없는 경우
                else if (currentLine.trim().endsWith("{")) {
                    // --- 추가된 로직: 여기서부터 커서 뒤의 텍스트를 스캔 ---
                    boolean hasMatchingBrace = false;
                    int braceCount = 1; // 현재 줄의 '{'를 포함하여 카운트 시작
                    String remainingText = codeArea.getText(caretPosition, codeArea.getLength());

                    for (char c : remainingText.toCharArray()) {
                        if (c == '{') {
                            braceCount++;
                        } else if (c == '}') {
                            braceCount--;
                        }
                        if (braceCount == 0) {
                            hasMatchingBrace = true; // 짝이 맞는 닫는 괄호를 찾음
                            break;
                        }
                    }
                    // --- 스캔 로직 끝 ---

                    if (hasMatchingBrace) {
                        // 이미 짝이 되는 '}'가 존재하므로, '}'를 추가하지 않고 들여쓰기된 새 줄만 삽입
                        codeArea.insertText(caretPosition, "\n" + indent + extraIndent);
                    } else {
                        // 짝이 되는 '}'가 없으므로, 완전한 새 블록을 생성
                        StringBuilder toInsert = new StringBuilder()
                                .append("\n")
                                .append(indent)
                                .append(extraIndent)
                                .append("\n")
                                .append(indent)
                                .append("}");

                        codeArea.insertText(caretPosition, toInsert.toString());
                        codeArea.moveTo(caretPosition + 1 + indent.length() + extraIndent.length());
                    }
                } else {
                    // 기본 동작: 현재 들여쓰기를 유지한 채 줄바꿈
                    codeArea.insertText(caretPosition, "\n" + indent);
                }

                e.consume(); // 기본 엔터 동작 방지
            }
        });
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