package com.ethis2s.view;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.ethis2s.controller.MainController;
import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.util.ConfigManager;

import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

public class MainScreen {

    private TreeView<Object> fileExplorer;
    private TreeItem<Object> projectRoot;
    private BorderPane fileExplorerContainer;
    private final Set<String> expandedItemPaths = new HashSet<>();
    private Label connectionStatusLabel;
    private MFXProgressSpinner antlrIndicator; // ANTLR 분석 상태 인디케이터
    private UserProjectsInfo currentProjectForFileTree;
    private UserInfo currentUserInfo;
    private boolean isTransitioning = false;
    private final double SEARCH_FIELD_NARROW_WIDTH = 200;
    private final double SEARCH_FIELD_WIDE_WIDTH = 400;
    private HBox searchToolsContainer; // 이 필드를 추가해주세요.
    

    private double xOffset = 0;
    private double yOffset = 0;

    public class NodeType {
        private final String name;
        private final String type;
        public NodeType(String name, String type) { this.name = name; this.type = type; }
        public String getType() { return type; }
        @Override public String toString() { return name; }
    }

    private ProblemsView problemsView; // Make ProblemsView accessible
    private DebugView debugView;
    private OutputView outputView;
    private Tab problemsTab;
    private Label problemsTabLabel;
    private TabPane bottomTabPane; // TabPane을 필드로 선언해서 접근 가능하게 할게요!
    private SplitPane editorArea;
    private TextField searchField;
    private HBox searchBox;
    private Button prevButton;
    private Button nextButton;
    private ToggleButton caseSensitiveCheck;
    private Label resultLabel;
    private HBox trafficLights;
    
    public void updateProblemsTab(int errorCount) {
        // 이제 Tab 객체가 있는지 직접 확인합니다.
        if (problemsTab == null) return;

        Platform.runLater(() -> {
            if (errorCount > 0) {
                // 2. Tab의 스타일 클래스를 직접 추가합니다.
                if (!problemsTab.getStyleClass().contains("tab-error")) {
                    problemsTab.getStyleClass().add("tab-error");
                }

                // 탭을 선택하는 로직은 그대로 유지합니다.
                if (bottomTabPane != null) {
                    bottomTabPane.getSelectionModel().select(problemsTab);
                }
            } else {
                problemsTab.getStyleClass().remove("tab-error");
            }
        });
    }

    public ProblemsView getProblemsView() {
        return problemsView;
    }

    public OutputView getOutputView() {
        return outputView;
    }

    public DebugView getDebugView() {
        return debugView;
    }

    public TextField getSearchField() {
        return searchField;
    }
    public HBox getSearchBox() { return searchBox; }
    public Button getPrevButton() { return prevButton; }
    public Button getNextButton() { return nextButton; }
    public ToggleButton getCaseSensitiveCheck() { return caseSensitiveCheck; }
    public Label getResultLabel() { return resultLabel; }

    public BorderPane createMainScreen(Stage stage, SplitPane editorArea, Label statusLabel, MainController mainController) {
        BorderPane mainLayout = new BorderPane(); // This will be the absolute root.
        this.editorArea = editorArea;
        
        MenuBar menuBar = new MenuBar();
        Menu fileMenu = new Menu("File");
        MenuItem settingsItem = new MenuItem("설정");
        settingsItem.setOnAction(e -> mainController.showSettingsView());
        MenuItem logoutItem = new MenuItem("로그아웃");
        logoutItem.setOnAction(e -> mainController.performLogout());
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> Platform.exit());
        fileMenu.getItems().addAll(settingsItem, new SeparatorMenuItem(), logoutItem, new SeparatorMenuItem(), exitItem);
        menuBar.getMenus().add(fileMenu);

        // --- OS-Specific Title Bar and Menu Bar Handling ---
        String os = System.getProperty("os.name").toLowerCase();
        boolean isMac = os.contains("mac");

        // This pane will hold the actual content, including our custom title bar.
        BorderPane contentPane = new BorderPane();
        contentPane.getStyleClass().add("root-pane");

        if (isMac) {
            menuBar.setUseSystemMenuBar(true);
            // On Mac, add the menu bar to the root layout. It won't be visible in the window,
            // but it needs to be in the scene graph for the OS to display it in the native menu bar.
            mainLayout.setTop(menuBar);
        }

        // 1. Create the container that looks like a TextField
        this.searchBox = new HBox();
        searchBox.getStyleClass().add("centered-search-field"); // Use existing style
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setSpacing(5);
        searchBox.setMaxWidth(500);
        searchBox.setPrefWidth(400);

        // 2. Create the actual transparent TextField
        this.searchField = new TextField();
        searchField.setPromptText("검색...");
        searchField.getStyleClass().add("transparent-textfield"); // New style for transparency
        HBox.setHgrow(searchField, Priority.ALWAYS); // Make the text field take up available space

        // 3. Create the buttons
        this.prevButton = new Button("↑");
        this.nextButton = new Button("↓");
        this.caseSensitiveCheck = new ToggleButton("Aa");
        this.resultLabel = new Label();
        
        // Style the buttons to be smaller
        List.of(prevButton, nextButton).forEach(btn -> btn.getStyleClass().add("search-tool-button"));
        caseSensitiveCheck.getStyleClass().add("search-tool-toggle-button");

        // Hide buttons by default
        List.of(resultLabel, caseSensitiveCheck, prevButton, nextButton).forEach(node -> {
            node.setVisible(false);
            node.setManaged(false);
        });

        // 4. Add the transparent textfield and buttons to the container
        searchBox.getChildren().addAll(searchField, resultLabel, caseSensitiveCheck, prevButton, nextButton);

        // --- Create Title Bar based on OS ---
        
        StackPane topPane;
        if (isMac) {
            

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox backgroundBar = new HBox(spacer);
            backgroundBar.setAlignment(Pos.CENTER);
            
            topPane = new StackPane(backgroundBar, searchBox);

        } else {
            // Windows/Other Style Title Bar (Original Implementation)
            Button minimizeButton = new Button("—");
            minimizeButton.getStyleClass().add("window-button");
            minimizeButton.setOnAction(e -> stage.setIconified(true));

            Button maximizeButton = new Button("◻");
            maximizeButton.getStyleClass().add("window-button");
            maximizeButton.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

            Button windowCloseButton = new Button("✕");
            windowCloseButton.getStyleClass().addAll("window-button", "close-button");
            windowCloseButton.setOnAction(e -> Platform.exit());

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            
            HBox backgroundBar = new HBox(menuBar, spacer, minimizeButton, maximizeButton, windowCloseButton);
            backgroundBar.setAlignment(Pos.CENTER);

            topPane = new StackPane(backgroundBar, searchBox);
        }

        topPane.getStyleClass().add("custom-title-bar");

        // Dragging logic (works for both styles)
        final int resizeBorder = 8; 
        final boolean[] isDragging = {false};

        topPane.setOnMousePressed(event -> {
            // Double-click to maximize/restore
            if (event.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
                isDragging[0] = false; // Prevent dragging on double-click
                return;
            }

            // Prevent dragging when clicking inside the searchBox
            Node target = (Node) event.getTarget();
            if (searchBox.equals(target) || searchBox.getChildren().contains(target)) {
                 isDragging[0] = false;
                 return;
            }
            
            // Single-click to drag
            if (event.getY() > resizeBorder) {
                isDragging[0] = true;
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            } else {
                isDragging[0] = false;
            }
        });

        topPane.setOnMouseDragged(event -> {
            if (isDragging[0]) {
                javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
                javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();

                // Restore window if it's dragged from a maximized state
                if (stage.isMaximized()) {
                    // Retain proportional position of cursor inside the window
                    double mouseX_ratio = event.getSceneX() / stage.getWidth();
                    stage.setMaximized(false);
                    // After restoring, the width changes, so we update the offset
                    xOffset = stage.getWidth() * mouseX_ratio;
                }

                // Snap to top -> Maximize
                if (event.getScreenY() <= bounds.getMinY()) {
                    stage.setMaximized(true);
                    return;
                }
                // Snap to left
                if (event.getScreenX() <= bounds.getMinX()) {
                    stage.setX(bounds.getMinX());
                    stage.setY(bounds.getMinY());
                    stage.setWidth(bounds.getWidth() / 2);
                    stage.setHeight(bounds.getHeight());
                    return;
                }
                // Snap to right
                if (event.getScreenX() >= bounds.getMaxX() - 1) {
                    stage.setX(bounds.getMinX() + (bounds.getWidth() / 2));
                    stage.setY(bounds.getMinY());
                    stage.setWidth(bounds.getWidth() / 2);
                    stage.setHeight(bounds.getHeight());
                    return;
                }

                // Normal drag
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            }
        });
        
        topPane.setOnMouseReleased(event -> {
            isDragging[0] = false;
        });

        contentPane.setTop(topPane);

        fileExplorer = new TreeView<>();
        fileExplorer.setShowRoot(false);
        
        fileExplorerContainer = new BorderPane(fileExplorer);

        // --- 하단 탭 패널 생성 ---
        this.bottomTabPane = new TabPane(); // 필드에 TabPane 인스턴스를 할당해요.
        bottomTabPane.getStyleClass().add("bottom-tab-pane"); // 커스텀 스타일 적용

        // 'Output' 탭 생성
        this.outputView = new OutputView();
        Tab outputTab = new Tab("OUTPUT");
        outputTab.setContent(outputView.getView());
        outputTab.setClosable(false);

        // 'Problems' 탭 생성
        this.problemsView = new ProblemsView(mainController); // Assign to field
        this.problemsTab = new Tab("PROBLEMS");
        this.problemsTab.setGraphic(problemsTabLabel);
        problemsTab.setContent(problemsView.getView());
        problemsTab.setClosable(false);

        // 'Debug' 탭 생성
        this.debugView = new DebugView();
        Tab debugTab = new Tab("DEBUG");
        debugTab.setContent(debugView.getView());
        debugTab.setClosable(false);

        bottomTabPane.getTabs().addAll(outputTab, debugTab, problemsTab);
        // --- 하단 탭 패널 생성 끝 ---

        SplitPane centerSplit = new SplitPane(this.editorArea, bottomTabPane);
        
        centerSplit.setOrientation(Orientation.VERTICAL);
        centerSplit.setDividerPositions(0.75);

        SplitPane mainSplit = new SplitPane(fileExplorerContainer, centerSplit);
        mainSplit.setId("main-split-pane");
        mainSplit.setDividerPositions(0.25);
        SplitPane.setResizableWithParent(fileExplorerContainer, false);

        contentPane.setCenter(mainSplit);

        Label safeStatusLabel = (statusLabel != null) ? statusLabel : new Label();
        connectionStatusLabel = new Label();
        
        // MFXProgressSpinner 생성 및 설정
        antlrIndicator = new MFXProgressSpinner();
        antlrIndicator.setVisible(false);
        antlrIndicator.setRadius(6); // 크기를 작게 조절
        antlrIndicator.getStyleClass().add("mfx-progress-spinner");
        
        Region statusBarSpacer = new Region();
        HBox.setHgrow(statusBarSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(safeStatusLabel, statusBarSpacer, antlrIndicator, connectionStatusLabel);
        statusBar.setSpacing(5);
        statusBar.setAlignment(Pos.CENTER_RIGHT);

        statusBar.getStyleClass().add("status-bar");
        contentPane.setBottom(statusBar);

        try {
            com.ethis2s.util.ConfigManager configManager = com.ethis2s.util.ConfigManager.getInstance();
            String treeViewCss = configManager.getTreeViewThemePath();
            String topTabsCss = configManager.getTopTabsThemePath();
            String bottomTabsCss = configManager.getBottomTabsThemePath();

            if (treeViewCss != null) fileExplorerContainer.getStylesheets().add(treeViewCss);
            // if (topTabsCss != null) this.editorTabs.getStylesheets().add(topTabsCss);
            if (bottomTabsCss != null) bottomTabPane.getStylesheets().add(bottomTabsCss);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("컴포넌트별 CSS 파일을 로드할 수 없습니다.");
        }
        
        mainLayout.setCenter(contentPane);
        return mainLayout;
    }

    public void reloadComponentCss() {
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            String treeViewCss = configManager.getTreeViewThemePath();
            String topTabsCss = configManager.getTopTabsThemePath();
            String bottomTabsCss = configManager.getBottomTabsThemePath();

            if (fileExplorerContainer != null) {
                fileExplorerContainer.getStylesheets().clear();
                if (treeViewCss != null) fileExplorerContainer.getStylesheets().add(treeViewCss);
            }
            // if (this.editorTabs != null) { 
            //     this.editorTabs.getStylesheets().clear();
            //     if (topTabsCss != null) this.editorTabs.getStylesheets().add(topTabsCss);
            // }
            if (bottomTabPane != null) {
                bottomTabPane.getStylesheets().clear();
                if (bottomTabsCss != null) bottomTabPane.getStylesheets().add(bottomTabsCss);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("컴포넌트별 CSS 파일을 다시 로드할 수 없습니다.");
        }
    }
    
    /**
     * ANTLR 분석 상태 인디케이터의 표시 여부를 설정합니다.
     * 이 메소드는 UI 스레드에서 안전하게 실행됩니다.
     * @param show true이면 인디케이터를 표시, false이면 숨김
     */
    public void showAntlrIndicator(boolean show) {
        Platform.runLater(() -> {
            if (antlrIndicator != null) {
                Color singleColor = Color.web("#0078D4"); // 예: 세련된 파란색
                antlrIndicator.setColor1(singleColor);
                antlrIndicator.setColor2(singleColor);
                antlrIndicator.setColor3(singleColor);
                antlrIndicator.setColor4(singleColor);
                antlrIndicator.setVisible(show);
            }
        });
    }
    
    private void performTransition(Runnable viewUpdateLogic) {
        if (isTransitioning) return;
        isTransitioning = true;

        viewUpdateLogic.run();

        Node newHeader = fileExplorerContainer.getTop();
        if (newHeader != null) {
            fileExplorerContainer.setStyle("-fx-background-color: #252526;");
            newHeader.setOpacity(0);
            
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), newHeader);
            fadeIn.setToValue(1);
            
            fadeIn.setOnFinished(e -> {
                fileExplorerContainer.setStyle(null);
                isTransitioning = false;
            });
            fadeIn.play();
        } else {
            isTransitioning = false;
        }
    }

    public void clearProjectView() {
        fileExplorer.setRoot(null);
        fileExplorerContainer.setTop(null);
        currentProjectForFileTree = null;
        currentUserInfo = null;
    }

    public void showReconnectingStatus(boolean isReconnecting) {
        if (isReconnecting) {
            connectionStatusLabel.setText("재연결 중...");
            connectionStatusLabel.setStyle("-fx-text-fill: #ffa500;");
        } else {
            connectionStatusLabel.setText("");
        }
    }

    public void showConnectedStatus() {
        connectionStatusLabel.setText("연결됨");
        connectionStatusLabel.setStyle("-fx-text-fill: #55ff55;");
        PauseTransition delay = new PauseTransition(Duration.seconds(3));
        delay.setOnFinished(e -> connectionStatusLabel.setText(""));
        delay.play();
    }

    public void setProjectList(List<UserProjectsInfo> upiList, ProjectController projectController, MainController mainController, UserInfo userInfo) {
        this.currentUserInfo = userInfo;
        projectRoot = new TreeItem<>(new UserProjectsInfo("Project"));
        projectRoot.setExpanded(true);
        upiList.forEach(info -> projectRoot.getChildren().add(new TreeItem<>(info)));

        updateFileExplorerHeader(ViewType.PROJECT_LIST, null, projectController, mainController);
        setProjectListCellFactory(projectController, mainController);
        fileExplorer.setRoot(projectRoot);
        fileExplorer.setShowRoot(false);
        fileExplorer.setOnMouseClicked(null); 
    }

    private void setFileTreeViewMouseHandler() {
        fileExplorer.setOnMousePressed(event -> {
            Node node = event.getPickResult().getIntersectedNode();
            
            while (node != null && !(node instanceof TreeCell)) {
                node = node.getParent();
            }

            if (node == null || ((TreeCell<?>) node).isEmpty()) {
                if (fileExplorer.getEditingItem() == null) {
                    fileExplorer.getSelectionModel().clearSelection();
                }
            }
        });
    }

    private void setProjectListCellFactory(ProjectController projectController, MainController mainController) {
        fileExplorer.setEditable(true);
        fileExplorer.setCellFactory(tv -> 
            new EditableProjectCell(projectController, mainController, (UserProjectsInfo projectInfo) -> {
                changeToProjectDirView(projectInfo, projectController, mainController);
            })
        );
    }

    private void setFileTreeCellFactory(ProjectController projectController, String projectId, UserProjectsInfo userProjectsInfo, MainController mainController) {
        fileExplorer.setEditable(true);
        fileExplorer.setCellFactory(tv -> new EditableFileTreeCell(projectController, userProjectsInfo, mainController));
    }

    private ContextMenu createProjectContextMenu(UserProjectsInfo projectInfo, ProjectController projectController, MainController mainController, UserInfo userInfo) {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem sharedItem;
        if (projectInfo.getIsShared() != null && projectInfo.getIsShared()) {
            sharedItem = new MenuItem("공유 해제");
            sharedItem.setOnAction(e -> projectController.shareDeleteRequest(projectInfo.getProjectID(), userInfo.getNickname(), userInfo.getTag()));
        } else {
            sharedItem = new MenuItem("공유 옵션");
            sharedItem.setOnAction(e -> mainController.showSharedOptionView(projectInfo));
        }

        MenuItem propertiesItem = new MenuItem("속성");
        propertiesItem.setOnAction(e -> mainController.showProjectPropertiesView(projectInfo));
        MenuItem deleteItem = new MenuItem("삭제");
        if (userInfo != null && !userInfo.getId().equals(projectInfo.getOwner())) {deleteItem.setDisable(true);}
        deleteItem.setOnAction(e -> projectController.projectDeleteRequest(projectInfo.getProjectID()));
        contextMenu.getItems().addAll(sharedItem, deleteItem, propertiesItem);
        return contextMenu;
    }

    public void showProjectListView(ProjectController projectController, MainController mainController) {
        performTransition(() -> {
            if (projectRoot != null) {
                currentProjectForFileTree = null;
                setProjectListCellFactory(projectController, mainController);
                fileExplorer.setRoot(projectRoot);
                fileExplorer.setShowRoot(false);
                updateFileExplorerHeader(ViewType.PROJECT_LIST, null, projectController, mainController);
                fileExplorer.setOnMousePressed(null);
            }
        });
    }

    public void updateFileTree(JSONObject fileListJson) {
        if (fileListJson != null && fileListJson.has("name") && fileListJson.has("children")) {
            saveExpandedState();
            String rootName = fileListJson.getString("name");
            TreeItem<Object> root = new FileTreeItem(new NodeType(rootName, "folder")); // <-- 여기를 수정!
            root.setExpanded(true);

            JSONArray children = fileListJson.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                root.getChildren().add(buildTreeFromJson(children.getJSONObject(i)));
            }

            fileExplorer.setRoot(root);
            fileExplorer.setShowRoot(false);
            restoreExpandedState(root);
        }
    }

    private void changeToProjectDirView(UserProjectsInfo userProjectsInfo, ProjectController projectController, MainController mainController) {
        performTransition(() -> {
            this.currentProjectForFileTree = userProjectsInfo;
            setFileTreeCellFactory(projectController, userProjectsInfo.getProjectID(), userProjectsInfo, mainController);
            updateFileExplorerHeader(ViewType.FILE_TREE, userProjectsInfo, projectController, mainController);
            projectController.fileListRequest(userProjectsInfo);
            setFileTreeViewMouseHandler();
            fileExplorer.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.DELETE) {
                    TreeItem<Object> selectedItem = fileExplorer.getSelectionModel().getSelectedItem();
                    if (selectedItem != null && selectedItem.getValue() instanceof NodeType nodeType) {
                        String pathString = getItemPath(selectedItem);
                        if (pathString != null && !pathString.isEmpty()) {
                            String path = pathString.replace('\\', '/');
                            if(nodeType.getType().equals("file")){
                                projectController.delFileRequest(userProjectsInfo.getProjectID(), path, userProjectsInfo.getOwner());
                                projectController.fileListRequest(userProjectsInfo);
                            }else if(nodeType.getType().equals("folder")){
                                projectController.delDirRequest(userProjectsInfo.getProjectID(), path, userProjectsInfo.getOwner());
                                projectController.fileListRequest(userProjectsInfo);
                            }
                        }
                    }
                }
            });
        });
    }

    private enum ViewType { PROJECT_LIST, FILE_TREE }

    private void updateFileExplorerHeader(ViewType viewType, UserProjectsInfo currentProject, ProjectController projectController, MainController mainController) {
        HBox headerBox = new HBox();
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.getStyleClass().add("file-explorer-header");
        headerBox.setSpacing(5);

        if (viewType == ViewType.PROJECT_LIST) {
            Label label = new Label("  프로젝트 리스트");
            Button addBtn = new Button("+");
            addBtn.getStyleClass().add("add-project-button");
            addBtn.setTooltip(new Tooltip("새 프로젝트 생성"));
            addBtn.setOnAction(e -> handleAddProject());
            
            Button refreshBtn = new Button("↻");
            refreshBtn.getStyleClass().add("add-project-button");
            refreshBtn.setTooltip(new Tooltip("새로고침"));
            refreshBtn.setOnAction(e -> projectController.projectListRequest());

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            headerBox.getChildren().addAll(label, spacer, addBtn, refreshBtn);
        } else if (viewType == ViewType.FILE_TREE && currentProject != null) {
            Button backButton = new Button("←");
            backButton.getStyleClass().add("add-project-button");
            backButton.setTooltip(new Tooltip("프로젝트 목록으로 돌아가기"));
            backButton.setOnAction(e -> showProjectListView(projectController, mainController));

            Label label = new Label("  " + currentProject.getProjectName());
            Button addFileBtn = new Button("+");
            addFileBtn.getStyleClass().add("add-project-button");
            addFileBtn.setTooltip(new Tooltip("파일 추가"));
            addFileBtn.setOnAction(e -> handleAddItem("file"));

            Button addFolderBtn = new Button("+F");
            addFolderBtn.getStyleClass().add("add-project-button");
            addFolderBtn.setTooltip(new Tooltip("폴더 추가"));
            addFolderBtn.setOnAction(e -> handleAddItem("folder"));

            Button refreshBtn = new Button("↻");
            refreshBtn.getStyleClass().add("add-project-button");
            refreshBtn.setTooltip(new Tooltip("새로고침"));
            refreshBtn.setOnAction(e -> refreshCurrentFileTree(projectController));

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            headerBox.getChildren().addAll(backButton, label, spacer, addFileBtn, addFolderBtn, refreshBtn);
        }
        fileExplorerContainer.setTop(headerBox);
    }

    private void handleAddProject() {
        if (fileExplorer.getEditingItem() != null) return;
        if (projectRoot == null) return;

        TreeItem<Object> newItem = new TreeItem<>(new UserProjectsInfo(""));
        projectRoot.getChildren().add(0, newItem);
        
        Platform.runLater(() -> {
            fileExplorer.getSelectionModel().select(newItem);
            fileExplorer.scrollTo(fileExplorer.getRow(newItem));
            fileExplorer.edit(newItem); 
            
        });
    }

    private void handleAddItem(String itemType) {
        if (fileExplorer.getEditingItem() != null) return;
        TreeItem<Object> selectedItem = fileExplorer.getSelectionModel().getSelectedItem();
        TreeItem<Object> parentItem;

        if (selectedItem == null) {
            parentItem = fileExplorer.getRoot();
        } else if (selectedItem.getValue() instanceof NodeType node && "file".equals(node.getType())) {
            parentItem = selectedItem.getParent();
        } else {
            parentItem = selectedItem;
        }
        
        if (parentItem == null) return;
        
        parentItem.setExpanded(true);

        TreeItem<Object> newItem = new FileTreeItem(new NodeType("", itemType));
        parentItem.getChildren().add(0, newItem);
        
        Platform.runLater(() -> {
            fileExplorer.getSelectionModel().select(newItem);
            fileExplorer.scrollTo(fileExplorer.getRow(newItem));
            fileExplorer.edit(newItem); 
        });
    }

    public void refreshCurrentFileTree(ProjectController projectController) {
        if (currentProjectForFileTree != null) {
            saveExpandedState();
            projectController.fileListRequest(currentProjectForFileTree);
        }
    }

    private TreeItem<Object> buildTreeFromJson(JSONObject jsonObject) {
        String name = jsonObject.getString("name");
        String type = jsonObject.has("children") ? "folder" : "file";
        TreeItem<Object> node = new FileTreeItem(new NodeType(name, type));

        if ("folder".equals(type)) {
            JSONArray children = jsonObject.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                node.getChildren().add(buildTreeFromJson(children.getJSONObject(i)));
            }
        }
        return node;
    }

    private String getItemPath(TreeItem<Object> item) {
        if (item == null || item.getParent() == null) return "";
        StringBuilder path = new StringBuilder();
        TreeItem<Object> current = item;
        while (current != null && current.getParent() != null) {
            path.insert(0, current.getValue().toString());
            current = current.getParent();
            if (current != null && current.getParent() != null) {
                path.insert(0, "/");
            }
        }
        return path.toString();
    }

    private void saveExpandedState() {
        expandedItemPaths.clear();
        if (fileExplorer.getRoot() != null) {
            findExpandedPathsRecursive(fileExplorer.getRoot());
        }
    }

    private void findExpandedPathsRecursive(TreeItem<Object> item) {
        if (item != null && item.isExpanded()) {
            expandedItemPaths.add(getItemPath(item));
            for (TreeItem<Object> child : item.getChildren()) {
                findExpandedPathsRecursive(child);
            }
        }
    }

    private void restoreExpandedState(TreeItem<Object> item) {
        if (item != null) {
            if (expandedItemPaths.contains(getItemPath(item))) {
                item.setExpanded(true);
            }
            for (TreeItem<Object> child : item.getChildren()) {
                restoreExpandedState(child);
            }
        }
    }

    private class EditableProjectCell extends TreeCell<Object> {
        private TextField textField;
        private final ProjectController projectController;
        private final MainController mainController;
        private final Consumer<UserProjectsInfo> onDoubleClick;

        public EditableProjectCell(ProjectController projectController, MainController mainController, Consumer<UserProjectsInfo> onDoubleClick) {
            this.projectController = projectController;
            this.mainController = mainController;
            this.onDoubleClick = onDoubleClick;
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (getItem() instanceof UserProjectsInfo projectInfo && projectInfo.getProjectName().isEmpty()) {
                if (textField == null) {
                    createTextField();
                }
                setText(null);
                setGraphic(textField);
                textField.requestFocus();
                textField.selectAll();
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            updateItem(getItem(), false);
            if (getItem() instanceof UserProjectsInfo projectInfo && projectInfo.getProjectName().isEmpty()) {
                Platform.runLater(() -> {
                    if (getTreeItem() != null && getTreeItem().getParent() != null)
                        getTreeItem().getParent().getChildren().remove(getTreeItem());
                });
            }
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setOnMouseClicked(null);
            } else {
                if (isEditing() && item instanceof UserProjectsInfo pi && pi.getProjectName().isEmpty()) {
                    if (textField == null) createTextField();
                    textField.setText("");
                    textField.setPromptText("프로젝트명");
                    setText(null);
                    setGraphic(textField);
                    setContextMenu(null);
                } else if (item instanceof UserProjectsInfo projectInfo) {
                    HBox hbox = new HBox();
                    Label nameLabel = new Label(projectInfo.toString());
                    hbox.getChildren().add(nameLabel);

                    if (projectInfo.getIsShared() != null && projectInfo.getIsShared()) {
                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);
                        Label sharedLabel = new Label("(공유됨)");
                        sharedLabel.getStyleClass().add("shared-label");
                        hbox.getChildren().addAll(spacer, sharedLabel);
                    }
                    setGraphic(hbox);
                    setText(null);
                    if (!"Project".equals(projectInfo.getProjectName()) && !projectInfo.getProjectName().isEmpty()) {
                        setContextMenu(createProjectContextMenu(projectInfo, projectController, mainController, currentUserInfo));
                    } else {
                        setContextMenu(null);
                    }
                } else {
                    setText(item.toString());
                    setGraphic(null);
                    setContextMenu(null);
                }

                setOnMousePressed(event -> {
                    if (!isEditing() && event.getClickCount() == 2) {
                        if (getItem() instanceof UserProjectsInfo pInfo && !pInfo.getProjectName().isEmpty()) {
                            onDoubleClick.accept(pInfo);
                            event.consume();
                        }
                    }
                });
            }
        }

        private void createTextField() {
            textField = new TextField();
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && isEditing()) {
                    cancelEdit();
                }
            });
            textField.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    String newName = textField.getText().trim();
                    if (!newName.isEmpty()) {
                        commitEdit(new UserProjectsInfo(newName));
                        projectController.createProjectRequest(newName);
                        projectController.projectListRequest();
                    } else {
                        cancelEdit();
                    }
                } else if (event.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                }
            });
        }
    }

    private class EditableFileTreeCell extends TreeCell<Object> {
        private TextField textField;
        private final ProjectController projectController;
        private final MainController mainController;
        private final String projectId;
        private final String owner;
        private UserProjectsInfo userProjectsInfo;

        public EditableFileTreeCell(ProjectController projectController, UserProjectsInfo userProjectsInfo, MainController mainController) {
            this.projectController = projectController;
            this.projectId = userProjectsInfo.getProjectID();
            this.owner=userProjectsInfo.getOwner();
            this.mainController = mainController;
            this.userProjectsInfo=userProjectsInfo;
        }

        @Override
        public void startEdit() {
            super.startEdit();
            Object item = getItem();
            if (item instanceof NodeType node && node.toString().isEmpty()) {
                if (textField == null) {
                    createTextField();
                }
                setText(null);
                setGraphic(textField);
                textField.requestFocus();
                textField.selectAll();
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem() != null ? getItem().toString() : null);
            setGraphic(null);
            if (getItem() instanceof NodeType node && node.toString().isEmpty()) {
                Platform.runLater(() -> {
                    if (getTreeItem() != null && getTreeItem().getParent() != null)
                        getTreeItem().getParent().getChildren().remove(getTreeItem());
                });
            }
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setOnMousePressed(null);
            } else {
                if (isEditing() && item instanceof NodeType node && node.toString().isEmpty()) {
                    if (textField == null) {
                        createTextField();
                    }
                    textField.setText("");
                    textField.setPromptText(node.getType().equals("file")?"파일명":"폴더명");
                    setText(null);
                    setGraphic(textField);
                    setContextMenu(null);
                } else {
                    setText(item.toString());
                    setGraphic(null);
                    setContextMenu(createFileContextMenu());
                }

                setOnMousePressed(event -> {
                    if (!isEditing() && event.getClickCount() == 2) {
                        if (getItem() instanceof NodeType nodeType && "file".equals(nodeType.getType())) {
                            String path = getItemPath(getTreeItem());
                            mainController.openFileInEditor(path);
                            event.consume();
                        }
                    }
                });
            }
        }

        private void createTextField() {
            textField = new TextField();
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {if (!isNowFocused && isEditing()) cancelEdit();});
            textField.setOnKeyPressed(event -> {
                switch (event.getCode()) {
                    case ENTER -> {
                        String newName = textField.getText().trim();
                        if (!newName.isEmpty() && getItem() instanceof NodeType node) {
                            NodeType newNode = new NodeType(newName, node.getType());
                            commitEdit(newNode);
                            
                            Path parentPath = Paths.get(getItemPath(getTreeItem().getParent()));
                            Path fullPath = parentPath.resolve(newName);
                            String pathString = fullPath.toString().replace('\\', '/');

                            if ("file".equals(node.getType())){
                                projectController.addFileRequest(projectId, pathString, owner);
                            }
                            else if ("folder".equals(node.getType())){
                                projectController.addFolderRequest(projectId, pathString, owner);
                            }
                        } else {
                            cancelEdit();
                        }
                    }
                    case ESCAPE -> {
                        cancelEdit();
                    }
                    default -> {}
                }
            });
        }

        private ContextMenu createFileContextMenu() {
            ContextMenu contextMenu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("삭제");
            deleteItem.setOnAction(e -> {
                TreeItem<Object> treeItem = getTreeItem();
                if (treeItem != null && treeItem.getValue() instanceof NodeType nodeType) {
                    String itemType = nodeType.getType(); // "file" or "folder"
                    Path path = Paths.get(getItemPath(treeItem));
                    String pathString = path.toString().replace('\\', '/');
                    if(itemType.equals("file")){
                        projectController.delFileRequest(projectId, pathString, owner);
                        projectController.fileListRequest(userProjectsInfo);
                    }else if(itemType.equals("folder")){
                        projectController.delDirRequest(projectId, pathString, owner);
                        projectController.fileListRequest(userProjectsInfo);
                    }
                }
            });
            contextMenu.getItems().add(deleteItem);
            return contextMenu;
        }
    }
    private static class FileTreeItem extends TreeItem<Object> {
        public FileTreeItem(Object value) {
            super(value);
        }
        @Override
        public boolean isLeaf() {
            Object value = getValue();
            // 아이템의 값이 NodeType이고, 그 타입이 "folder" 라면,
            // 자식이 있든 없든 절대 leaf가 아니라고 반환합니다. (항상 확장 버튼 표시)
            if (value instanceof NodeType nodeType && "folder".equals(nodeType.getType())) {
                return false;
            }

            // 폴더가 아닌 경우(파일 등)에는 기본 동작을 따릅니다.
            // (자식이 없으면 leaf, 있으면 아님)
            return super.isLeaf();
        }
    }
}