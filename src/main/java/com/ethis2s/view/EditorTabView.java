package com.ethis2s.view;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.EditorSearchHandler;
import com.ethis2s.view.ProblemsView.Problem;
import com.ethis2s.view.editor.EditorFactory;
import com.ethis2s.view.editor.EditorStateManager;
import com.ethis2s.view.editor.TabDragDropManager;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Orientation;
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

    private final Set<TabPane> managedTabPanes = new HashSet<>();
    private TabPane activeTabPane;
    private CodeArea activeCodeArea;
    private final StringProperty activeTabTitle = new SimpleStringProperty("검색...");

    public EditorTabView(MainController mainController, SplitPane rootContainer) {
        this.mainController = mainController;
        this.rootContainer = rootContainer;
        
        this.stateManager = new EditorStateManager();
        this.editorFactory = new EditorFactory(mainController, stateManager, this);
        this.dragDropManager = new TabDragDropManager(this, rootContainer);
        this.searchHandler = new EditorSearchHandler(stateManager, mainController);
        
        TabPane primaryTabPane = createNewTabPane();
        this.rootContainer.getItems().add(primaryTabPane);
        this.activeTabPane = primaryTabPane;
    }
    
    // --- Public API ---

    public void openFileInEditor(String filePath, String content) {
        String tabId = "file-" + filePath;
        if (hasTab(tabId)) {
            selectTab(tabId);
            return;
        }

        Node editorContent = editorFactory.createEditorForFile(filePath, content, tabId);
        
        Runnable onClose = () -> {
            stateManager.unregisterTab(tabId);
            aggregateAndSendProblems(); // 문제가 있는 탭이 닫혔으므로 목록 업데이트
            Platform.runLater(this::checkAndCleanupAllPanes);
        };

        Tab newTab = createTab(tabId, null, editorContent, onClose);
        if (newTab == null) return;
        
        String fileName = Paths.get(filePath).getFileName().toString();
        HBox tabGraphic = createTabGraphic(fileName, newTab);
        newTab.setGraphic(tabGraphic);

        dragDropManager.registerDraggableTab(newTab, tabGraphic);
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
                String topTabsCss = ConfigManager.getInstance().getTopTabsThemePath();
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
            this.activeTabPane = primaryTabPane;
        }
    }

    // --- Search API (Delegation) ---
    public String getCurrentSelectedText() {
        return (activeCodeArea != null) ? activeCodeArea.getSelectedText() : "";
    }

    public void performSearchOnActiveTab(String query, boolean caseSensitive) {
        if (activeCodeArea != null) {
            searchHandler.performSearch(activeCodeArea, query, caseSensitive);
        }
    }

    public void goToNextMatchOnActiveTab() {
        if (activeCodeArea != null) searchHandler.goToNextMatch(activeCodeArea);
    }

    public void goToPreviousMatchOnActiveTab() {
        if (activeCodeArea != null) searchHandler.goToPreviousMatch(activeCodeArea);
    }

    // --- Internal Logic & Helpers ---

    private HBox createTabGraphic(String fileName, Tab tab) {
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.getStyleClass().add("tab-file-name");
        
        Label errorCountLabel = new Label("");
        errorCountLabel.getStyleClass().add("tab-error-count");
        errorCountLabel.setMinWidth(Region.USE_PREF_SIZE);
        
        HBox tabGraphic = new HBox(5, fileNameLabel, errorCountLabel);
        tabGraphic.setAlignment(Pos.CENTER_LEFT);
        tabGraphic.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

        ContextMenu contextMenu = new ContextMenu();
        MenuItem splitRight = new MenuItem("우측으로 분할");
        splitRight.setOnAction(e -> dragDropManager.splitTab(tab, Orientation.HORIZONTAL));
        MenuItem splitDown = new MenuItem("하단으로 분할");
        splitDown.setOnAction(e -> dragDropManager.splitTab(tab, Orientation.VERTICAL));
        contextMenu.getItems().addAll(splitRight, splitDown);
        
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

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (tabPane.isFocused() || activeTabPane == tabPane) updateSearchPrompt(newTab);
        });
        tabPane.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                activeTabPane = tabPane;
                updateSearchPrompt(tabPane.getSelectionModel().getSelectedItem());
            }
        });

        try {
            String css = ConfigManager.getInstance().getTopTabsThemePath();
            if (css != null) tabPane.getStylesheets().add(css);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        if (activeTabPane == pane) activeTabPane = managedTabPanes.stream().findFirst().orElse(null);

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

    // createTab 메소드를 약간 수정하여 그래픽이 없는 일반 탭도 지원하도록 합니다.
    private Tab createTab(String tabId, String title, Node content, Runnable customOnClose) {
        if (hasTab(tabId)) {
            selectTab(tabId);
            return findTabById(tabId).orElse(null);
        }
        // title이 null이 아니면 텍스트를 사용하고, null이면 그래픽을 사용하도록 가정
        Tab newTab = (title != null) ? new Tab(title, content) : new Tab(null, content);
        newTab.setId(tabId);
        newTab.setClosable(true);
        newTab.setOnClosed(e -> {
            if (customOnClose != null) customOnClose.run();
            // 파일 에디터가 아닌 일반 탭이 닫힐 때도 정리 로직 호출
            Platform.runLater(this::checkAndCleanupAllPanes);
        });

        TabPane targetPane = (activeTabPane != null) ? activeTabPane : managedTabPanes.stream().findFirst().orElse(null);
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
}