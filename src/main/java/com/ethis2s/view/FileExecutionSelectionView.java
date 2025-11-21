package com.ethis2s.view;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

public class FileExecutionSelectionView {

    private AnchorPane view;
    private TextField searchField;
    private TextField argumentsField; // 실행 인자를 위한 텍스트 필드
    private Button executeButton; // 실행 버튼
    private TableView<FileExecutionInfo> tableView;
    private ObservableList<FileExecutionInfo> fileList = FXCollections.observableArrayList();
    private FilteredList<FileExecutionInfo> filteredData;

    public FileExecutionSelectionView() {
        initialize();
    }

    private void initialize() {
        view = new AnchorPane();
        view.getStyleClass().add("file-execution-selection-view");
        view.setVisible(false); // 기본적으로 숨김
        view.setMaxWidth(600);
        view.setMaxHeight(400);
        view.setPrefSize(600, 400);
        view.setPadding(new Insets(15));


        searchField = new TextField();
        searchField.setPromptText("실행할 파일 검색...");

        // --- 실행 인자 필드와 실행 버튼 ---
        argumentsField = new TextField();
        argumentsField.setPromptText("실행 인자 (선택 사항)");
        HBox.setHgrow(argumentsField, Priority.ALWAYS);

        executeButton = new Button("실행");
        executeButton.getStyleClass().add("execute-button");

        HBox executionControls = new HBox(10, argumentsField, executeButton);
        // --- ---

        tableView = new TableView<>();
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 1. 체크박스 컬럼
        TableColumn<FileExecutionInfo, Boolean> checkBoxColumn = new TableColumn<>();
        checkBoxColumn.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        checkBoxColumn.setCellFactory(CheckBoxTableCell.forTableColumn(checkBoxColumn));
        checkBoxColumn.setEditable(true);
        checkBoxColumn.setPrefWidth(40);
        checkBoxColumn.setResizable(false);

        // 2. 파일 이름 컬럼
        TableColumn<FileExecutionInfo, String> fileNameColumn = new TableColumn<>("파일 이름");
        fileNameColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        fileNameColumn.setPrefWidth(180);

        // 3. 파일 경로 컬럼
        TableColumn<FileExecutionInfo, String> filePathColumn = new TableColumn<>("경로");
        filePathColumn.setCellValueFactory(new PropertyValueFactory<>("filePath"));
        filePathColumn.setPrefWidth(350);

        tableView.getColumns().addAll(checkBoxColumn, fileNameColumn, filePathColumn);
        tableView.setEditable(true);

        // 필터링 설정
        filteredData = new FilteredList<>(fileList, p -> true);
        tableView.setItems(filteredData);

        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filteredData.setPredicate(fileInfo -> {
                if (newValue == null || newValue.isEmpty()) {
                    return true;
                }
                String lowerCaseFilter = newValue.toLowerCase();
                if (fileInfo.getFileName().toLowerCase().contains(lowerCaseFilter)) {
                    return true;
                } else if (fileInfo.getFilePath().toLowerCase().contains(lowerCaseFilter)) {
                    return true;
                }
                return false;
            });
        });


        AnchorPane.setTopAnchor(searchField, 0.0);
        AnchorPane.setLeftAnchor(searchField, 0.0);
        AnchorPane.setRightAnchor(searchField, 0.0);

        AnchorPane.setTopAnchor(tableView, 40.0);
        AnchorPane.setLeftAnchor(tableView, 0.0);
        AnchorPane.setRightAnchor(tableView, 0.0);
        AnchorPane.setBottomAnchor(tableView, 40.0);

        AnchorPane.setBottomAnchor(executionControls, 0.0);
        AnchorPane.setLeftAnchor(executionControls, 0.0);
        AnchorPane.setRightAnchor(executionControls, 0.0);

        view.getChildren().addAll(searchField, tableView, executionControls);
    }

    public AnchorPane getView() {
        return view;
    }

    public void updateFileList(List<FileExecutionInfo> files) {
        Platform.runLater(() -> {
            fileList.setAll(files);
        });
    }

    public void setVisible(boolean visible) {
        view.setVisible(visible);
        if (visible) {
            searchField.requestFocus();
        }
    }

    public boolean isVisible() {
        return view.isVisible();
    }

    public void setOnExecute(Consumer<List<FileExecutionInfo>> callback) {
        executeButton.setOnAction(event -> {
            List<FileExecutionInfo> selectedFiles = fileList.stream()
                    .filter(FileExecutionInfo::isSelected)
                    .toList();
            callback.accept(selectedFiles);
        });
    }

    public String getArguments() {
        return argumentsField.getText();
    }


    // TableView에 데이터를 표현하기 위한 모델 클래스
    public static class FileExecutionInfo {
        private final SimpleBooleanProperty selected;
        private final SimpleStringProperty fileName;
        private final SimpleStringProperty filePath;
        private final String tabId;

        public FileExecutionInfo(boolean selected, String fileName, String filePath, String tabId) {
            this.selected = new SimpleBooleanProperty(selected);
            this.fileName = new SimpleStringProperty(fileName);
            this.filePath = new SimpleStringProperty(filePath);
            this.tabId = tabId;
        }

        public boolean isSelected() { return selected.get(); }
        public SimpleBooleanProperty selectedProperty() { return selected; }
        public String getFileName() { return fileName.get(); }
        public SimpleStringProperty fileNameProperty() { return fileName; }
        public String getFilePath() { return filePath.get(); }
        public SimpleStringProperty filePathProperty() { return filePath; }
        public String getTabId() { return tabId; }
    }
}
