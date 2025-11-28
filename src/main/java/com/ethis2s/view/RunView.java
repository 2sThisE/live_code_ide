package com.ethis2s.view;

import com.ethis2s.model.LogType;
import com.ethis2s.service.ExecutionService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import org.fxmisc.richtext.StyleClassedTextArea; // RichTextFX 임포트

import java.util.function.Consumer;

public class RunView {

    // [변경] TextArea -> StyleClassedTextArea (색상 혼합 지원)
    private final StyleClassedTextArea consoleArea;
    private final ExecutionService executionService = new ExecutionService();
    private Consumer<String> onInputSubmitted;

    private int lastOutputEndIndex = 0;

    public RunView() {
        // [변경] RichTextFX 컴포넌트 생성 (편집 가능)
        consoleArea = new StyleClassedTextArea();
        consoleArea.getStyleClass().add("run-console-area");
        
        // 자동 줄바꿈 끄기 (터미널 느낌)
        consoleArea.setWrapText(false); 
        consoleArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            // 1. 허용된 키는 통과 (화살표, 복사, 엔터 등)
            if (event.getCode() == KeyCode.COPY || event.isShortcutDown()) { 
                // Ctrl+C 등은 허용하지만, Ctrl+V(붙여넣기)나 Ctrl+X(잘라내기)는 검사 필요
                if (event.getCode() == KeyCode.V || event.getCode() == KeyCode.X) {
                    if (isTouchingReadOnlyZone()) {
                        event.consume(); // 히스토리 영역 건드리면 차단
                    }
                }
                return;
            }
            
            // 2. 백스페이스(지우기) 처리
            if (event.getCode() == KeyCode.BACK_SPACE) {
                // 커서가 보호구역 경계선에 있거나, 보호구역 안쪽에 있으면 -> 삭제 금지!
                if (consoleArea.getCaretPosition() <= lastOutputEndIndex) {
                    event.consume(); // 이벤트를 먹어버림 (아무 일도 안 일어남)
                }
                // 드래그해서 지우려고 할 때 보호구역이 포함되어 있으면 -> 삭제 금지!
                else if (consoleArea.getSelection().getStart() < lastOutputEndIndex) {
                    consoleArea.deselect(); // 선택 풀고
                    consoleArea.moveTo(consoleArea.getLength()); // 맨 뒤로 보냄
                    event.consume();
                }
                return;
            }
            
            // 3. Delete 키 처리 (커서 뒤 글자 삭제)
            if (event.getCode() == KeyCode.DELETE) {
                if (consoleArea.getCaretPosition() < lastOutputEndIndex) {
                    event.consume();
                }
                return;
            }
            

            // 4. 일반 입력 (글자 타이핑)
            // 사용자가 위쪽(과거 출력)을 클릭하고 타이핑을 시도하면?
            if (isTypingKey(event)) {
                if (consoleArea.getCaretPosition() < lastOutputEndIndex) {
                    // 방법 A: 입력을 막는다. (event.consume())
                    // 방법 B: 커서를 자동으로 맨 뒤로 옮겨주고 입력하게 한다. (터미널 스타일)
                    consoleArea.moveTo(consoleArea.getLength()); // 커서를 맨 끝으로 강제 이동
                }
            }
        });

        consoleArea.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                // 엔터 처리 로직 (RichTextFX API에 맞게 조정)
                int totalLength = consoleArea.getLength();
                
                if (totalLength >= lastOutputEndIndex) {
                    // 텍스트 추출
                    String userInput = consoleArea.getText(lastOutputEndIndex, totalLength);
                    
                    if (onInputSubmitted != null) {
                        onInputSubmitted.accept(userInput);
                    }
                    
                    Platform.runLater(() -> {
                        lastOutputEndIndex = consoleArea.getLength();
                    });
                }
            }
        });
    }
    private boolean isTouchingReadOnlyZone() {
        // 선택 영역이 있는 경우
        if (consoleArea.getSelection().getLength() > 0) {
            return consoleArea.getSelection().getStart() < lastOutputEndIndex;
        }
        // 그냥 커서만 있는 경우
        return consoleArea.getCaretPosition() < lastOutputEndIndex;
    }

    // [헬퍼 메서드 2] 단순 입력 키인지 확인 (글자, 숫자, 기호 등)
    private boolean isTypingKey(javafx.scene.input.KeyEvent event) {
        return !event.getCode().isNavigationKey() 
            && !event.getCode().isFunctionKey()
            && !event.getCode().isModifierKey()
            && !event.getCode().isMediaKey()
            && event.getCode() != KeyCode.ENTER // 엔터는 별도 처리
            && event.getText().length() > 0;
    }

    public void executeProcess(String command, String workingDir) {
        clear();
        appendStyledText(">> Executing: " + command + "\n", "system-msg");
        
        this.onInputSubmitted = input -> executionService.sendInputToProcess(input);

        // [핵심 변경] 하나의 콜백에서 타입을 보고 분기 처리
        executionService.execute(
            command,
            workingDir,
            (type, text) -> {
                if (type == LogType.STDOUT) {
                    appendOutput(text); // 흰색
                } else {
                    // 에러일 때는 줄바꿈 보정 로직 적용
                    appendError(text);  // 빨간색
                }
            }
        );
    }

    public Node getView() { return consoleArea; }

    public void clear() {
        Platform.runLater(() -> {
            consoleArea.clear();
            lastOutputEndIndex = 0;
        });
    }

    // [표준 출력] 흰색 (기본)
    public void appendOutput(String text) {
        Platform.runLater(() -> {
            // RichTextFX의 append 메서드: (텍스트, 스타일클래스)
            consoleArea.append(text, "standard-out");
            scrollToBottom();
        });
    }

    // [표준 에러] 빨간색
    public void appendError(String text) {
        Platform.runLater(() -> {
            // 1. 현재 콘솔에 내용이 있는지 확인
            int currentLength = consoleArea.getLength();
            
            if (currentLength > 0) {
                // 2. 마지막 글자가 줄바꿈(\n)인지 확인
                // (전체 텍스트를 가져오면 느리므로 마지막 1글자만 가져옵니다)
                String lastChar = consoleArea.getText(currentLength - 1, currentLength);
                
                if (!lastChar.equals("\n")) {
                    // 줄바꿈이 없으면 강제로 하나 넣어줍니다. (스타일은 표준 출력용으로)
                    consoleArea.append("\n", "standard-out");
                }
            }

            // 3. 에러 메시지 출력 (빨간색)
            consoleArea.append(text, "error-out");
            
            // 4. 스크롤 이동 및 보호구역 갱신
            scrollToBottom();
        });
    }
    
    // 내부 헬퍼: 텍스트 추가 및 스타일 지정
    private void appendStyledText(String text, String styleClass) {
        Platform.runLater(() -> {
            consoleArea.append(text, styleClass);
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        consoleArea.moveTo(consoleArea.getLength()); // 커서를 맨 뒤로
        consoleArea.requestFollowCaret(); // 화면 이동
        lastOutputEndIndex = consoleArea.getLength();
    }

    /**
     * 애플리케이션 종료 등으로 콘솔을 정리할 때 호출.
     * 실행 중인 외부 프로세스를 강제로 종료하고 관련 쓰레드를 정리합니다.
     */
    public void shutdown() {
        executionService.stopCurrentProcess();
    }

    public void stopProcess() {
        executionService.stopCurrentProcess();
        appendStyledText("\n>> Stopped by user.\n", "system-msg");
    }
}
