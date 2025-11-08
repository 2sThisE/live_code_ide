package com.ethis2s.view;

import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import java.util.List;

public class ProblemsView {

    // '문제'의 재정의: 어느 파일에서 온 어떤 에러인지 기억하는 새로운 영혼
    public static class Problem {
        public final String fileName;
        public final SyntaxError error;

        public Problem(String fileName, SyntaxError error) {
            this.fileName = fileName;
            this.error = error;
        }
    }

    private final ListView<Problem> problemsList;

    public ProblemsView() {
        problemsList = new ListView<>();
        problemsList.setPlaceholder(new Label("No problems detected."));
        
        // 에러 메시지를 '파일 이름'과 함께 보여주도록 수정
        problemsList.setCellFactory(lv -> new ListCell<>() {
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
                        problem.error.message));
                }
            }
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
