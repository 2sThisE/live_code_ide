package com.ethis2s.view;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import com.ethis2s.controller.MainController;
import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.service.ExecutionService;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.MacosNativeUtil;

import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.geometry.Insets;
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
    private HBox statusBar;
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
    private RunView runView;
    private OutputView outputView;
    private Tab problemsTab;
    private Label problemsTabLabel;
    private TabPane bottomTabPane; // TabPane을 필드로 선언해서 접근 가능하게 할게요!
    private Button runButton; // [추가] 실행 버튼 필드
    private ToggleButton pauseOTButton;
    private TextField searchField;
    private HBox searchBox;
    private Button prevButton;
    private Button nextButton;
    private ToggleButton caseSensitiveCheck;
    private Label resultLabel;
    private List<Node> nonDraggableNodes;
    private Button minimizeButton;
    private Button maximizeButton;
    private Button windowCloseButton;
    private MenuBar menuBar;
    private StackPane topPane;


    public HBox getStatusBar() {return statusBar;}

    // [추가] 외부에서 실행 버튼의 가시성을 제어하는 메소드
    public void setRunButtonVisible(boolean visible) {
        if (runButton != null) {
            runButton.setVisible(visible);
        }
    }

    // [추가] 외부에서 OT 일시정지 버튼의 가시성을 제어하는 메소드
    public void setPauseOTButtonVisible(boolean visible) {
        if (pauseOTButton != null) pauseOTButton.setVisible(visible);
    }
 
    // [추가] 외부에서 OT 일시정지 버튼 객체를 가져오는 메소드
    public ToggleButton getPauseOTButton() {
         return pauseOTButton;
    }

    public void updateProblemsTab(int errorCount) {
        // 이제 Tab 객체가 있는지 직접 확인합니다.
        if (problemsTab == null) return;

        Platform.runLater(() -> {
            if (errorCount > 0) {
                // 2. Tab의 스타일 클래스를 직접 추가합니다.
                if (!problemsTab.getStyleClass().contains("tab-error")) {
                    problemsTab.getStyleClass().add("tab-error");
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
    public List<Node> getNonDraggableNodes() { return nonDraggableNodes; }
    public StackPane getTopPane() { return topPane; }
    public Button getMinimizeButton() { return minimizeButton; }
    public Button getMaximizeButton() { return maximizeButton; }
    public Button getWindowCloseButton() { return windowCloseButton; }
    public MenuBar getMenuBar() { return menuBar; }
    

    public BorderPane createMainScreen(Stage stage, Node editorLayout, Label statusLabel, MainController mainController) {
        this.nonDraggableNodes = new ArrayList<>();
        BorderPane mainLayout = new BorderPane(); // This will be the absolute root.
        
        // this.editorArea는 이제 EditorTabView 내부에서 관리되므로 직접 참조할 필요가 없습니다.
        // this.editorArea.setStyle("-fx-background-color: transparent;"); 
        
        this.menuBar = new MenuBar();
        nonDraggableNodes.add(menuBar);
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
        nonDraggableNodes.add(searchBox);
        searchBox.getStyleClass().add("centered-search-field"); // Use existing style
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setSpacing(5);
        searchBox.setMinWidth(100);
        searchBox.setMaxWidth(500);
        
        // 2. Create the actual transparent TextField
        this.searchField = new TextField();
        searchField.setPromptText("검색...");
        searchField.getStyleClass().add("transparent-textfield"); // New style for transparency
        searchField.setMinWidth(Region.USE_PREF_SIZE); // 또는 searchField.setMinWidth(0);
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
        
        if (isMac) {
            // 2. [핵심] searchBox를 감싸고 '안전 영역'을 만들어 줄 BorderPane 컨테이너를 생성합니다.
            BorderPane searchBoxContainer = new BorderPane();
            Region backgroundBar = new Region();
            // 3. [가장 중요] 컨테이너의 왼쪽에 '신호등 버튼'이 위치할 공간만큼 패딩을 줍니다.
            //    이 값(70px)은 실험을 통해 가장 보기 좋은 값으로 조절할 수 있습니다.
            searchBoxContainer.setPadding(new Insets(0, 70, 0, 70));
            
            // 4. searchBox를 이 컨테이너의 'Center' 영역에 배치합니다.
            searchBoxContainer.setCenter(searchBox);
            BorderPane.setAlignment(searchBox, Pos.CENTER);
            
            // 5. 최종 topPane은 StackPane이 되어, 배경 위에 searchBox 컨테이너를 올립니다.
            this.topPane = new StackPane(backgroundBar, searchBoxContainer);

        } else {
            
            // Windows/Other Style Title Bar (Original Implementation)
            
            this.minimizeButton = new Button("—");
            minimizeButton.getStyleClass().add("window-button");
            minimizeButton.setOnAction(e -> stage.setIconified(true));
            nonDraggableNodes.add(minimizeButton);

            this.maximizeButton = new Button("◻");
            maximizeButton.getStyleClass().add("window-button");
            maximizeButton.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
            nonDraggableNodes.add(maximizeButton);

            this.windowCloseButton = new Button("✕");
            windowCloseButton.getStyleClass().add("close-button");
            windowCloseButton.setOnAction(e -> Platform.exit());
            nonDraggableNodes.add(windowCloseButton);
            HBox windowButtons = new HBox(minimizeButton, maximizeButton, windowCloseButton);
            windowButtons.setAlignment(Pos.CENTER);
            Region fakeMenuBar = new Region();
            fakeMenuBar.prefWidthProperty().bind(menuBar.widthProperty());
            
            Region fakeWindowButtons = new Region();
            fakeWindowButtons.prefWidthProperty().bind(windowButtons.widthProperty());

            // 3. BorderPane의 Left와 Right에 실제 UI와 가짜 쌍둥이를 함께 넣습니다.
            HBox leftGroup = new HBox(menuBar, fakeWindowButtons);
            leftGroup.setAlignment(Pos.CENTER_LEFT);
            leftGroup.setPickOnBounds(false); // 투명한 부분이 클릭을 막지 않도록

            HBox rightGroup = new HBox(fakeMenuBar, windowButtons);
            rightGroup.setPickOnBounds(false);
            
            // 4. 최종 타이틀 바 레이아웃을 BorderPane으로 잡습니다.
            BorderPane titleBarLayout = new BorderPane();
            titleBarLayout.setLeft(leftGroup);
            titleBarLayout.setCenter(searchBox);
            titleBarLayout.setRight(rightGroup);
            
            // 5. searchBox를 Center 영역의 중앙에 정렬합니다.
            BorderPane.setAlignment(searchBox, Pos.CENTER);

            // 6. 최종 topPane은 이 BorderPane을 담는 StackPane이 됩니다.
            this.topPane = new StackPane(titleBarLayout);
        }
        

        topPane.getStyleClass().add("custom-title-bar");
        // =================================================================
        // DEBUG: JavaFX가 드래그 이벤트를 소비하는지 확인하기 위한 핸들러
        
        // =================================================================

        // --- Native Dragging Logic for macOS ---
        if (isMac) {
            topPane.setOnMousePressed(event -> {
                // searchBox 내부 클릭 시 드래그 방지
                if (event.getTarget() instanceof Node) {
                    Node target = (Node) event.getTarget();
                    if (searchBox.equals(target) || searchBox.getChildren().contains(target)) {
                        return;
                    }
                }
                // 네이티브 드래그 시작
                MacosNativeUtil.performNativeWindowDrag(event, stage);
            });
        }

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

        // 'Run' 탭 생성
        this.runView = new RunView();
        Tab runTab = new Tab("RUN");
        runTab.setContent(runView.getView());
        runTab.setClosable(false);
        

        bottomTabPane.getTabs().addAll(outputTab, debugTab, problemsTab, runTab);
        // --- 하단 탭 패널 생성 끝 ---

        // --- [핵심 수정] 실행 버튼을 위한 StackPane 레이어 ---
        // 1. 실행 버튼 생성 및 설정
        this.runButton = new Button("▶");
        runButton.getStyleClass().add("run-button");
        runButton.setMouseTransparent(false); // 버튼 자신은 클릭 이벤트를 받아야 함
        runButton.setVisible(false); // 기본적으로 숨김
        
        this.pauseOTButton = new ToggleButton("⏸"); // Pause 아이콘 사용
        pauseOTButton.getStyleClass().add("pause-ot-button");
        pauseOTButton.setMouseTransparent(false);
        pauseOTButton.setVisible(false);

        HBox buttonBox = new HBox(5, pauseOTButton, runButton); // 5px 간격
        buttonBox.setAlignment(Pos.TOP_RIGHT); // 내부 아이템들을 오른쪽으로 정렬
        buttonBox.setPadding(new javafx.geometry.Insets(5));
        buttonBox.setPickOnBounds(false);
        
        // 3. 기존 editorLayout과 버튼을 StackPane에 담기
        StackPane editorStack = new StackPane();
        editorStack.getStyleClass().add("root-pane");
        editorStack.getChildren().addAll(editorLayout, buttonBox); // [오류 수정] this.editorArea -> editorLayout
        StackPane.setAlignment(buttonBox, Pos.TOP_RIGHT);
        // --- 핵심 수정 끝 ---

        SplitPane centerSplit = new SplitPane(editorStack, bottomTabPane);
        centerSplit.setOrientation(Orientation.VERTICAL);
        centerSplit.setDividerPositions(0.75);

        SplitPane mainSplit = new SplitPane(fileExplorerContainer, centerSplit);
        mainSplit.getStyleClass().add("main-split-pane");
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
        statusBar = new HBox(safeStatusLabel, statusBarSpacer, antlrIndicator, connectionStatusLabel);
        statusBar.setSpacing(5);
        statusBar.setAlignment(Pos.CENTER_RIGHT);

        statusBar.getStyleClass().add("status-bar");
        contentPane.setBottom(statusBar);

        try {
            com.ethis2s.util.ConfigManager configManager = com.ethis2s.util.ConfigManager.getInstance();
            String treeViewCss = configManager.getThemePath("design","treeViewTheme");
            String topTabsCss = configManager.getThemePath("design","topTabsTheme");
            String bottomTabsCss = configManager.getThemePath("design","bottomTabsTheme");

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

    public Button getRunButton() {
        return runButton;
    }

    public void reloadComponentCss() {
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            String treeViewCss = configManager.getThemePath("design","treeViewTheme");
            String topTabsCss = configManager.getThemePath("design","topTabsTheme");
            String bottomTabsCss = configManager.getThemePath("design","bottomTabsTheme");

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
        // 이제 이 메소드는 컨트롤러에 프로젝트 변경을 '요청'하는 역할만 합니다.
        mainController.requestProjectSwitch(userProjectsInfo, projectController);
    }

    /**
     * 컨트롤러의 확인 절차가 끝난 후, 실제 파일 트리 뷰로 전환하는 메소드입니다.
     * @param userProjectsInfo 표시할 프로젝트 정보
     * @param projectController 프로젝트 컨트롤러
     * @param mainController 메인 컨트롤러
     */
    public void switchToProjectDirView(UserProjectsInfo userProjectsInfo, ProjectController projectController, MainController mainController) {
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
                            projectController.fileContentRequest(userProjectsInfo, path);
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
    public Optional<UserProjectsInfo> getCurrentProjectForFileTree() {
        return Optional.ofNullable(currentProjectForFileTree);
    }
    public ReadOnlyDoubleProperty searchBoxWidthProperty() {
        // HBox는 Region의 하위 클래스이므로, widthProperty()를 가지고 있습니다.
        return searchBox.widthProperty();
    }

    public void switchToTab(String tabName) {
        if (bottomTabPane != null) {
            for (Tab tab : bottomTabPane.getTabs()) {
                if (tabName.equals(tab.getText())) {
                    Platform.runLater(() -> bottomTabPane.getSelectionModel().select(tab));
                    break;
                }
            }                                                                         
        }
    }
    public RunView getRunView(){
        return runView;
    }
}