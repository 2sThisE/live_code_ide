package com.ethis2s.view;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
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
    private final DoubleProperty measuredCellHeight = new SimpleDoubleProperty(52.0); // 초기 추정값

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
        searchField.getStyleClass().add("file-search-field"); 

        // --- 실행 인자 필드와 실행 버튼 ---
        argumentsField = new TextField();
        argumentsField.setPromptText("실행 인자 (선택 사항)");
        HBox.setHgrow(argumentsField, Priority.ALWAYS);

        executeButton = new Button("실행");
        executeButton.getStyleClass().add("execute-button");

        HBox executionControls = new HBox(10, argumentsField, executeButton);
        // --- ---

        tableView = new TableView<>();
        tableView.getStyleClass().add("file-execution-table"); 
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 1. 체크박스 컬럼
        TableColumn<FileExecutionInfo, Boolean> checkBoxColumn = new TableColumn<>();
        checkBoxColumn.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        checkBoxColumn.setCellFactory(CheckBoxTableCell.forTableColumn(checkBoxColumn));
        checkBoxColumn.setEditable(true);
        checkBoxColumn.setPrefWidth(40);
        checkBoxColumn.setResizable(false);

        filteredData = new FilteredList<>(fileList, p -> true);
        TableColumn<FileExecutionInfo, Void> fileInfoColumn = new TableColumn<>("File");
    
        
        
        fileInfoColumn.setCellFactory(column -> { // 'column' 파라미터는 TableColumn 자체를 의미
        // 이 람다 블록은 새로운 TableCell 객체를 반환해야 합니다.
        return new TableCell<FileExecutionInfo, Void>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null); // 텍스트도 비워주는 것이 안전
                } else {
                    FileExecutionInfo fileInfo = getTableRow().getItem();
                    if (fileInfo != null) {
                        // 아이콘, 파일 이름, 경로 Label 생성 (이전과 동일)
                        Label icon = new Label("\uD83D\uDCC4");
                        icon.getStyleClass().add("file-icon");
                        
                        Label fileName = new Label(fileInfo.getFileName());
                        fileName.getStyleClass().add("file-name-label");
                        
                        Label filePath = new Label(fileInfo.getFilePath());
                        filePath.getStyleClass().add("file-path-label");
                        
                        VBox fileDetails = new VBox(fileName, filePath);
                        HBox cellGraphic = new HBox(10, icon, fileDetails);
                        cellGraphic.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                        
                        setGraphic(cellGraphic);
                        setText(null); // 그래픽을 사용하므로 텍스트는 비움
                    } else {
                        setGraphic(null);
                        setText(null);
                    }
                }
            }
        };
    });
    tableView.getColumns().addAll(checkBoxColumn, fileInfoColumn); // [수정] 새로운 컬럼 사용
    tableView.setEditable(true);
    // 2. [가장 중요한 부분] TableView가 'filteredData'를 보도록 설정합니다.
    tableView.setItems(filteredData);

    // 3. 검색 필드의 텍스트가 변경될 때마다 'filteredData'의 필터를 업데이트합니다.
    searchField.textProperty().addListener((observable, oldValue, newValue) -> {
        filteredData.setPredicate(fileInfo -> {
            // 필터링 로직 (이 부분은 완벽합니다)
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
    
    // 1. [측정] TableView에 'rowFactory'를 설정하여, 행(Row)이 생성될 때마다 높이를 측정합니다.
    tableView.setRowFactory(tv -> {
            TableRow<FileExecutionInfo> row = new TableRow<>();
            
            // 행의 높이가 변경될 때마다(초기 렌더링 포함), 그 높이를 우리의 속성에 업데이트합니다.
            row.heightProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.doubleValue() > 0 && measuredCellHeight.get() != newVal.doubleValue()) {
                    // 0보다 큰 유효한 높이가 측정되면, measuredCellHeight 값을 업데이트합니다.
                    // 이 업데이트는 아래의 높이 계산 바인딩을 자동으로 다시 트리거합니다.
                    measuredCellHeight.set(newVal.doubleValue());
                }
            });
            
            return row;
        });
        
        // 2. [바인딩] TableView의 prefHeightProperty를 filteredData의 크기와 '측정된 셀 높이'에 바인딩합니다.
        final int MAX_VISIBLE_ROWS = 5;

        tableView.prefHeightProperty().bind(Bindings.createDoubleBinding(() -> {
            Node header = tableView.lookup(".column-header-background");
            double headerHeight = (header == null) ? 0 : header.getBoundsInParent().getHeight();
            
            int numberOfRows = filteredData.size();
            int visibleRows = Math.max(1, Math.min(numberOfRows, MAX_VISIBLE_ROWS));
             if(numberOfRows == 0){
                visibleRows = 1;
            }

            // [수정] 하드코딩된 상수 대신, 동적으로 측정된 measuredCellHeight 값을 사용합니다.
            return headerHeight + (visibleRows * measuredCellHeight.get()) + 2;

        }, filteredData, measuredCellHeight)); // [중요] measuredCellHeight가 바뀔 때도 다시 계산하도록 의존성 추가
        tableView.maxHeightProperty().bind(tableView.prefHeightProperty());


        fileInfoColumn.prefWidthProperty().bind(
            tableView.widthProperty()
            .subtract(checkBoxColumn.widthProperty()) // 체크박스 컬럼 너비 빼기
            .subtract(2) // 스크롤바 등을 위한 약간의 여백
        );



        AnchorPane.setTopAnchor(searchField, 0.0);
        AnchorPane.setLeftAnchor(searchField, 0.0);
        AnchorPane.setRightAnchor(searchField, 0.0);

        AnchorPane.setTopAnchor(tableView, 40.0);
        AnchorPane.setLeftAnchor(tableView, 0.0);
        AnchorPane.setRightAnchor(tableView, 0.0);

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
