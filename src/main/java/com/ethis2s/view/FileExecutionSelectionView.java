package com.ethis2s.view;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.UserProjectsInfo;
import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class FileExecutionSelectionView {

    private VBox view;
    private TextField searchField;
    private TextField argumentsField;
    private Button executeButton;
    private TableView<FileExecutionInfo> tableView;
    private final ObservableList<FileExecutionInfo> fileList = FXCollections.observableArrayList();
    private FilteredList<FileExecutionInfo> filteredData;
    private final SimpleDoubleProperty measuredCellHeight = new SimpleDoubleProperty(52.0);
    private final Map<String, MFXProgressSpinner> spinners = new HashMap<>();
    private UserProjectsInfo userProjectsInfo;
    private final ProjectController projectController;

    public FileExecutionSelectionView(ProjectController projectController) {
        this.projectController = projectController;
        initialize();
    }

    public void bindWidthTo(ReadOnlyDoubleProperty parentSearchBoxWidthProperty) {
        final double EXTRA_PADDING = 20.0;
        view.prefWidthProperty().bind(parentSearchBoxWidthProperty.add(EXTRA_PADDING));
    }

    private void initialize() {
        view = new VBox(10);
        view.getStyleClass().add("file-execution-selection-view");
        view.setVisible(false);
        view.setPadding(new Insets(15));

        searchField = new TextField();
        searchField.setPromptText("실행할 파일 검색...");
        searchField.getStyleClass().add("file-search-field");

        argumentsField = new TextField();
        argumentsField.setPromptText("실행 인자 (선택 사항)");
        HBox.setHgrow(argumentsField, Priority.ALWAYS);

        executeButton = new Button("실행");
        executeButton.getStyleClass().add("execute-button");

        HBox executionControls = new HBox(10, argumentsField, executeButton);

        tableView = new TableView<>();
        tableView.getStyleClass().add("file-execution-table");

        TableColumn<FileExecutionInfo, Boolean> checkBoxColumn = new TableColumn<>();
        checkBoxColumn.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        checkBoxColumn.setCellFactory(CheckBoxTableCell.forTableColumn(checkBoxColumn));
        checkBoxColumn.setEditable(true);
        checkBoxColumn.setPrefWidth(40);
        checkBoxColumn.setResizable(false);

        TableColumn<FileExecutionInfo, Void> fileInfoColumn = new TableColumn<>("File");
        fileInfoColumn.setCellFactory(column -> new TableCell<>() {
            private final Label icon = new Label("\uD83D\uDCC4");
            private final Label fileName = new Label();
            private final Label filePath = new Label();
            private final HBox fileDetails = new HBox();
            private final HBox cellGraphic = new HBox(10);
            private final Region spacer = new Region();
            private final StackPane statusContainer = new StackPane();

            {
                icon.getStyleClass().add("file-icon");
                fileName.getStyleClass().add("file-name-label");
                filePath.getStyleClass().add("file-path-label");
                fileName.setMinWidth(Region.USE_PREF_SIZE);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                fileDetails.getChildren().addAll(fileName, spacer, filePath);
                fileDetails.setAlignment(Pos.BASELINE_LEFT);
                cellGraphic.getChildren().addAll(icon, fileDetails, statusContainer);
                cellGraphic.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(fileDetails, Priority.ALWAYS);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    FileExecutionInfo fileInfo = getTableRow().getItem();
                    fileName.setText(fileInfo.getFileName());
                    filePath.setText(fileInfo.getFilePath());

                    Label checkLabel = new Label("\u2714");
                    checkLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #2ECC71;");
                    MFXProgressSpinner spinner = spinners.get(fileInfo.getFilePath());

                    if (spinner != null) {
                        spinner.visibleProperty().bind(
                            Bindings.createBooleanBinding(
                                () -> fileInfo.isProcessing() && !fileInfo.isCompleted(),
                                fileInfo.processingProperty(),
                                fileInfo.completedProperty()
                            )
                        );
                        checkLabel.visibleProperty().bind(fileInfo.completedProperty());
                        statusContainer.getChildren().setAll(spinner, checkLabel);
                    } else {
                        statusContainer.getChildren().clear();
                    }
                    setGraphic(cellGraphic);
                }
            }
        });

        tableView.getColumns().addAll(checkBoxColumn, fileInfoColumn);
        tableView.setEditable(true);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        filteredData = new FilteredList<>(fileList, p -> true);
        tableView.setItems(filteredData);

        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filteredData.setPredicate(fileInfo -> {
                if (newValue == null || newValue.isEmpty()) return true;
                String lowerCaseFilter = newValue.toLowerCase();
                return fileInfo.getFileName().toLowerCase().contains(lowerCaseFilter) ||
                       fileInfo.getFilePath().toLowerCase().contains(lowerCaseFilter);
            });
        });

        final SimpleBooleanProperty heightMeasured = new SimpleBooleanProperty(false);
        tableView.setRowFactory(tv -> {
            TableRow<FileExecutionInfo> row = new TableRow<>();
            row.heightProperty().addListener((obs, oldHeight, newHeight) -> {
                if (newHeight.doubleValue() > 20 && !row.isEmpty() && !heightMeasured.get()) {
                    heightMeasured.set(true);
                    measuredCellHeight.set(newHeight.doubleValue());
                    tableView.setFixedCellSize(newHeight.doubleValue());
                }
            });
            return row;
        });

        final int MAX_VISIBLE_ROWS = 5;
        tableView.prefHeightProperty().bind(
            Bindings.createDoubleBinding(() -> {
                int itemCount = filteredData.size();
                double cellHeight = measuredCellHeight.get();
                int visibleRows = Math.min(itemCount, MAX_VISIBLE_ROWS);
                if (visibleRows == 0) return cellHeight + 2;
                return (visibleRows * cellHeight) + 2;
            }, filteredData, measuredCellHeight)
        );

        executeButton.setOnAction(e -> {
            for (MFXProgressSpinner spinner : spinners.values()) {
                Color singleColor = Color.web("#0078D4");
                spinner.setColor1(singleColor);
                spinner.setColor2(singleColor);
                spinner.setColor3(singleColor);
                spinner.setColor4(singleColor);
            }
            
            fileList.forEach(fileInfo -> fileInfo.setProcessing(true));

            JSONObject payload = new JSONObject();
            List<FileExecutionInfo> selectedFiles = fileList.stream()
                .filter(FileExecutionInfo::isSelected)
                .toList();
            String[] selectedFilePaths = selectedFiles.stream()
                .map(FileExecutionInfo::getFilePath)
                .toArray(String[]::new);

            payload.put("project_id", userProjectsInfo.getProjectID());
            payload.put("owner", userProjectsInfo.getOwner());
            payload.put("path", selectedFilePaths);
            projectController.getProjecFilesRequest(payload);
        });

        view.getChildren().addAll(searchField, tableView, executionControls);
    }

    public VBox getView() {
        return view;
    }

    public UserProjectsInfo getUserProjectsInfo() {
        return userProjectsInfo;
    }

    public void updateFileList(List<FileExecutionInfo> files, UserProjectsInfo userProjectsInfo) {
        this.userProjectsInfo = userProjectsInfo;
        Platform.runLater(() -> {
            spinners.clear();
            for (FileExecutionInfo fileInfo : files) {
                MFXProgressSpinner spinner = new MFXProgressSpinner();
                spinner.setRadius(6);
                spinners.put(fileInfo.getFilePath(), spinner);
            }
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

    public String getArguments() {
        return argumentsField.getText();
    }

    public ObservableList<FileExecutionInfo> getFileList() {
        return fileList;
    }

    public void markFileAsCompleted(String filePath) {
        Platform.runLater(() -> {
            fileList.stream()
                .filter(f -> f.getFilePath().equals(filePath))
                .findFirst()
                .ifPresent(fileInfo -> fileInfo.setCompleted(true));
        });
    }

    public static class FileExecutionInfo {
        private final SimpleBooleanProperty selected;
        private final SimpleStringProperty fileName;
        private final SimpleStringProperty filePath;
        private final String tabId;
        private final SimpleBooleanProperty processing;
        private final SimpleBooleanProperty completed;

        public FileExecutionInfo(boolean selected, String fileName, String filePath, String tabId) {
            this.selected = new SimpleBooleanProperty(selected);
            this.fileName = new SimpleStringProperty(fileName);
            this.filePath = new SimpleStringProperty(filePath);
            this.tabId = tabId;
            this.processing = new SimpleBooleanProperty(false);
            this.completed = new SimpleBooleanProperty(false);
        }

        public boolean isSelected() { return selected.get(); }
        public SimpleBooleanProperty selectedProperty() { return selected; }
        public String getFileName() { return fileName.get(); }
        public String getFilePath() { return filePath.get(); }
        public String getTabId() { return tabId; }
        public boolean isProcessing() { return processing.get(); }
        public void setProcessing(boolean isProcessing) { this.processing.set(isProcessing); }
        public SimpleBooleanProperty processingProperty() { return processing; }
        public boolean isCompleted() { return completed.get(); }
        public void setCompleted(boolean isCompleted) { this.completed.set(isCompleted); }
        public SimpleBooleanProperty completedProperty() { return completed; }
    }
}