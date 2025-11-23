package com.ethis2s.service;

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
        
        // Add listeners to redraw cursors when the view scrolls or changes
        codeArea.estimatedScrollXProperty().addListener((obs, old, n) -> updateAllCursorPositions());
        codeArea.estimatedScrollYProperty().addListener((obs, old, n) -> updateAllCursorPositions());
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

        Label nameLabel = new Label(nickname);
        nameLabel.getStyleClass().add("remote-cursor-label");
        nameLabel.setStyle("-fx-background-color: " + toCss(userColor) + ";");
        
        Line cursorLine = new Line(0, 0, 0, 15); // Height of the cursor line
        cursorLine.getStyleClass().add("remote-cursor-line");
        cursorLine.setStroke(userColor);

        VBox cursorNode = new VBox(nameLabel, cursorLine);
        cursorNode.setSpacing(-1); // Make label and line overlap slightly
        cursorNode.setVisible(false); // Initially invisible

        overlayPane.getChildren().add(cursorNode);
        activeCursors.put(userId, new UserCursorInfo(nickname, userColor, cursorNode));
    }

    private void updateCursorPosition(UserCursorInfo cursorInfo) {
        // 1. 전체 범위 방어
        if (cursorInfo.position < 0 || cursorInfo.position > codeArea.getLength()) {
            cursorInfo.node.setVisible(false);
            return;
        }

        // ★★★ [대가리 디버깅 해결의 핵심] ★★★
        // 좌표 계산은 믿을 수 없으므로, '줄 번호(Paragraph Index)'로 1차 검문을 실시합니다.
        
        // 1) 커서가 위치한 줄 번호 구하기
        int cursorLine = codeArea.offsetToPosition(cursorInfo.position, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        
        // 2) 현재 화면에 보이는 첫 번째 줄과 마지막 줄 번호 구하기
        // (RichTextFX의 first/lastVisibleParToAllParIndex 사용)
        // 만약 뷰가 초기화 안 됐으면 -1을 리턴할 수 있으므로 방어
        int firstVisibleLine = -1;
        int lastVisibleLine = -1;
        try {
            firstVisibleLine = codeArea.firstVisibleParToAllParIndex();
            lastVisibleLine = codeArea.lastVisibleParToAllParIndex();
        } catch (Exception e) {
            // 뷰가 아직 준비 안 됨 -> 숨김
            cursorInfo.node.setVisible(false);
            return;
        }

        // 3) 검문: 커서가 보이는 줄 범위 안에 있는가?
        // (여유 있게 위아래로 1줄 정도는 봐줍니다)
        if (cursorLine < firstVisibleLine - 1 || cursorLine > lastVisibleLine + 1) {
            // 범위 밖이면 좌표 계산이고 뭐고 다 필요 없음. 무조건 숨김!
            cursorInfo.node.setVisible(false);
            return; 
        }

        // ---------------------------------------------------------------
        // 2차 검문: 좌표 기반 (화면 경계선 처리용)
        // 위에서 대충 걸러냈으니, 이제 정밀하게 좌표를 계산해도 렉이 안 걸림
        // ---------------------------------------------------------------
        Optional<Bounds> boundsOpt = codeArea.getCharacterBoundsOnScreen(cursorInfo.position, cursorInfo.position);

        if (boundsOpt.isPresent()) {
            Bounds charScreenBounds = boundsOpt.get();
            
            // 오버레이 패널 기준 로컬 좌표로 변환 (가벼운 연산)
            Point2D localPoint = overlayPane.screenToLocal(charScreenBounds.getMinX(), charScreenBounds.getMinY());

            // null 체크 및 Y좌표 범위 정밀 체크
            if (localPoint != null && 
                localPoint.getY() >= -20 && // 글자 높이 고려 
                localPoint.getY() < overlayPane.getHeight() + 20) {
                
                cursorInfo.node.relocate(localPoint.getX(), localPoint.getY());
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
