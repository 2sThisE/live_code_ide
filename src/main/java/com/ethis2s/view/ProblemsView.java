package com.ethis2s.view;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;

import java.util.List;

public class ProblemsView {

    public static class Problem {
        public final String filePath;
        public final String fileName;
        public final SyntaxError error;
        public final String truncatedMessage;

        public Problem(String filePath, String fileName, SyntaxError error) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.error = error;
            
            String originalMessage = error.message;
            int expectingIndex = originalMessage.indexOf("expecting");
            if (expectingIndex != -1) {
                this.truncatedMessage = originalMessage.substring(0, expectingIndex).trim();
            } else {
                this.truncatedMessage = originalMessage;
            }
        }
    }

    private final ListView<Problem> problemsList;

    public ProblemsView(MainController mainController) {
        problemsList = new ListView<>();
        problemsList.setPlaceholder(new Label("No problems detected."));
        
        problemsList.setCellFactory(lv -> {
            ListCell<Problem> cell = new ListCell<>() {
                @Override
                protected void updateItem(Problem problem, boolean empty) {
                    super.updateItem(problem, empty);
                    if (empty || problem == null) {
                        setText(null);
                    } else {
                        setText(String.format("%s [%d:%d] %s", 
                            problem.fileName, 
                            problem.error.line, 
                            problem.error.charPositionInLine, 
                            problem.truncatedMessage));
                    }
                }
            };
            cell.getStyleClass().add("problems-list-cell");

            cell.setOnMouseClicked(event -> {
                if (!cell.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    Problem problem = cell.getItem();
                    mainController.navigateToError(problem);
                }
            });
            return cell;
        });
    }

    public Node getView() {
        return problemsList;
    }

    public void updateProblems(List<Problem> problems) {
        if (problems == null || problems.isEmpty()) {
            problemsList.getItems().clear();
        } else {
            problemsList.setItems(FXCollections.observableArrayList(problems));
        }
    }
}