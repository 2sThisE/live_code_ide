package com.ethis2s.view;

import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import io.github.palexdev.materialfx.controls.MFXSpinner;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.json.JSONObject;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.UserProjectsInfo;

public class FileExecutionSelectionView {

    private VBox view;
    private TextField searchField;
    private TextField argumentsField; // 실행 인자를 위한 텍스트 필드
    private Button executeButton; // 실행 버튼
    private TableView<FileExecutionInfo> tableView;
    private ObservableList<FileExecutionInfo> fileList = FXCollections.observableArrayList();
    private FilteredList<FileExecutionInfo> filteredData;
    private final DoubleProperty measuredCellHeight = new SimpleDoubleProperty(52.0); // 초기 추정값
    private final Map<String, MFXProgressSpinner> spinners = new HashMap<>();
    private UserProjectsInfo userProjectsInfo;
    private final ProjectController projectController;
    public FileExecutionSelectionView(ProjectController projectController) {
        this.projectController=projectController;
        initialize();
    }

    public void bindWidthTo(ReadOnlyDoubleProperty parentSearchBoxWidthProperty) {
        final double EXTRA_PADDING = 20.0;
        view.prefWidthProperty().bind(parentSearchBoxWidthProperty.add(EXTRA_PADDING));
    }

    private void initialize() {
        // [수정] 루트 컨테이너를 VBox로 변경하여 자식 컨트롤들을 수직으로 자동 정렬합니다.
        // 이렇게 하면 아이템 개수에 따라 TableView의 높이가 동적으로 변하는 요구사항을 쉽게 구현할 수 있습니다.
        view = new VBox(10); // 컨트롤 사이의 간격을 10px로 설정
        view.getStyleClass().add("file-execution-selection-view");
        view.setVisible(false);
        view.setPadding(new Insets(15));

        // --- UI 컨트롤 생성 (이전과 동일) ---
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

        // --- TableView 컬럼 및 동적 높이 설정 ---
        TableColumn<FileExecutionInfo, Boolean> checkBoxColumn = new TableColumn<>();
        checkBoxColumn.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        checkBoxColumn.setCellFactory(CheckBoxTableCell.forTableColumn(checkBoxColumn));
        checkBoxColumn.setEditable(true);
        checkBoxColumn.setPrefWidth(40);
        checkBoxColumn.setResizable(false);

        TableColumn<FileExecutionInfo, Void> fileInfoColumn = new TableColumn<>("File");
        fileInfoColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    FileExecutionInfo fileInfo = getTableRow().getItem();
                    Label icon = new Label("\uD83D\uDCC4");
                    icon.getStyleClass().add("file-icon");
                    Label fileName = new Label(fileInfo.getFileName());
                    fileName.getStyleClass().add("file-name-label");
                    Label filePath = new Label(fileInfo.getFilePath());
                    filePath.getStyleClass().add("file-path-label");
                    
                    HBox fileDetails = new HBox();
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    fileDetails.getChildren().addAll(fileName, spacer, filePath);
                    fileDetails.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);

                    // [추가] MFXSpinner 생성 및 설정
                    MFXProgressSpinner spinner = spinners.get(fileInfo.getFilePath());
                    Node spinnerNode = (spinner != null) ? spinner : new Region();
                    HBox cellGraphic = new HBox(10, icon, fileDetails, spinnerNode);
                    cellGraphic.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    
                    HBox.setHgrow(fileDetails, Priority.ALWAYS);
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

        // --- TableView 높이 동적 조절 및 CSS 대응 로직 ---
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
        final double HEADER_HEIGHT = 0; 
        final double PADDING = 2; 

        tableView.prefHeightProperty().bind(
            Bindings.createDoubleBinding(() -> {
                int itemCount = filteredData.size();
                double cellHeight = measuredCellHeight.get();
                int visibleRows = Math.min(itemCount, MAX_VISIBLE_ROWS);
                if (visibleRows == 0) {
                    return cellHeight + PADDING;
                }
                return (visibleRows * cellHeight) + HEADER_HEIGHT + PADDING;
            }, filteredData, measuredCellHeight) 
        );
        executeButton.setOnAction(e->{
            for(MFXProgressSpinner spinner:spinners.values()){
                Color singleColor = Color.web("#0078D4"); // 예: 세련된 파란색
                spinner.setColor1(singleColor);
                spinner.setColor2(singleColor);
                spinner.setColor3(singleColor);
                spinner.setColor4(singleColor);
                spinner.setVisible(true);
            }
            JSONObject payload=new JSONObject();
            List<FileExecutionInfo> selectedFiles = fileList.stream()
                .filter(FileExecutionInfo::isSelected)
                .toList();
            String[] selectedFilePaths = selectedFiles.stream()
                .map(FileExecutionInfo::getFilePath) // 각 FileExecutionInfo 객체에서 filePath 문자열만 추출
                .toArray(String[]::new);             // 추출된 문자열들을 String 배열로 수집

            payload.put("project_id", userProjectsInfo.getProjectID());
            payload.put("owner",userProjectsInfo.getOwner());
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
        this.userProjectsInfo=userProjectsInfo;
        Platform.runLater(() -> {
            // [추가] 파일 목록이 업데이트될 때마다 스피너 맵을 초기화하여 메모리 누수를 방지합니다.
            spinners.clear();
            for (FileExecutionInfo fileInfo : files) {
                MFXProgressSpinner spinner = new MFXProgressSpinner();
                spinner.setVisible(false);
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

    // [추가] 외부에서 스피너에 접근하기 위한 getter 메서드
    public Map<String, MFXProgressSpinner> getSpinners() {
        return spinners;
    }

    /**
     * 지정된 파일 경로에 해당하는 스피너를 "체크" 라벨로 변경합니다.
     * @param filePath 라벨로 변경할 파일의 경로
     */
    public void changeSpinnerToLabel(String filePath) {
        Platform.runLater(() -> {
            MFXProgressSpinner spinner = spinners.get(filePath);
            if (spinner != null && spinner.getParent() instanceof HBox) {
                HBox parentContainer = (HBox) spinner.getParent();
                
                Label checkLabel = new Label("\u2714");
                checkLabel.setStyle("-fx-text-fill: #00c853;");
                int spinnerIndex = parentContainer.getChildren().indexOf(spinner);
                if (spinnerIndex != -1) {
                    parentContainer.getChildren().set(spinnerIndex, checkLabel);
                    // 한 번 변경된 스피너는 더 이상 제어할 필요가 없으므로 맵에서 제거합니다.
                    spinners.remove(filePath);
                }
            }
        });
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
