package com.ethis2s.view;

import javafx.scene.Node;
import javafx.scene.control.TextArea;

public class OutputView {

    private final TextArea outputArea;

    public OutputView() {
        outputArea = new TextArea();
        outputArea.getStyleClass().add("text-area");
        outputArea.setEditable(false);
        outputArea.setText("Output will be shown here...");
    }
    
    public Node getView() {
        return outputArea;
    }

    public void clear() {
        outputArea.clear();
    }

    public void appendText(String text) {
        outputArea.appendText(text);
    }
}
