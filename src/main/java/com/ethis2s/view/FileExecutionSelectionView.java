package com.ethis2s.view;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.util.ConfigManager;

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
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
    private TextField commandField; 
    private Button executeButton;
    private TableView<FileExecutionInfo> tableView;
    private final ObservableList<FileExecutionInfo> fileList = FXCollections.observableArrayList();
    private FilteredList<FileExecutionInfo> filteredData;
    private final SimpleDoubleProperty measuredCellHeight = new SimpleDoubleProperty(52.0);
    private final Map<String, MFXProgressSpinner> spinners = new HashMap<>();
    private UserProjectsInfo userProjectsInfo;
    private final ProjectController projectController;
    private String selectedFile;
    private ComboBox<String> runConfigComboBox; // [추가] 실행 모드 선택 박스
    private static final Map<String, String> projectLastCommandCache = new HashMap<>();

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
        searchField.setPromptText("서버로부터 불러올 파일 검색...");
        searchField.getStyleClass().add("file-search-field");

        runConfigComboBox = new ComboBox<>();
        runConfigComboBox.setPromptText("실행 모드 선택");
        runConfigComboBox.setPrefWidth(150);

        // ConfigManager에서 "Run Java", "Build Project" 같은 목록을 가져와서 채움
        Map<String, Object> commands = ConfigManager.getInstance()
                .get("runConfig", "userCommands", Map.class, new HashMap<>());
        if (commands != null) {
            runConfigComboBox.getItems().addAll(commands.keySet());
            // 목록이 있으면 첫 번째꺼 자동 선택
            if (!commands.isEmpty()) runConfigComboBox.getSelectionModel().selectFirst(); 
        }
        runConfigComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && commands != null) {
                String template = (String) commands.get(newVal);
                if (template != null) {
                    commandField.setText(template);
                }
            }
        });

        // [핵심 변경 3] 명령어 입력 필드 (이제 여기가 메인입니다)
        commandField = new TextField();
        commandField.setPromptText("실행 명령어");
        HBox.setHgrow(commandField, Priority.ALWAYS);

        executeButton = new Button("실행");
        executeButton.getStyleClass().add("execute-button");

        // [수정] 3. 레이아웃에 콤보박스도 같이 넣기
        HBox executionControls = new HBox(10, runConfigComboBox, commandField, executeButton);

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
                icon.setMinWidth(Region.USE_PREF_SIZE);
                fileName.setMinWidth(Region.USE_PREF_SIZE);
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
                    spinner.setMinWidth(Region.USE_PREF_SIZE);
                    checkLabel.setMinWidth(Region.USE_PREF_SIZE);
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

        tableView.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                selectedFile = newSelection.getFilePath();
            }
        });

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
            // 파일 선택 확인
            if (selectedFile == null) {
                System.out.println(">> 목록에서 실행할 파일(Main.java 등)을 클릭해주세요.");
                return;
            }
            
            // 명령어가 비어있으면 안 됨
            String commandToRun = commandField.getText();
            if (commandToRun == null || commandToRun.trim().isEmpty()) {
                System.out.println(">> 실행할 명령어가 없습니다.");
                return;
            }

            // ★ 현재 입력된 명령어를 메모리 캐시에 저장 (다음 번에 열 때 기억하기 위해)
            if (userProjectsInfo != null) {
                projectLastCommandCache.put(userProjectsInfo.getProjectID(), commandToRun);
            }

            // 3. UI 처리 (기존 코드)
            for (MFXProgressSpinner spinner : spinners.values()) {
                Color singleColor = Color.web("#0078D4");
                spinner.setColor1(singleColor);
                spinner.setColor2(singleColor);
                spinner.setColor3(singleColor);
                spinner.setColor4(singleColor);
            }
            
            fileList.forEach(fileInfo -> fileInfo.setProcessing(true));

            JSONObject payload = new JSONObject();
            // 체크박스 체크된 파일들만 다운로드
            List<FileExecutionInfo> selectedFiles = fileList.stream()
                .filter(FileExecutionInfo::isSelected)
                .toList();
            
            // (주의) 체크된 게 없으면 다운로드가 안 되므로 경고하거나, 
            // 현재 선택된 파일을 강제로 포함시키는 로직이 필요할 수 있음. 
            // 일단은 기존 로직 유지.

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

    public String getSelectedFile(){
        return selectedFile;
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

            // ★ 메모리 캐시에서 마지막 명령어 불러오기
            String pID = userProjectsInfo.getProjectID();
            if (projectLastCommandCache.containsKey(pID)) {
                String lastCommand = projectLastCommandCache.get(pID);
                commandField.setText(lastCommand);
            } else {
                // 저장된 게 없으면 콤보박스 첫 번째 거 선택해서 기본값 보여주기
                if (!runConfigComboBox.getItems().isEmpty()) {
                    runConfigComboBox.getSelectionModel().selectFirst();
                } else {
                    commandField.clear();
                }
            }
        });
    }

    public void setVisible(boolean visible) {
        view.setVisible(visible);
        if (visible) {
            searchField.requestFocus();
        }
    }
    // [추가] 현재 선택된 실행 모드 이름(예: Run Java)을 컨트롤러가 가져갈 수 있게 해줌
    public String getSelectedRunConfigName() {
        return runConfigComboBox.getValue();
    }

    

    public boolean isVisible() {
        return view.isVisible();
    }

    public String getCommandToExecute() {
        return commandField.getText();
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