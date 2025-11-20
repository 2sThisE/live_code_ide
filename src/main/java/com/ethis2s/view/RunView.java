package com.ethis2s.view;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import java.util.function.Consumer;

/**
 * 사용자가 실행한 프로그램의 표준 입/출력/에러를 표시하고,
 * 사용자로부터 입력을 받는 대화형 콘솔 뷰입니다.
 */
public class RunView {

    private final TextArea consoleArea;
    
    // [추가] 사용자가 엔터 키를 눌렀을 때, 입력된 라인을 전달할 콜백
    private Consumer<String> onInputSubmitted;

    public RunView() {
        consoleArea = new TextArea();
        consoleArea.getStyleClass().add("run-console-area"); // CSS 스타일링을 위해 다른 클래스 이름 사용
        consoleArea.setEditable(true); // 사용자가 입력할 수 있도록 설정
        consoleArea.setPromptText("Program output will be shown here...");
        
        // [추가] 엔터 키 이벤트를 감지하여 입력 처리
        consoleArea.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                // 핸들러가 설정되어 있을 경우에만 입력 처리
                if (onInputSubmitted != null) {
                    // 현재 커서 위치를 기준으로 마지막 라인을 찾아 입력으로 간주
                    String text = consoleArea.getText();
                    int caretPosition = consoleArea.getCaretPosition();
                    int lastNewLine = text.substring(0, caretPosition).lastIndexOf('\n');
                    if (lastNewLine == -1) {
                        lastNewLine = 0; // 첫 줄인 경우
                    } else {
                        lastNewLine += 1; // 줄바꿈 문자 다음부터
                    }
                    
                    // 실제 입력 내용은 마지막 줄바꿈 이후부터 현재 커서까지
                    // (주의: 사용자가 중간에서 엔터를 칠 수도 있으므로 이 로직은 개선의 여지가 있음)
                    // 가장 단순한 방법: 마지막 라인 전체를 입력으로 간주
                    lastNewLine = text.lastIndexOf('\n');
                    String lastLine = text.substring(lastNewLine + 1);

                    onInputSubmitted.accept(lastLine);
                }
                // TextArea 자체에 줄바꿈이 입력되도록 event.consume()을 하지 않습니다.
                // 사용자가 입력한 내용도 화면에 보여야 하기 때문입니다.
            }
        });
    }

    /**
     * 이 뷰의 JavaFX 노드를 반환합니다.
     * @return TextArea 노드
     */
    public Node getView() {
        return consoleArea;
    }

    /**
     * 콘솔 내용을 모두 지웁니다.
     */
    public void clear() {
        Platform.runLater(() -> consoleArea.clear());
    }

    /**
     * 프로그램의 표준 출력(stdout)을 콘솔에 추가합니다.
     * @param text 추가할 텍스트
     */
    public void appendOutput(String text) {
        Platform.runLater(() -> {
            consoleArea.appendText(text);
            // 자동으로 스크롤을 맨 아래로 내립니다.
            consoleArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * 프로그램의 표준 에러(stderr)를 콘솔에 추가합니다.
     * (향후 별도의 스타일을 적용할 수 있도록 메서드를 분리)
     * @param text 추가할 에러 텍스트
     */
    public void appendError(String text) {
        Platform.runLater(() -> {
            // 간단하게는 그냥 추가하지만, 나중에는 TextFlow 등을 사용해 색상을 입힐 수 있습니다.
            consoleArea.appendText("[ERROR] " + text);
            consoleArea.setScrollTop(Double.MAX_VALUE);
        });
    }
    
    /**
     * [추가] 사용자가 엔터 키를 눌렀을 때 호출될 이벤트 핸들러를 등록합니다.
     * @param onInputSubmitted 입력된 라인(String)을 받는 Consumer
     */
    public void setOnInputSubmitted(Consumer<String> onInputSubmitted) {
        this.onInputSubmitted = onInputSubmitted;
    }
}