package com.ethis2s.view;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProblemsView {

    // --- 기존 Problem 클래스는 그대로 유지 ---
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

    // --- 데이터 모델 정의 ---
    private static class TreeData { }
    private static class TotalErrorsNode extends TreeData {
        final String title;
        final int totalErrorCount;
        TotalErrorsNode(String title, int totalErrorCount) {
            this.title = title;
            this.totalErrorCount = totalErrorCount;
        }
    }
    private static class FileNode extends TreeData {
        final String fileName;
        final int errorCount;
        FileNode(String fileName, int errorCount) {
            this.fileName = fileName;
            this.errorCount = errorCount;
        }
    }
    private static class ErrorNode extends TreeData {
        final Problem problem;
        ErrorNode(Problem problem) { this.problem = problem; }
    }

    // --- UI 컴포넌트 ---
    private final TreeView<TreeData> problemsTree;
    private final StackPane container;

    public ProblemsView(MainController mainController) {
        problemsTree = new TreeView<>();
        problemsTree.setShowRoot(false); // 루트는 보이지 않게 설정
        problemsTree.setRoot(new TreeItem<>()); // 비어있는 숨겨진 루트 아이템 설정

        Label placeholder = new Label("No problems detected.");
        placeholder.visibleProperty().bind(Bindings.isEmpty(problemsTree.getRoot().getChildren()));

        container = new StackPane(problemsTree, placeholder);
        
        problemsTree.setCellFactory(tv -> {
            TreeCell<TreeData> cell = new TreeCell<>() {
                @Override
                protected void updateItem(TreeData item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                    } else {
                        if (item instanceof TotalErrorsNode totalErrorsNode) {
                            // 총 에러 개수 노드 렌더링
                            Label title = new Label(totalErrorsNode.title);
                            title.setStyle("-fx-font-weight: bold;");
                            Label count = new Label(" (" + totalErrorsNode.totalErrorCount + ")");
                            count.setStyle("-fx-text-fill: #999999;");
                            setGraphic(new HBox(title, count));
                        }
                        else if (item instanceof FileNode fileNode) {
                            // 파일 노드 렌더링
                            Label fileName = new Label(fileNode.fileName);
                            fileName.setStyle("-fx-font-weight: bold;");
                            Label errorCount = new Label(" (" + fileNode.errorCount + ")");
                            errorCount.setStyle("-fx-text-fill: #999999;");
                            setGraphic(new HBox(fileName, errorCount));
                        } else if (item instanceof ErrorNode errorNode) {
                            // 에러 메시지 노드 렌더링
                            Label message = new Label(String.format("Line %d: %s",
                                errorNode.problem.error.line,
                                errorNode.problem.truncatedMessage));
                            setGraphic(new HBox(message));
                        }
                    }
                }
            };
            
            cell.getStyleClass().add("problems-tree-cell");

            cell.setOnMouseClicked(event -> {
                if (!cell.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    TreeItem<TreeData> treeItem = cell.getTreeItem();
                    if (treeItem != null && treeItem.getValue() instanceof ErrorNode errorNode) {
                        mainController.navigateToError(errorNode.problem);
                    }
                }
            });
            return cell;
        });
    }

    public Node getView() {
        return container;
    }

    public void updateProblems(List<Problem> problems) {
        TreeItem<TreeData> root = problemsTree.getRoot();
        root.getChildren().clear();

        if (problems != null && !problems.isEmpty()) {
            // 1. 총 에러 개수 노드 추가
            int totalProblems = problems.size();
            TreeItem<TreeData> totalErrorsItem = new TreeItem<>(new TotalErrorsNode("Total Errors", totalProblems));
            root.getChildren().add(totalErrorsItem);

            // 2. 파일별 에러 노드 추가
            Map<String, List<Problem>> groupedByFile = problems.stream()
                .collect(Collectors.groupingBy(p -> p.filePath));

            for (Map.Entry<String, List<Problem>> entry : groupedByFile.entrySet()) {
                List<Problem> fileProblems = entry.getValue();
                Problem firstProblem = fileProblems.get(0);

                FileNode fileNodeData = new FileNode(firstProblem.fileName, fileProblems.size());
                TreeItem<TreeData> fileItem = new TreeItem<>(fileNodeData);
                fileItem.setExpanded(true);

                for (Problem problem : fileProblems) {
                    ErrorNode errorNodeData = new ErrorNode(problem);
                    fileItem.getChildren().add(new TreeItem<>(errorNodeData));
                }
                root.getChildren().add(fileItem);
            }
        }
    }
}