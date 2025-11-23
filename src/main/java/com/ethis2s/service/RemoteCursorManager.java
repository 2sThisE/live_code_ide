package com.ethis2s.service;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import org.fxmisc.richtext.CodeArea;

import com.ethis2s.util.EditorStateManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RemoteCursorManager {

    private final CodeArea codeArea;
    private final Pane overlayPane;
    private final EditorStateManager stateManager;

    private static class UserCursorInfo {
        int position;
        String nickname;
        Color color;
        Node node;

        UserCursorInfo(String nickname, Color color, Node node) {
            this.nickname = nickname;
            this.color = color;
            this.node = node;
        }
    }

    private final Map<String, UserCursorInfo> activeCursors = new HashMap<>();

    public RemoteCursorManager(CodeArea codeArea, Pane overlayPane, EditorStateManager stateManager) {
        this.codeArea = codeArea;
        this.overlayPane = overlayPane;
        this.stateManager = stateManager;
        
        codeArea.estimatedScrollXProperty().addListener((obs, old, n) -> 
            Platform.runLater(this::updateAllCursorPositions));
            
        codeArea.estimatedScrollYProperty().addListener((obs, old, n) -> 
            Platform.runLater(this::updateAllCursorPositions));

        // [추가 권장] 창 크기가 바뀌거나(Resize) 레이아웃이 변할 때도 위치를 다시 잡아야 함
        codeArea.widthProperty().addListener((obs, old, n) -> 
            Platform.runLater(this::updateAllCursorPositions));
        codeArea.heightProperty().addListener((obs, old, n) -> 
            Platform.runLater(this::updateAllCursorPositions));
        // TODO: Add more listeners if needed (e.g., for zoom)
    }

    public void updateCursor(String userId, String nickname, int position) {
        if (!activeCursors.containsKey(userId)) {
            createCursorForUser(userId, nickname);
        }
        UserCursorInfo cursorInfo = activeCursors.get(userId);
        cursorInfo.position = position;

        updateCursorPosition(cursorInfo);
    }

    private void createCursorForUser(String userId, String nickname) {
        Color userColor = generateColorFromUserId(userId);

        // 1. 이름 라벨 (Header)
        Label nameLabel = new Label(nickname);
        nameLabel.getStyleClass().add("remote-cursor-label");
        nameLabel.setStyle("-fx-background-color: " + toCss(userColor) + ";");
        
        // ★ 핵심 1: 라벨을 자신의 높이만큼 위로 들어 올립니다.
        // (높이가 변해도 자동으로 반영되도록 바인딩)
        nameLabel.translateYProperty().bind(nameLabel.heightProperty().negate());

        // 2. 커서 라인 (Caret)
        Line cursorLine = new Line(0, 0, 0, 15); // 높이 15 (폰트 크기에 맞춰 조절 가능)
        cursorLine.getStyleClass().add("remote-cursor-line");
        cursorLine.setStroke(userColor);
        cursorLine.setStrokeWidth(2); // 두께를 살짝 줘서 잘 보이게 함

        // 3. VBox 대신 Group 사용
        // Group은 자식들의 좌표(0,0)를 기준으로 겹쳐서 그립니다.
        // cursorLine은 (0,0)에서 시작하고, nameLabel은 위로(-height) 올라갑니다.
        javafx.scene.Group cursorNode = new javafx.scene.Group(cursorLine, nameLabel);
        
        cursorNode.setVisible(false);
        
        // (중요) 마우스 이벤트를 무시하게 해서 클릭 방해 금지
        cursorNode.setMouseTransparent(true); 

        overlayPane.getChildren().add(cursorNode);
        activeCursors.put(userId, new UserCursorInfo(nickname, userColor, cursorNode));
    }

    private void updateCursorPosition(UserCursorInfo cursorInfo) {
        // 1. 문서 전체 범위 체크
        if (cursorInfo.position < 0 || cursorInfo.position > codeArea.getLength()) {
            cursorInfo.node.setVisible(false);
            return;
        }

        // 2. [엄격해진 1차 관문] 줄 번호 기반 검사
        // 아까는 (first - 1)이었지만, 이제는 여유 없이 정확히 (first)부터 검사합니다.
        // RichTextFX의 firstVisiblePar...는 "조금이라도 보이는 줄"을 반환하므로,
        // 여기에 포함되지 않으면 아예 안 보이는 게 맞습니다.
        int cursorLine = codeArea.offsetToPosition(cursorInfo.position, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        int firstVisibleLine = -1;
        int lastVisibleLine = -1;
        try {
            firstVisibleLine = codeArea.firstVisibleParToAllParIndex();
            lastVisibleLine = codeArea.lastVisibleParToAllParIndex();
        } catch (Exception e) {
            cursorInfo.node.setVisible(false);
            return;
        }

        // ★ [수정] 여유분(-1, +1) 삭제! 보이는 줄이 아니면 칼같이 숨김
        if (cursorLine < firstVisibleLine || cursorLine > lastVisibleLine) {
            cursorInfo.node.setVisible(false);
            return; 
        }

        // 3. [엄격해진 2차 관문] 좌표 기반 검사
        Optional<Bounds> boundsOpt = codeArea.getCharacterBoundsOnScreen(cursorInfo.position, cursorInfo.position);

        if (boundsOpt.isPresent()) {
            Bounds charScreenBounds = boundsOpt.get();
            javafx.geometry.Point2D localPoint = overlayPane.screenToLocal(charScreenBounds.getMinX(), charScreenBounds.getMinY());

            // ★ [수정] Y좌표 검사도 엄격하게 변경
            // 기존: -50 (여유 많음) -> 변경: -5 (거의 여유 없음)
            // -5 정도는 주는 이유: 커서 선 두께나 미세한 오차로 깜빡이는 걸 방지하기 위함입니다.
            // 하지만 -20, -50 처럼 크게 주면 아까처럼 윗줄에 걸쳐서 렉이 걸립니다.
            if (localPoint != null && 
                localPoint.getY() >= -5 && 
                localPoint.getY() < overlayPane.getHeight() + 5) {
                
                cursorInfo.node.setLayoutX(localPoint.getX());
                cursorInfo.node.setLayoutY(localPoint.getY());

                // 높이 조정 (기존 로직 유지)
                double lineHeight = charScreenBounds.getHeight();
                if (cursorInfo.node instanceof javafx.scene.Group) {
                    javafx.scene.Group group = (javafx.scene.Group) cursorInfo.node;
                    if (!group.getChildren().isEmpty() && group.getChildren().get(0) instanceof javafx.scene.shape.Line) {
                        ((javafx.scene.shape.Line) group.getChildren().get(0)).setEndY(lineHeight);
                    }
                }

                cursorInfo.node.setVisible(true);
                
            } else {
                cursorInfo.node.setVisible(false);
            }
        } else {
            cursorInfo.node.setVisible(false);
        }
    }

    public void updateAllCursorPositions() {
        for (UserCursorInfo cursorInfo : activeCursors.values()) {
            updateCursorPosition(cursorInfo);
        }
    }

    private Color generateColorFromUserId(String userId) {
        // Simple hash-based color generation
        int hash = userId.hashCode();
        int r = (hash & 0xFF0000) >> 16;
        int g = (hash & 0x00FF00) >> 8;
        int b = hash & 0x0000FF;
        return Color.rgb(r, g, b);
    }

    private String toCss(Color color) {
        return String.format("rgba(%d, %d, %d, %.2f)",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255),
                color.getOpacity());
    }
}
