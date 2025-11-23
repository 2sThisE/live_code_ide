package com.ethis2s.view;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.json.JSONObject;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.MacosNativeUtil;

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
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

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
    private ContextMenu suggestionsPopup;
    
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
        

        // [핵심 변경 3] 명령어 입력 필드 (이제 여기가 메인입니다)
        commandField = new TextField();
        commandField.setPromptText("실행 명령어");
        HBox.setHgrow(commandField, Priority.ALWAYS);

        executeButton = new Button("실행");
        executeButton.getStyleClass().add("execute-button");

        setupAutoComplete();

        // [수정] 3. 레이아웃에 콤보박스도 같이 넣기
        HBox executionControls = new HBox(10, commandField, executeButton);

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
    private void setupAutoComplete() {
        suggestionsPopup = new ContextMenu();
        // 팝업이 텍스트 필드의 폭에 맞춰지도록 설정 (선택 사항)
        suggestionsPopup.setMinWidth(400); 
        
        // ConfigManager에서 명령어 목록 가져오기
        Map<String, Object> commands = ConfigManager.getInstance()
                .get("runConfig", "userCommands", Map.class, new HashMap<>());

        // 1. 텍스트 입력 시 추천 목록 갱신 리스너
        commandField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                suggestionsPopup.hide();
                return;
            }

            // 검색어와 일치하는 명령어 찾기
            List<MenuItem> suggestions = commands.entrySet().stream()
                .filter(entry -> {
                    String key = entry.getKey().toLowerCase();
                    String val = String.valueOf(entry.getValue()).toLowerCase();
                    String input = newVal.toLowerCase();
                    return key.contains(input) || val.contains(input);
                })
                .map(entry -> {
                    String name = entry.getKey(); 
                    String cmd = String.valueOf(entry.getValue()); 
                    
                    MenuItem item = new MenuItem(name + " : " + cmd);
                    
                    // 항목 선택 시 동작
                    item.setOnAction(e -> {
                        commandField.setText(cmd); // 명령어 입력
                        commandField.positionCaret(cmd.length()); // 커서 맨 뒤로
                        suggestionsPopup.hide();
                    });
                    return item;
                })
                .collect(Collectors.toList());

            // 팝업 갱신
            suggestionsPopup.getItems().clear();
            if (!suggestions.isEmpty()) {
                suggestionsPopup.getItems().addAll(suggestions);
                if (!suggestionsPopup.isShowing()) {
                    if((System.getProperty("os.name").toLowerCase()).contains("mac")) suggestionsPopup.show(commandField, Side.BOTTOM, 0, -MacosNativeUtil.getTitleBarHeightOffset());
                    else suggestionsPopup.show(commandField, Side.BOTTOM, 0, 0);
                }
            } else {
                suggestionsPopup.hide();
            }
        });
        commandField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB) {
                if (suggestionsPopup.isShowing() && !suggestionsPopup.getItems().isEmpty()) {
                    suggestionsPopup.getItems().get(0).fire();
                }
                event.consume(); // ★ Tab 기본 동작 완전 차단
            }
        });

        // 2. [핵심 수정] 키보드 이벤트 핸들링 (Tab 및 방향키)
        commandField.setOnKeyPressed(event -> {
            // [아래 화살표] : 메뉴로 포커스 넘기기
            if (event.getCode() == KeyCode.DOWN) {
                if (suggestionsPopup.isShowing() && !suggestionsPopup.getItems().isEmpty()) {
                    // ★ 핵심: 팝업의 첫 번째 메뉴 아이템으로 포커스를 강제로 줍니다.
                    // 이렇게 하면 이후부터는 위/아래 방향키로 메뉴 이동이 가능해집니다.
                    suggestionsPopup.getSkin().getNode().lookup(".menu-item").requestFocus();
                }
            }
        });
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

            // 마지막 명령어 불러오기
            String pID = userProjectsInfo.getProjectID();
            if (projectLastCommandCache.containsKey(pID)) {
                commandField.setText(projectLastCommandCache.get(pID));
            } else {
                // 저장된 게 없으면 기본 템플릿 하나(예: 첫 번째) 보여주거나 비워둠
                commandField.clear();
            }
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