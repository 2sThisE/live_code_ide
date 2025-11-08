package com.ethis2s.util; // 패키지 이름은 그대로 유지하세요.

import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

public class ReSizeHelper {

    public static void addResizeListener(Stage stage) {
        ResizeListener resizeListener = new ResizeListener(stage);
        
        // CHANGED: addEventHandler -> addEventFilter
        // EventFilter를 사용하면 자식 노드가 이벤트를 소비하기 전에 먼저 처리할 수 있습니다.
        Scene scene = stage.getScene();
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, resizeListener);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, resizeListener);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, resizeListener);
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, resizeListener);
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, resizeListener);
        
        // REMOVED: addListenerDeeply
        // Scene 레벨에서 EventFilter를 사용하므로, 더 이상 모든 자식 노드에 리스너를
        // 재귀적으로 추가할 필요가 없습니다. 코드가 훨씬 깔끔하고 효율적으로 변합니다.
    }

    // REMOVED: addListenerDeeply 메소드 자체가 필요 없어졌습니다.

    static class ResizeListener implements EventHandler<MouseEvent> {
        private Stage stage;
        private Cursor cursorEvent = Cursor.DEFAULT;
        private int border = 6; // 리사이즈 감지 영역 두께
        private double startX = 0;
        private double startY = 0;
        
        // 드래그 시작 시점의 스테이지 위치/크기 저장을 위한 변수
        private double stageX = 0;
        private double stageY = 0;
        private double stageWidth = 0;
        private double stageHeight = 0;


        public ResizeListener(Stage stage) {
            this.stage = stage;
        }

        @Override
        public void handle(MouseEvent mouseEvent) {
            EventType<? extends MouseEvent> mouseEventType = mouseEvent.getEventType();
            Scene scene = stage.getScene();

            double mouseEventX = mouseEvent.getSceneX();
            double mouseEventY = mouseEvent.getSceneY();
            double sceneWidth = scene.getWidth();
            double sceneHeight = scene.getHeight();

            if (mouseEventType == MouseEvent.MOUSE_MOVED) {
                if (mouseEventX < border && mouseEventY < border) {
                    cursorEvent = Cursor.NW_RESIZE;
                } else if (mouseEventX < border && mouseEventY > sceneHeight - border) {
                    cursorEvent = Cursor.SW_RESIZE;
                } else if (mouseEventX > sceneWidth - border && mouseEventY < border) {
                    cursorEvent = Cursor.NE_RESIZE;
                } else if (mouseEventX > sceneWidth - border && mouseEventY > sceneHeight - border) {
                    cursorEvent = Cursor.SE_RESIZE;
                } else if (mouseEventX < border) {
                    cursorEvent = Cursor.W_RESIZE;
                } else if (mouseEventX > sceneWidth - border) {
                    cursorEvent = Cursor.E_RESIZE;
                } else if (mouseEventY < border) {
                    cursorEvent = Cursor.N_RESIZE;
                } else if (mouseEventY > sceneHeight - border) {
                    cursorEvent = Cursor.S_RESIZE;
                } else {
                    cursorEvent = Cursor.DEFAULT;
                }
                scene.setCursor(cursorEvent);
            } else if (mouseEventType == MouseEvent.MOUSE_EXITED || mouseEventType == MouseEvent.MOUSE_EXITED_TARGET) {
                scene.setCursor(Cursor.DEFAULT);
            } else if (mouseEventType == MouseEvent.MOUSE_PRESSED) {
                // 드래그 시작 시점의 마우스 위치와 스테이지 정보를 저장합니다.
                startX = mouseEvent.getScreenX();
                startY = mouseEvent.getScreenY();
                stageX = stage.getX();
                stageY = stage.getY();
                stageWidth = stage.getWidth();
                stageHeight = stage.getHeight();
            } else if (mouseEventType == MouseEvent.MOUSE_DRAGGED) {
                if (cursorEvent != Cursor.DEFAULT) {
                    // CHANGED: 이벤트 소비(consume) 로직 추가
                    // 리사이징이 시작되면 이벤트를 소비하여 스크롤바 등 다른 컨트롤이
                    // 이벤트를 받지 못하게 막습니다.
                    mouseEvent.consume();

                    double newWidth = stageWidth;
                    double newHeight = stageHeight;

                    // 오른쪽, 아래쪽 크기 조절
                    if (cursorEvent == Cursor.E_RESIZE || cursorEvent == Cursor.SE_RESIZE || cursorEvent == Cursor.NE_RESIZE) {
                        newWidth = stageWidth + (mouseEvent.getScreenX() - startX);
                    }
                    if (cursorEvent == Cursor.S_RESIZE || cursorEvent == Cursor.SE_RESIZE || cursorEvent == Cursor.SW_RESIZE) {
                        newHeight = stageHeight + (mouseEvent.getScreenY() - startY);
                    }
                    
                    // 왼쪽, 위쪽 크기 조절 (위치 이동 포함)
                    if (cursorEvent == Cursor.W_RESIZE || cursorEvent == Cursor.SW_RESIZE || cursorEvent == Cursor.NW_RESIZE) {
                        double newX = stageX + (mouseEvent.getScreenX() - startX);
                        newWidth = stageWidth - (mouseEvent.getScreenX() - startX);
                        if (newWidth >= stage.getMinWidth()) {
                            stage.setX(newX);
                            stage.setWidth(newWidth);
                        }
                    }
                    if (cursorEvent == Cursor.N_RESIZE || cursorEvent == Cursor.NE_RESIZE || cursorEvent == Cursor.NW_RESIZE) {
                        double newY = stageY + (mouseEvent.getScreenY() - startY);
                        newHeight = stageHeight - (mouseEvent.getScreenY() - startY);
                         if (newHeight >= stage.getMinHeight()) {
                            stage.setY(newY);
                            stage.setHeight(newHeight);
                        }
                    } else { // 오른쪽, 아래쪽 크기 조절 시에는 위치 이동 없음
                         if (newWidth >= stage.getMinWidth()) stage.setWidth(newWidth);
                         if (newHeight >= stage.getMinHeight()) stage.setHeight(newHeight);
                    }
                }
            }
        }
    }
}