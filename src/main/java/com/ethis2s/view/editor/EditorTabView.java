package com.ethis2s.view.editor;

import com.ethis2s.controller.MainController;
import com.ethis2s.controller.ProjectController;
import com.ethis2s.service.TabDragDropManager;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.EditorSearchHandler;
import com.ethis2s.util.EditorStateManager;
import com.ethis2s.util.TabPaneFocusManager;
import com.ethis2s.view.ProblemsView.Problem;
import com.ethis2s.model.UserProjectsInfo;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.TabPane.TabDragPolicy;
import javafx.scene.layout.*;
import org.fxmisc.richtext.CodeArea;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 실시간 동시 편집 클라이언트 구현 가이드 (Javadoc 생략)
 *
 * 이 클래스는 에디터 UI의 레이아웃을 구성하고,
 * 각 기능(상태 관리, 에디터 생성, 드래그앤드롭, 검색)을 담당하는
 * 헬퍼 클래스들을 조정(coordinate)하는 역할을 합니다.
 */
public class EditorTabView {

    private final MainController mainController;
    private final SplitPane rootContainer;
    
    private final EditorStateManager stateManager;
    private final EditorFactory editorFactory;
    private final TabDragDropManager dragDropManager;
    private final EditorSearchHandler searchHandler;
    private final TabPaneFocusManager focusManager;


    private final Set<TabPane> managedTabPanes = new HashSet<>();
    private CodeArea activeCodeArea;
    private final StringProperty activeTabTitle = new SimpleStringProperty("검색...");

    public EditorTabView(MainController mainController, SplitPane rootContainer) {
        this.mainController = mainController;
        this.rootContainer = rootContainer;
        
        this.stateManager = new EditorStateManager();
        this.editorFactory = new EditorFactory(mainController, stateManager, this, mainController.getProjectController());
        this.dragDropManager = new TabDragDropManager(this, rootContainer);
        this.searchHandler = new EditorSearchHandler(stateManager, mainController);
        this.focusManager = new TabPaneFocusManager(mainController, this, stateManager, managedTabPanes);
        
        TabPane primaryTabPane = createNewTabPane();
        this.rootContainer.getItems().add(primaryTabPane);
        focusManager.setActiveTabPane(primaryTabPane);
    }
    
    // --- Public API ---

    public void openFileInEditor(String filePath, String content, long initialVersion) {
        String tabId = "file-" + filePath;
        if (hasTab(tabId)) {
            selectTab(tabId);
            return;
        }

        Optional<UserProjectsInfo> projectInfoOpt = mainController.getCurrentActiveProject();

        Node editorContent = editorFactory.createEditorForFile(filePath, content, tabId, initialVersion);

        // Finalize initialization for this tab
        stateManager.setInitializing(tabId, false);
        stateManager.processPendingUpdates(tabId);
        String fileName = Paths.get(filePath).getFileName().toString();

        Tab newTab = createTab(tabId, null, editorContent, null);
        newTab.setOnClosed(e -> {
            stateManager.unregisterTab(tabId);
            aggregateAndSendProblems(); // 문제가 있는 탭이 닫혔으므로 목록 업데이트
            ProjectController projectController= mainController.getProjectController();
            projectController.closeFileRequest(projectInfoOpt.orElseThrow(),filePath);
            Platform.runLater(this::checkAndCleanupAllPanes);
            
        });

        if (newTab == null) return;

        projectInfoOpt.ifPresent(newTab::setUserData);

        HBox tabGraphic = createTabGraphic(fileName, newTab);
        newTab.setGraphic(tabGraphic);

        dragDropManager.registerDraggableTab(newTab, tabGraphic);
    }
    
    public void updateLineLockIndicator(String filePath, int line, String userId, String userNickname) {
        String tabId = "file-" + filePath;
        stateManager.updateLineLock(tabId, line, userId, userNickname);

        stateManager.getCodeArea(tabId).ifPresent(codeArea -> {
            // Force the paragraph graphic factory to be re-applied, thus redrawing the line numbers
            codeArea.setParagraphGraphicFactory(codeArea.getParagraphGraphicFactory());
        });
    }

    public void updateUserCursor(String filePath, String userId, String userNickname, int position) {
        String tabId = "file-" + filePath;
        stateManager.getCursorManager(tabId).ifPresent(cursorManager -> {
            cursorManager.updateCursor(userId, userNickname, position);
        });
    }

    public void navigateTo(String filePath, int line, int column) {
        String tabId = "file-" + filePath;
        if (!hasTab(tabId)) return;

        selectTab(tabId);
        stateManager.getCodeArea(tabId).ifPresent(codeArea -> {
            codeArea.moveTo(line - 1, column);
            codeArea.requestFollowCaret();
            codeArea.requestFocus();
        });
    }
    
    public void reapplyAllEditorSettings() {
        editorFactory.reapplyStylesToAllEditors();
        for (TabPane pane : managedTabPanes) {
            try {
                String topTabsCss = ConfigManager.getInstance().getThemePath("design","topTabsTheme");
                pane.getStylesheets().clear();
                if (topTabsCss != null) {
                    pane.getStylesheets().add(topTabsCss);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void shutdownAllManagers() {
        stateManager.shutdownAllManagers();
    }

    public boolean hasTabsFromOtherProjects(UserProjectsInfo selectedProject) {
        if (selectedProject == null) return false;

        for (TabPane pane : managedTabPanes) {
            for (Tab tab : pane.getTabs()) {
                Object userData = tab.getUserData();
                if (userData instanceof UserProjectsInfo tabProjectInfo) {
                    if (!tabProjectInfo.getProjectID().equals(selectedProject.getProjectID())) {
                        return true; // 다른 프로젝트 탭을 발견하면 즉시 true 반환
                    }
                }
            }
        }
        return false; // 검사가 끝날 때까지 다른 프로젝트 탭이 없으면 false 반환
    }
    
    public void closeTabsBelongingToOtherProjects(UserProjectsInfo selectedProject) {
        if (selectedProject == null) return;

        List<Tab> tabsToClose = new ArrayList<>();
        for (TabPane pane : managedTabPanes) {
            for (Tab tab : pane.getTabs()) {
                Object userData = tab.getUserData();
                if (userData instanceof UserProjectsInfo tabProjectInfo) {
                    if (!tabProjectInfo.getProjectID().equals(selectedProject.getProjectID())) {
                        tabsToClose.add(tab);
                    }
                }
            }
        }

        // 순회가 끝난 후 탭을 제거합니다.
        for (Tab tab : tabsToClose) {
            if (tab.getTabPane() != null) {
                tab.getTabPane().getTabs().remove(tab);
            }
        }

        // 모든 탭을 닫은 후, 빈 TabPane이 남아있을 수 있으므로 정리 로직을 호출합니다.
        Platform.runLater(this::checkAndCleanupAllPanes);
    }
    
    public void closeAllClosableTabs() {
        shutdownAllManagers();
        new ArrayList<>(managedTabPanes).forEach(pane -> 
            new ArrayList<>(pane.getTabs()).forEach(tab -> {
                if (tab.isClosable()) pane.getTabs().remove(tab);
            })
        );
        checkAndCleanupAllPanes();
        if (managedTabPanes.isEmpty()) {
            TabPane primaryTabPane = createNewTabPane();
            this.rootContainer.getItems().add(primaryTabPane);
            focusManager.setActiveTabPane(primaryTabPane);
        }
    }

    // --- Search API (Delegation) ---
    public String getActiveCodeAreaHash() {return activeCodeArea != null ? Integer.toHexString(System.identityHashCode(activeCodeArea)) : "null";}
    public String getCurrentSelectedText() {return (activeCodeArea != null) ? activeCodeArea.getSelectedText() : "";}
    public void performSearchOnActiveTab(String query, boolean caseSensitive) {if (activeCodeArea != null) searchHandler.performSearch(activeCodeArea, query, caseSensitive);}
    public void goToNextMatchOnActiveTab() {if (activeCodeArea != null) searchHandler.goToNextMatch(activeCodeArea);}
    public void goToPreviousMatchOnActiveTab() {if (activeCodeArea != null) searchHandler.goToPreviousMatch(activeCodeArea);}
    public void clearSearchHighlights() {performSearchOnActiveTab("", false);}

    // --- Internal Logic & Helpers ---

    private HBox createTabGraphic(String fileName, Tab tab) {
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.getStyleClass().add("tab-file-name");
        
        // [수정] 파일 에디터 탭에만 에러 카운터 표시
        Node errorCounter = new Pane(); // 기본적으로 빈 공간
        if (tab.getId() != null && tab.getId().startsWith("file-")) {
            Label errorCountLabel = new Label("");
            errorCountLabel.getStyleClass().add("tab-error-count");
            errorCountLabel.setMinWidth(Region.USE_PREF_SIZE);
            errorCounter = errorCountLabel;
        }
        
        HBox tabGraphic = new HBox(5, fileNameLabel, errorCounter);
        tabGraphic.setAlignment(Pos.CENTER_LEFT);
        tabGraphic.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

        // --- [핵심 수정] 컨텍스트 메뉴 구조 변경 (하위 메뉴 사용) ---
        ContextMenu contextMenu = new ContextMenu();
        
        // 1. "창 분할" 하위 메뉴 생성
        Menu splitMenu = new Menu("창 분할");
        
        // 2. 각 분할 메뉴 아이템 생성
        MenuItem splitRight = new MenuItem("우측으로 분할");
        splitRight.setOnAction(e -> dragDropManager.splitTab(tab, TabDragDropManager.SplitDirection.RIGHT));

        MenuItem splitLeft = new MenuItem("좌측으로 분할");
        splitLeft.setOnAction(e -> dragDropManager.splitTab(tab, TabDragDropManager.SplitDirection.LEFT));
        
        MenuItem splitDown = new MenuItem("하단으로 분할");
        splitDown.setOnAction(e -> dragDropManager.splitTab(tab, TabDragDropManager.SplitDirection.BOTTOM));

        MenuItem splitUp = new MenuItem("상단으로 분할");
        splitUp.setOnAction(e -> dragDropManager.splitTab(tab, TabDragDropManager.SplitDirection.TOP));
        
        // 3. 분할 메뉴 아이템들을 "창 분할" 하위 메뉴에 추가
        splitMenu.getItems().addAll(splitUp, splitDown, splitLeft, splitRight);

        // 4. "창 닫기" 메뉴 아이템 생성
        MenuItem closeTabItem = new MenuItem("창 닫기");
        closeTabItem.setOnAction(e -> {
            // 탭이 속한 TabPane을 찾아서 탭을 제거
            if (tab.getTabPane() != null) {
                tab.getTabPane().getTabs().remove(tab);
            }
        });
        // 설정 탭과 같이 닫을 수 없는 탭은 "창 닫기" 메뉴를 비활성화
        closeTabItem.setDisable(!tab.isClosable());

        // 5. 메인 컨텍스트 메뉴에 하위 메뉴와 다른 메뉴들을 추가
        contextMenu.getItems().addAll(splitMenu, new SeparatorMenuItem(), closeTabItem);
        
        tabGraphic.setOnContextMenuRequested(e -> contextMenu.show(tabGraphic, e.getScreenX(), e.getScreenY()));

        return tabGraphic;
    }

    public void handleErrorUpdate(String tabId, String fileName, List<SyntaxError> errors) {
        stateManager.updateErrorsForTab(tabId, errors);
        aggregateAndSendProblems();

        findTabById(tabId).ifPresent(tab -> {
            if (tab.getGraphic() instanceof HBox hbox && hbox.lookup(".tab-error-count") instanceof Label errorLabel) {
                if (errors.isEmpty()) {
                    errorLabel.setText("");
                    errorLabel.getStyleClass().remove("has-errors");
                } else {
                    String errorText = errors.size() > 9 ? "9+" : String.valueOf(errors.size());
                    errorLabel.setText(errorText);
                    if (!errorLabel.getStyleClass().contains("has-errors")) {
                        errorLabel.getStyleClass().add("has-errors");
                    }
                }
            }
        });

        stateManager.getCodeArea(tabId).ifPresent(ca -> ca.setParagraphGraphicFactory(ca.getParagraphGraphicFactory()));
    }
    
    private void aggregateAndSendProblems() {
        List<Problem> allProblems = new ArrayList<>();
        stateManager.getAllTabErrors().forEach((tabId, errors) -> {
            String filePath = tabId.startsWith("file-") ? tabId.substring(5) : null;
            if (filePath != null) {
                stateManager.getFileName(tabId).ifPresent(fileName ->
                    errors.forEach(error -> allProblems.add(new Problem(filePath, fileName, error)))
                );
            }
        });
        mainController.updateProblems(allProblems);
    }
    
    public TabPane createNewTabPane() {
        TabPane tabPane = new TabPane();
        tabPane.setTabDragPolicy(TabDragPolicy.REORDER);
        managedTabPanes.add(tabPane);
        dragDropManager.registerDropTarget(tabPane);
        focusManager.registerTabPane(tabPane);
        
        try {
            String css = ConfigManager.getInstance().getThemePath("design","topTabsTheme");
            if (css != null) tabPane.getStylesheets().add(css);
        } catch (Exception e) {
            e.printStackTrace();
        }
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            updateRunButtonVisibility();
        });
        return tabPane;
    }

    private boolean hasTab(String tabId) {
        return managedTabPanes.stream().anyMatch(pane -> pane.getTabs().stream().anyMatch(tab -> tabId.equals(tab.getId())));
    }
    
    private void selectTab(String tabId) {
        for (TabPane pane : managedTabPanes) {
            pane.getTabs().stream().filter(tab -> tabId.equals(tab.getId())).findFirst().ifPresent(tab -> {
                pane.getSelectionModel().select(tab);
                pane.requestFocus();
            });
        }
    }

    private Optional<Tab> findTabById(String tabId) {
        return managedTabPanes.stream()
            .flatMap(pane -> pane.getTabs().stream())
            .filter(tab -> tabId.equals(tab.getId()))
            .findFirst();
    }
    
    public void checkAndCleanupAllPanes() {
        new ArrayList<>(managedTabPanes).forEach(pane -> {
            if (pane.getTabs().isEmpty() && managedTabPanes.size() > 1) {
                cleanupEmptyPane(pane);
            }
        });
    }

    private void cleanupEmptyPane(TabPane pane) {
        Parent container = dragDropManager.findContainerOf(pane);
        managedTabPanes.remove(pane);
        if (focusManager.getActiveTabPane() == pane) {
            focusManager.setActiveTabPane(managedTabPanes.stream().findFirst().orElse(null));
        }

        if (container instanceof SplitPane parentSplitPane) {
            parentSplitPane.getItems().remove(pane);
            if (parentSplitPane.getItems().size() == 1) {
                Node survivor = parentSplitPane.getItems().get(0);
                if (dragDropManager.findContainerOf(parentSplitPane) instanceof SplitPane grandParent) {
                    int parentIndex = grandParent.getItems().indexOf(parentSplitPane);
                    if (parentIndex != -1) grandParent.getItems().set(parentIndex, survivor);
                }
            }
        }
    }

    public void updateRunButtonVisibility() {
        if (mainController == null || mainController.getMainScreen() == null) {
            return;
        }

        TabPane activePane = focusManager.getActiveTabPane();
        boolean isVisible = true; // 기본적으로 버튼을 보이게 설정
        if (activePane != null) {
            Tab selectedTab = activePane.getSelectionModel().getSelectedItem();
            if (selectedTab != null && selectedTab.getId() != null) {
                String tabId = selectedTab.getId();
                // 로그인 탭 또는 회원가입 탭일 경우에만 숨김
                if ("login-tab".equals(tabId) || "register-tab".equals(tabId)) {
                    isVisible = false;
                }
            } else {
                // 선택된 탭이 없으면(모든 탭이 닫혔을 때) 버튼을 숨김
                isVisible = false;
            }
        } else {
            // 활성화된 Pane이 없어도 버튼을 숨김
            isVisible = false;
        }
        mainController.getMainScreen().setRunButtonVisible(isVisible);
    }

    private void updateSearchPrompt(Tab tab) {
        if (tab != null && tab.getGraphic() instanceof HBox hbox && hbox.getChildren().get(0) instanceof Label label) {
            activeTabTitle.set(label.getText() + "에서 검색");
        } else {
            activeTabTitle.set("검색...");
        }
    }
    
    public void setActiveCodeArea(CodeArea codeArea) {
        this.activeCodeArea = codeArea;
        findTabById(stateManager.findTabIdForCodeArea(codeArea).orElse("")).ifPresent(this::updateSearchPrompt);
    
        // [BUG FIX] CodeArea가 직접 포커스를 받았을 때도 자동 재검색을 트리거한다.
        String query = mainController.getSearchQuery();
        if (query != null && !query.isEmpty()) mainController.triggerSearch();
        
    }

    public Optional<CodeArea> getActiveCodeArea() {
        return Optional.ofNullable(activeCodeArea);
    }

    // --- 탭 관리를 위한 새로운 Public API ---

    /**
     * 지정된 탭을 열거나, 이미 열려있으면 선택합니다.
     * 뷰(Node) 생성을 위한 로직은 Supplier를 통해 지연 실행(lazy execution)됩니다.
     * @param tabId 탭을 식별하는 고유 ID
     * @param title 탭에 표시될 제목
     * @param isClosable 탭을 닫을 수 있는지 여부
     * @param contentSupplier 탭 내용(Node)을 생성하는 로직
     * @param onClose 탭이 닫힐 때 실행될 콜백 (선택 사항)
     */
    private void showTab(String tabId, String title, boolean isClosable, Supplier<Node> contentSupplier, Runnable onClose) {
        if (hasTab(tabId)) {
            selectTab(tabId);
            return;
        }
        Node content = contentSupplier.get(); // 탭이 실제로 생성될 때만 content를 만듭니다.
        Tab newTab = createTab(tabId, title, content, onClose);
        if (newTab != null) {
            newTab.setClosable(isClosable);
        }
    }

    // 로그인 뷰를 표시하는 전용 메소드
    public void showLoginView(Supplier<Node> contentSupplier) {
        closeTab("register-tab"); // 회원가입 탭이 있다면 닫습니다.
        showTab("login-tab", "로그인", false, contentSupplier, null);
    }

    // 회원가입 뷰를 표시하는 전용 메소드
    public void showRegisterView(Supplier<Node> contentSupplier) {
        closeTab("login-tab"); // 로그인 탭이 있다면 닫습니다.
        showTab("register-tab", "회원가입", false, contentSupplier, null);
    }

    // 프로젝트 속성 뷰를 표시하는 전용 메소드
    public void showProjectPropertiesView(String projectId, String projectName, Supplier<Node> contentSupplier) {
        String tabId = "properties-" + projectId;
        String title = projectName + " 속성";
        showTab(tabId, title, true, contentSupplier, null);
    }

    // 공유 옵션 뷰를 표시하는 전용 메소드
    public void showSharedOptionView(String projectId, String projectName, Supplier<Node> contentSupplier, Runnable onClose) {
        String tabId = "share-" + projectId;
        String title = projectName + " 공유";
        showTab(tabId, title, true, contentSupplier, onClose);
    }

    // 설정 뷰를 표시하는 전용 메소드
    public void showSettingsView(Supplier<Node> contentSupplier) {
        showTab("settings-tab", "설정", true, contentSupplier, null);
    }

    // 탭을 닫는 public 메소드 (기존에 private이었다면 public으로 변경)
    public void closeTab(String tabId) {
        for (TabPane pane : managedTabPanes) {
            pane.getTabs().stream()
                .filter(tab -> tabId.equals(tab.getId()))
                .findFirst()
                .ifPresent(tab -> pane.getTabs().remove(tab));
        }
    }

    private Tab createTab(String tabId, String title, Node content, Runnable customOnClose) {
        if (hasTab(tabId)) {
            selectTab(tabId);
            return findTabById(tabId).orElse(null);
        }

        // [핵심 수정] 이제 title이 있든 없든 항상 Tab을 먼저 만들고, 그래픽을 설정합니다.
        Tab newTab = new Tab(null, content); // 제목은 그래픽에서 처리하므로 null로 시작
        newTab.setId(tabId);
        newTab.setClosable(true);
        newTab.setOnClosed(e -> {
            if (customOnClose != null) customOnClose.run();
            Platform.runLater(this::checkAndCleanupAllPanes);
        });

        // 파일 에디터가 아니면 tabId("login-tab")를, 파일 에디터면 title(파일명)을 사용
        String tabTitle = (title != null) ? title : Paths.get(tabId.substring(5)).getFileName().toString();
        
        // 모든 탭에 대해 그래픽 헤더를 생성하고 분할 기능을 등록합니다.
        HBox tabGraphic = createTabGraphic(tabTitle, newTab);
        newTab.setGraphic(tabGraphic);
        dragDropManager.registerDraggableTab(newTab, tabGraphic); // 드래그 기능도 모든 탭에 등록

        // 탭을 TabPane에 추가
        TabPane targetPane = (focusManager.getActiveTabPane() != null) ? focusManager.getActiveTabPane() : managedTabPanes.stream().findFirst().orElse(null);
        if (targetPane != null) {
            targetPane.getTabs().add(newTab);
            targetPane.getSelectionModel().select(newTab);
            targetPane.requestFocus();
        }
        
        return newTab;
    }
    
    // --- Property Getters ---
    public StringProperty activeTabTitleProperty() { return activeTabTitle; }
    public IntegerProperty totalMatchesProperty() { return stateManager.totalMatchesProperty(); }
    public IntegerProperty currentMatchIndexProperty() { return stateManager.currentMatchIndexProperty(); }
    public EditorStateManager getStateManager() { return stateManager; }
}