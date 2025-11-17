package com.ethis2s.service;

import com.ethis2s.view.editor.EditorStateManager;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import org.fxmisc.richtext.CodeArea;

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
        if (cursorInfo.position > codeArea.getLength()) {
            cursorInfo.node.setVisible(false);
            return;
        }
        Optional<Bounds> boundsOpt = codeArea.getCharacterBoundsOnScreen(cursorInfo.position, cursorInfo.position);

        if (boundsOpt.isPresent()) {
            Bounds bounds = boundsOpt.get();
            Bounds overlayBounds = overlayPane.localToScreen(overlayPane.getBoundsInLocal());

            // Calculate position relative to the overlay pane
            double x = bounds.getMinX() - overlayBounds.getMinX();
            double y = bounds.getMinY() - overlayBounds.getMinY();
            


            cursorInfo.node.relocate(x, y);
            cursorInfo.node.setVisible(true);
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
