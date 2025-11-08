package com.ethis2s.view;

import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import java.util.List;

public class ProblemsView {

    private final ListView<SyntaxError> problemsList;

    public ProblemsView() {
        problemsList = new ListView<>();
        problemsList.setPlaceholder(new Label("No problems detected."));
        
        // 에러 메시지를 어떻게 보여줄지 미리 정의
        problemsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SyntaxError error, boolean empty) {
                super.updateItem(error, empty);
                if (empty || error == null) {
                    setText(null);
                } else {
                    setText(String.format("[%d:%d] %s", error.line, error.charPositionInLine, error.message));
                }
            }
        });
    }

    public Node getView() {
        return problemsList;
    }

    public void updateErrors(List<SyntaxError> errors) {
        if (errors == null || errors.isEmpty()) {
            problemsList.getItems().clear();
        } else {
            problemsList.setItems(FXCollections.observableArrayList(errors));
        }
    }
}
