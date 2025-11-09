package com.ethis2s.view;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TextArea;

public class DebugView {

    private final TextArea debugArea;

    public DebugView() {
        debugArea = new TextArea();
        debugArea.setEditable(false);
        debugArea.setPromptText("Debug logs will be shown here...");
    }

    public Node getView() {
        return debugArea;
    }

    public void clear() {
        Platform.runLater(() -> debugArea.clear());
    }

    public void appendText(String text) {
        Platform.runLater(() -> debugArea.appendText(text));
    }
}
