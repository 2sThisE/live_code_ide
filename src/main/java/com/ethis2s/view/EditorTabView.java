package com.ethis2s.view;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.HybridManager;
import com.ethis2s.view.ProblemsView.Problem;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.control.TabPane.TabDragPolicy;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.scene.layout.BorderPane;
import com.ethis2s.util.Tm4eSyntaxHighlighter.StyleToken;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.geometry.Bounds;


/**
 * 클라이언트와의 개별 세션을 처리하는 클래스입니다.
 * 이 클래스는 클라이언트 개발자를 위한 실시간 동시 편집 프로토콜 가이드를 포함합니다.
 *
 * ## 실시간 동시 편집 클라이언트 구현 가이드 ##
 *
 * 클라이언트는 아래의 절차에 따라 서버와 통신하여 실시간 동시 편집 기능을 구현해야 합니다.
 * 모든 통신 페이로드는 JSON 형식을 사용합니다.
 *
 * ### 1단계: 파일 열기 요청
 * 사용자가 파일을 열 때, 서버에 파일의 전체 내용을 요청합니다.
 * - **프로토콜**: `UF_FILE_CONTENT_REQUEST`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "owner": "프로젝트_소유자_ID",
 *     "path": "열고자_하는_파일_경로"
 *   }
 *   ```
 * - **서버 응답**: `UF_FILE_CONTENT_RESPONSE` 프로토콜과 함께 파일의 전체 내용(`content`)을 받습니다.
 * - **클라이언트 동작**: 받은 `content`를 에디터에 표시합니다. 이 시점부터 서버는 해당 클라이언트를 파일의 '편집자'로 인식합니다.
 *
 * ### 2단계: 라인 락(Lock) 획득
 * 사용자가 특정 라인을 편집하기 **직전** (예: 키보드 입력 시작, 붙여넣기 시도), 반드시 해당 라인에 대한 락을 요청해야 합니다.
 * - **프로토콜**: `UF_LINE_LOCK_REQUEST`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "owner": "프로젝트_소유자_ID",
 *     "path": "현재_파일_경로",
 *     "lineNumber": 1 // 1부터 시작하는 라인 번호
 *   }
 *   ```
 * - **서버 응답**: `UF_LINE_LOCK_RESPONSE` 프로토콜과 함께 성공 여부를 받습니다.
 *   - **성공 시**: `{"success": true, ...}`
 *   - **실패 시**: `{"success": false, "lockOwner": "다른_사용자_ID", ...}`
 * - **클라이언트 동작**: 락 획득에 성공하면 사용자 입력을 허용합니다. 실패하면 해당 라인의 편집을 막고, UI에 누가 락을 소유하고 있는지 표시할 수 있습니다.
 *
 * ### 3단계: 편집 연산(Operation) 전송
 * 라인 락을 획득한 상태에서 사용자가 내용을 수정하면(예: 한 글자 입력/삭제), 즉시 해당 변경 사항을 '연산'으로 서버에 전송합니다.
 * - **프로토콜**: `UF_FILE_EDIT_OPERATION`
 * - **전송할 JSON**:
 *   ```json
 *   // 텍스트 삽입 시
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "...",
 *     "owner": "...",
 *     "path": "...",
 *     "type": "INSERT",
 *     "position": 10, // 전체 텍스트 기준 삽입 위치 (0부터 시작)
 *     "text": "a", // 삽입된 텍스트
 *     "cursorPosition": 11 // (선택 사항) 연산 후의 새로운 커서 위치
 *   }
 *
 *   // 텍스트 삭제 시
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "...",
 *     "owner": "...",
 *     "path": "...",
 *     "type": "DELETE",
 *     "position": 10, // 전체 텍스트 기준 삭제 시작 위치
 *     "length": 1, // 삭제된 글자 수
 *     "cursorPosition": 10 // (선택 사항) 연산 후의 새로운 커서 위치
 *   }
 *   ```
 * - **서버 동작**: 서버는 이 연산을 수신하여 파일에 적용하고, 다른 모든 편집자에게 이 연산과 함께 커서 위치를 브로드캐스트합니다.
 *
 * ### 4단계: 편집 연산 수신 (브로드캐스트)
 * 다른 사용자가 보낸 편집 연산을 서버로부터 수신합니다.
 * - **프로토콜**: `UF_FILE_EDIT_BROADCAST`
 * - **수신할 JSON**: 3단계의 편집 연산과 동일한 형식의 JSON을 받습니다. `cursorPosition` 필드가 포함될 수 있습니다.
 * - **클라이언트 동작**: 수신한 연산을 로컬 에디터의 텍스트에 **즉시 적용**하고, `cursorPosition`이 있다면 해당 사용자의 커서를 업데이트합니다. 이 연산을 다시 서버로 보내면 안 됩니다(무한 루프 방지).
 *
 * ### 5단계: 라인 락 해제
 * 사용자의 커서가 다른 라인으로 이동하거나, 해당 라인에서 일정 시간 입력이 없으면, 점유했던 락을 **반드시 해제**해야 합니다.
 * - **프로토콜**: `UF_LINE_UNLOCK_REQUEST`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "...",
 *     "owner": "...",
 *     "path": "...",
 *     "lineNumber": 1 // 해제할 라인 번호
 *   }
 *   ```
 * - **서버 응답**: `UF_LINE_UNLOCK_RESPONSE` 프로토콜과 함께 성공 여부를 받습니다.
 * - **클라이언트 동작**: 락이 해제되면 다른 사용자가 해당 라인을 편집할 수 있게 됩니다.
 *
 * ### 6단계: 커서 이동 정보 전송 (코드 변경 없음)
 * 사용자가 코드를 변경하지 않고 키보드나 마우스로 커서 위치만 이동했을 때, 이 정보를 서버에 전송합니다.
 * - **프로토콜**: `UF_CURSOR_MOVE`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "owner": "프로젝트_소유자_ID",
 *     "path": "현재_파일_경로",
 *     "cursorPosition": 25 // 커서의 현재 위치 (0부터 시작하는 문자열 인덱스)
 *   }
 *   ```
 * - **서버 응답**: 별도의 직접적인 응답은 없으며, 서버는 이 정보를 다른 편집자에게 브로드캐스트합니다.
 *
 * ### 7단계: 커서 이동 정보 수신 (브로드캐스트)
 * 다른 사용자가 보낸 커서 이동 정보를 서버로부터 수신합니다.
 * - **프로토콜**: `UF_CURSOR_MOVE_BROADCAST`
 * - **수신할 JSON**:
 *   ```json
 *   {
 *     "user_id": "커서_소유자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "path": "현재_파일_경로",
 *     "cursorPosition": 25 // 커서의 현재 위치
 *   }
 *   ```
 * - **클라이언트 동작**: 수신한 `user_id`의 커서를 `cursorPosition`으로 업데이트하여 UI에 표시합니다.
 *
 * ### 연결 종료
 * 클라이언트의 연결이 끊어지면, 서버는 해당 사용자가 소유했던 모든 라인 락을 자동으로 해제합니다.
 */


public class EditorTabView {

    private final SplitPane rootContainer;
    private final Set<TabPane> managedTabPanes = new HashSet<>();
    private TabPane activeTabPane;
    private CodeArea activeCodeArea; // 새 필드 추가
    private Tab draggingTab = null; // 드래그 중인 탭을 추적
    private TabPane dropTargetPane = null;
    private TabPane sourcePaneForDrag = null; // 드래그 시작 TabPane
    private int sourceIndexForDrag = -1; // 드래그 시작 인덱스
    private Node originalGraphic = null; // 드래그 시작 시 원래 그래픽을 저장
    private Tab previewTab = null; // 드래그 미리보기 탭
    private final List<HybridManager> activeManagers = new ArrayList<>();
    private final MainController mainController;
    
    private final Map<String, List<SyntaxError>> tabErrors = new HashMap<>();
    private final Map<String, String> tabFileNames = new HashMap<>();
    private final Map<String, CodeArea> codeAreaMap = new HashMap<>();
    private final Map<String, HybridManager> hybridManagerMap = new HashMap<>();
    private final Map<String, List<Integer>> searchResultsMap = new HashMap<>();
    private final Map<String, Integer> currentMatchIndexMap = new HashMap<>();
    
    private final IntegerProperty totalMatches = new SimpleIntegerProperty(0);
    private final IntegerProperty currentMatchIndex = new SimpleIntegerProperty(0);

    private final Tooltip errorTooltip = new Tooltip();
    private final PauseTransition tooltipDelay = new PauseTransition(Duration.millis(500));
    private final StringProperty activeTabTitle = new SimpleStringProperty("검색...");
    
    // --- Visual Feedback for Drag and Drop ---
    private Pane feedbackPane;
    
    
    public EditorTabView(MainController mainController, SplitPane rootContainer) {
        this.mainController = mainController;
        this.rootContainer = rootContainer;
        errorTooltip.getStyleClass().add("error-tooltip");
    
        initializeFeedbackPane();
        setupDragAndDropHandlers(); // Will be implemented in steps    
        // Create the initial, empty TabPane
        TabPane primaryTabPane = createNewTabPane();
        this.rootContainer.getItems().add(primaryTabPane);
        this.activeTabPane = primaryTabPane;
    
        // Listen for changes in the selected tab to update the search prompt
        primaryTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            updateSearchPrompt(newTab);
        });
    }
    
    private void initializeFeedbackPane() {
        BorderPane dropPane = new BorderPane();

        Region topZone = createDropZone("split-top");
        Region bottomZone = createDropZone("split-bottom");
        Region leftZone = createDropZone("split-left");
        Region rightZone = createDropZone("split-right");
        Region centerZone = createDropZone("merge-center");

        topZone.setPrefHeight(80);
        bottomZone.setPrefHeight(80);
        leftZone.setPrefWidth(80);
        rightZone.setPrefWidth(80);

        dropPane.setTop(topZone);
        dropPane.setBottom(bottomZone);
        dropPane.setLeft(leftZone);
        dropPane.setRight(rightZone);
        dropPane.setCenter(centerZone);

        this.feedbackPane = dropPane;
        this.feedbackPane.setVisible(false);
    }

    private Region createDropZone(String zoneId) {
        Region zone = new Region();
        zone.setUserData(zoneId);
        zone.getStyleClass().add("drop-zone");
        
        // --- FINAL FIX: Manually toggle 'active' class on drag enter/exit ---
        zone.setOnDragEntered(e -> {
            if (draggingTab != null) {
                zone.getStyleClass().add("active");
                e.consume();
            }
        });
        zone.setOnDragExited(e -> {
            if (draggingTab != null) {
                zone.getStyleClass().remove("active");
                e.consume();
            }
        });
        // --- End of FINAL FIX ---

        return zone;
    }

    private void setupDragAndDropHandlers() {
        // Accept the drag event on the parent pane, but DO NOT consume it,
        // so it can propagate to the child zones for hover effects.
        feedbackPane.addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (draggingTab != null) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
        });

        feedbackPane.addEventFilter(DragEvent.DRAG_DROPPED, event -> {
            if (draggingTab == null || dropTargetPane == null) return;

            Object target = event.getTarget();
            if (target instanceof Node) {
                Object userData = ((Node) target).getUserData();
                if (userData instanceof String) {
                    String zoneId = (String) userData;
                    Orientation orientation = null;
                    boolean isMerge = false;

                    switch (zoneId) {
                        case "split-top":
                        case "split-bottom":
                            orientation = Orientation.VERTICAL;
                            break;
                        case "split-left":
                        case "split-right":
                            orientation = Orientation.HORIZONTAL;
                            break;
                        case "merge-center":
                            isMerge = true;
                            break;
                    }

                    if (orientation != null) {
                        splitTab(draggingTab, dropTargetPane, orientation);
                        event.setDropCompleted(true);
                    } else if (isMerge) {
                        // Perform the merge by adding the tab to the target pane
                        if (previewTab != null && previewTab.getTabPane() != null) {
                            previewTab.getTabPane().getTabs().remove(previewTab);
                        }
                        int dropIndex = calculateDropIndex(dropTargetPane, event.getSceneX());
                        dropTargetPane.getTabs().add(dropIndex, draggingTab);
                        dropTargetPane.getSelectionModel().select(draggingTab);
                        Platform.runLater(this::checkAndCleanupAllPanes);
                        event.setDropCompleted(true);
                    }
                    event.consume();
                }
            }
        });
    }

    private void updateSearchPrompt(Tab tab) {
        if (tab != null && tab.isClosable()) {
            if (tab.getGraphic() instanceof HBox) {
                Label tabLabel = (Label) ((HBox) tab.getGraphic()).getChildren().get(0);
                if (tabLabel != null) {
                    activeTabTitle.set(tabLabel.getText() + "에서 검색");
                }
            } else if (tab.getText() != null) {
                activeTabTitle.set(tab.getText() + "에서 검색");
            }
        } else {
            activeTabTitle.set("검색...");
        }
    }

    private TabPane createNewTabPane() {
        TabPane tabPane = new TabPane();
        tabPane.setTabDragPolicy(TabDragPolicy.REORDER);
        managedTabPanes.add(tabPane);

        // --- Drag and Drop v5: Flicker-Free Preview Logic ---
        tabPane.setOnDragOver(event -> {
            if (event.getDragboard().hasString() && draggingTab != null) {
                event.acceptTransferModes(TransferMode.MOVE);
                this.dropTargetPane = tabPane;

                // --- Reposition feedbackPane over the current TabPane ---
                if (feedbackPane.isVisible()) {
                    Node contentArea = tabPane.lookup(".tab-content-area");
                    Bounds bounds;
                    if (contentArea != null) {
                        bounds = contentArea.localToScene(contentArea.getLayoutBounds());
                    } else {
                        bounds = tabPane.localToScene(tabPane.getLayoutBounds());
                    }

                    feedbackPane.setLayoutX(bounds.getMinX());
                    feedbackPane.setLayoutY(bounds.getMinY());
                    feedbackPane.setPrefSize(bounds.getWidth(), bounds.getHeight());
                    feedbackPane.resize(bounds.getWidth(), bounds.getHeight());
                }
                // --- End of Repositioning ---

                // [안전장치 1] 계산된 인덱스를 사용하기 전에 항상 유효성을 검사합니다.
                int dropIndex = calculateDropIndex(tabPane, event.getSceneX());
                int safeIndex = Math.max(0, Math.min(dropIndex, tabPane.getTabs().size()));

                if (previewTab == null) {
                    previewTab = new Tab();
                    previewTab.setClosable(false);
                    previewTab.setDisable(true);
                    previewTab.getStyleClass().add("drag-preview-tab");
                }

                if (previewTab.getTabPane() != tabPane) {
                    if (previewTab.getTabPane() != null) {
                        previewTab.getTabPane().getTabs().remove(previewTab);
                    }
                    // `safeIndex`를 사용하여 절대 범위를 벗어나지 않도록 보장합니다.
                    tabPane.getTabs().add(safeIndex, previewTab);
                } else {
                    int currentPreviewIndex = tabPane.getTabs().indexOf(previewTab);
                    if (currentPreviewIndex != safeIndex) {
                        tabPane.getTabs().remove(previewTab);
                        // 미리보기 탭 제거 후, 리스트 크기가 변경되었으므로 인덱스가 유효한지 다시 확인합니다.
                        int newSafeIndex = Math.min(safeIndex, tabPane.getTabs().size());
                        tabPane.getTabs().add(newSafeIndex, previewTab);
                    }
                }
            }
            event.consume();
        });

        tabPane.setOnDragExited(event -> {
            if (previewTab != null && previewTab.getTabPane() == tabPane) {
                tabPane.getTabs().remove(previewTab);
            }
        });

        // Track the focused TabPane
        tabPane.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                // activeTabPane = tabPane; // This line is intentionally commented out
            }
        });

        // Update search prompt when tab selection changes in any pane
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (tabPane.isFocused()) {
                updateSearchPrompt(newTab);
            }
        });
        
        try {
            String topTabsCss = ConfigManager.getInstance().getTopTabsThemePath();
            if (topTabsCss != null) {
                tabPane.getStylesheets().add(topTabsCss);
            }
        } catch (Exception e) {
            System.err.println("Failed to load top tabs CSS for new TabPane.");
            e.printStackTrace();
        }

        return tabPane;
    }

    public StringProperty activeTabTitleProperty() {
        return activeTabTitle;
    }

    public IntegerProperty totalMatchesProperty() {
        return totalMatches;
    }

    public IntegerProperty currentMatchIndexProperty() {
        return currentMatchIndex;
    }

    public void shutdownAllManagers() {
        activeManagers.forEach(HybridManager::shutdown);
        activeManagers.clear();
    }

    public void closeAllClosableTabs() {
        shutdownAllManagers();
        
        List<Tab> allTabs = new ArrayList<>();
        for (TabPane pane : managedTabPanes) {
            allTabs.addAll(pane.getTabs());
        }

        for (Tab tab : allTabs) {
            if (tab.isClosable()) {
                // This will trigger the setOnClosed event for each tab
                tab.getTabPane().getTabs().remove(tab);
            }
        }

        // The onClosed handlers should have cleaned up empty panes.
        // This is just a final check for any panes that might have been missed.
        List<TabPane> toRemove = new ArrayList<>();
        for (TabPane pane : new ArrayList<>(managedTabPanes)) {
            if (pane.getTabs().isEmpty()) {
                 if (managedTabPanes.size() - toRemove.size() > 1) {
                    toRemove.add(pane);
                }
            }
        }
        toRemove.forEach(this::cleanupEmptyPane);

        if (managedTabPanes.isEmpty()) {
            TabPane primaryTabPane = createNewTabPane();
            this.rootContainer.getItems().add(primaryTabPane);
            this.activeTabPane = primaryTabPane;
        }
    }

    public boolean hasTab(String tabId) {
        for (TabPane pane : managedTabPanes) {
            if (pane.getTabs().stream().anyMatch(tab -> tabId.equals(tab.getId()))) {
                return true;
            }
        }
        return false;
    }

    public void selectTab(String tabId) {
        for (TabPane pane : managedTabPanes) {
            Optional<Tab> tabOpt = pane.getTabs().stream()
                .filter(tab -> tabId.equals(tab.getId()))
                .findFirst();
            
            if (tabOpt.isPresent()) {
                pane.getSelectionModel().select(tabOpt.get());
                pane.requestFocus();
                return;
            }
        }
    }

    public void closeTab(String tabId) {
        for (TabPane pane : managedTabPanes) {
            pane.getTabs().stream()
                .filter(tab -> tabId.equals(tab.getId()))
                .findFirst()
                .ifPresent(tab -> pane.getTabs().remove(tab));
        }
    }

    public void openTab(String tabId, String title, Node content) {
        createTab(tabId, title, content, null);
    }

    public void openTabWithCloseCallback(String tabId, String title, Node content, Runnable onClose) {
        createTab(tabId, title, content, onClose);
    }

    private Tab createTab(String tabId, String title, Node content, Runnable customOnClose) {
        if (hasTab(tabId)) {
            selectTab(tabId);
            return findTabById(tabId).orElse(null);
        }

        Tab newTab = new Tab(title, content);
        newTab.setId(tabId);
        newTab.setClosable(true);

        newTab.setOnClosed(e -> {
            if (customOnClose != null) {
                customOnClose.run();
            }
            Platform.runLater(this::checkAndCleanupAllPanes);
        });

        TabPane targetPane = activeTabPane;
        if (targetPane == null && !managedTabPanes.isEmpty()) {
            targetPane = managedTabPanes.iterator().next();
        }
        
        if (targetPane != null) {
            targetPane.getTabs().add(newTab);
            targetPane.getSelectionModel().select(newTab);
            targetPane.requestFocus();
        }
        
        return newTab;
    }
    
    /**
     * Scans all managed TabPanes and cleans up any that are empty.
     * This is a robust way to handle cleanup after a tab is closed, regardless of
     * which pane it was in (especially after being moved via split).
     */
    private void checkAndCleanupAllPanes() {
        // Iterate over a copy to avoid ConcurrentModificationException, as cleanupEmptyPane modifies the set.
        List<TabPane> panesToCheck = new ArrayList<>(managedTabPanes);
        
        for (TabPane pane : panesToCheck) {
            // We must re-check the size inside the loop, as a previous cleanup can change the count.
            if (pane.getTabs().isEmpty() && managedTabPanes.size() > 1) {
                // Also ensure the pane hasn't already been removed by a cascading cleanup.
                if (managedTabPanes.contains(pane)) {
                    cleanupEmptyPane(pane);
                }
            }
        }
    }

    private void aggregateAndSendProblems() {
        List<Problem> allProblems = new ArrayList<>();
        for (Map.Entry<String, List<SyntaxError>> entry : tabErrors.entrySet()) {
            String tabId = entry.getKey();
            String fileName = tabFileNames.get(tabId);
            List<SyntaxError> errors = entry.getValue();

            String filePath = null;
            if (tabId.startsWith("file-")) {
                filePath = tabId.substring(5);
            }

            if (filePath != null && fileName != null) {
                for (SyntaxError error : errors) {
                    allProblems.add(new Problem(filePath, fileName, error));
                }
            }
        }
        mainController.updateProblems(allProblems);
    }

    private void handleErrorUpdate(String tabId, String fileName, List<SyntaxError> errors) {
        tabErrors.put(tabId, errors);
        aggregateAndSendProblems();

        findTabById(tabId).ifPresent(tab -> {
            if (tab.getGraphic() instanceof HBox hbox) {
                Label errorLabel = (Label) hbox.lookup(".tab-error-count");
                if (errorLabel != null) {
                    if (errors.isEmpty()) {
                        errorLabel.setText("");
                        errorLabel.getStyleClass().remove("has-errors");
                    } else {
                        int errorCount = errors.size();
                        String errorText = errorCount > 9 ? "9+" : String.valueOf(errorCount);
                        errorLabel.setText(errorText);
                        if (!errorLabel.getStyleClass().contains("has-errors")) {
                            errorLabel.getStyleClass().add("has-errors");
                        }
                    }
                }
            }
        });

        CodeArea codeArea = codeAreaMap.get(tabId);
        if (codeArea != null) {
            codeArea.setParagraphGraphicFactory(codeArea.getParagraphGraphicFactory());
        }
    }

    public void openFileInEditor(String filePath, String content) {
        String fileName = Paths.get(filePath).getFileName().toString();
        String tabId = "file-" + filePath;

        if (hasTab(tabId)) {
            selectTab(tabId);
            return;
        }
        
        CodeArea codeArea = new CodeArea();
        codeArea.getStyleClass().add("code-area");
        
        codeAreaMap.put(tabId, codeArea);
        tabErrors.put(tabId, new ArrayList<>());
        tabFileNames.put(tabId, fileName);

        setupEditorFeatures(codeArea, tabId);
        setupErrorTooltip(codeArea, tabId);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        String fileExtension = getFileExtension(filePath);

        HybridManager manager = new HybridManager(
            codeArea, 
            fileExtension, 
            (errors) -> Platform.runLater(() -> handleErrorUpdate(tabId, fileName, errors)),
            mainController::notifyAntlrTaskStarted,
            mainController::notifyAntlrTaskFinished
        );
        
        activeManagers.add(manager);
        hybridManagerMap.put(tabId, manager);
        codeArea.replaceText(0, 0, content);

        Runnable onClose = () -> {
            manager.shutdown();
            activeManagers.remove(manager);
            hybridManagerMap.remove(tabId);
            tabErrors.remove(tabId);
            codeAreaMap.remove(tabId);
            searchResultsMap.remove(tabId);
            currentMatchIndexMap.remove(tabId);
            mainController.updateProblems(new ArrayList<>());
        };

        Tab newTab = createTab(tabId, null, scrollPane, onClose);
        if (newTab == null) return;
        
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.getStyleClass().add("tab-file-name");
        
        Label errorCountLabel = new Label("");
        errorCountLabel.getStyleClass().add("tab-error-count");
        errorCountLabel.setMinWidth(Region.USE_PREF_SIZE);
        
        HBox tabGraphic = new HBox(5, fileNameLabel, errorCountLabel);
        tabGraphic.setAlignment(Pos.CENTER_LEFT);
        // Make the HBox fill the available width of the tab header
        tabGraphic.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileNameLabel, Priority.ALWAYS);

        tabGraphic.setOnDragDetected(event -> {
            if (!newTab.isClosable()) return;

            draggingTab = newTab;
            sourcePaneForDrag = newTab.getTabPane();
            sourceIndexForDrag = sourcePaneForDrag.getTabs().indexOf(newTab);
            originalGraphic = newTab.getGraphic();

            Dragboard db = tabGraphic.startDragAndDrop(TransferMode.MOVE);
            
            Node tabHeaderNode = tabGraphic;
            Node parent = tabGraphic.getParent();
            while (parent != null) {
                if (parent.getStyleClass().contains("tab")) {
                    tabHeaderNode = parent;
                    break;
                }
                parent = parent.getParent();
            }

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(javafx.scene.paint.Color.TRANSPARENT);
            WritableImage snapshot = tabHeaderNode.snapshot(params, null);

            db.setDragView(snapshot, 0, 0);

            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(newTab.getId());
            db.setContent(clipboardContent);

            if (rootContainer.getScene() != null && rootContainer.getScene().getRoot() instanceof Pane) {
                Pane sceneRoot = (Pane) rootContainer.getScene().getRoot();
                if (!sceneRoot.getChildren().contains(feedbackPane)) {
                    sceneRoot.getChildren().add(feedbackPane);
                }
                
                Platform.runLater(() -> {
                    if (sourcePaneForDrag == null) return;

                    Node contentArea = sourcePaneForDrag.lookup(".tab-content-area");
                    Bounds bounds;
                    if (contentArea != null) {
                        bounds = contentArea.localToScene(contentArea.getLayoutBounds());
                    } else {
                        // Fallback to original behavior if content area is not found
                        bounds = sourcePaneForDrag.localToScene(sourcePaneForDrag.getLayoutBounds());
                    }
                    
                    feedbackPane.setLayoutX(bounds.getMinX());
                    feedbackPane.setLayoutY(bounds.getMinY());
                    feedbackPane.setPrefSize(bounds.getWidth(), bounds.getHeight());
                    feedbackPane.resize(bounds.getWidth(), bounds.getHeight());
                    
                    feedbackPane.setVisible(true);

                    // --- DETAILED CSS DEBUGGING ---
                    System.out.println("\n--- CSS DEBUG START ---");
                    System.out.println("Scene stylesheets: " + rootContainer.getScene().getStylesheets());
                    System.out.println("feedbackPane style classes: " + feedbackPane.getStyleClass());
                    if (feedbackPane instanceof BorderPane) {
                        Node topZone = ((BorderPane) feedbackPane).getTop();
                        if (topZone != null) {
                            System.out.println("Top drop zone style classes: " + topZone.getStyleClass());
                        }
                    }
                    System.out.println(String.format(
                        "feedbackPane final state: visible=%b, X=%.2f, Y=%.2f, W=%.2f, H=%.2f",
                        feedbackPane.isVisible(), feedbackPane.getLayoutX(), feedbackPane.getLayoutY(),
                        feedbackPane.getWidth(), feedbackPane.getHeight()
                    ));
                    System.out.println("--- CSS DEBUG END ---\n");
                    // --- END OF DEBUGGING ---
                });
            }

            sourcePaneForDrag.getTabs().remove(newTab);
            event.consume();
        });

        tabGraphic.setOnDragDone(event -> {
            if (feedbackPane != null) {
                feedbackPane.setVisible(false);
                feedbackPane.prefWidthProperty().unbind();
                feedbackPane.prefHeightProperty().unbind();
            }
            
            if (event.getTransferMode() != TransferMode.MOVE) {
                if (sourcePaneForDrag != null && draggingTab != null) {
                    int addIndex = Math.min(sourceIndexForDrag, sourcePaneForDrag.getTabs().size());
                    sourcePaneForDrag.getTabs().add(addIndex, draggingTab);
                }
            }

            if (draggingTab != null && originalGraphic != null) {
                draggingTab.setGraphic(originalGraphic);
            }

            draggingTab = null;
            sourcePaneForDrag = null;
            sourceIndexForDrag = -1;
            originalGraphic = null;
            dropTargetPane = null;
            
            event.consume();
        });

        ContextMenu contextMenu = new ContextMenu();
        MenuItem splitRight = new MenuItem("우측으로 분할");
        splitRight.setOnAction(e -> splitTab(newTab, Orientation.HORIZONTAL));
        MenuItem splitDown = new MenuItem("하단으로 분할");
        splitDown.setOnAction(e -> splitTab(newTab, Orientation.VERTICAL));
        contextMenu.getItems().addAll(splitRight, splitDown);
        
        tabGraphic.setOnContextMenuRequested(e -> contextMenu.show(tabGraphic, e.getScreenX(), e.getScreenY()));

        newTab.setGraphic(tabGraphic);
    }

    private void splitTab(Tab tab, Orientation orientation) {
        TabPane sourcePane = tab.getTabPane();
        if (sourcePane == null) {
            return;
        }
        splitTab(tab, sourcePane, orientation);
    }

    private void splitTab(Tab tab, TabPane targetPane, Orientation orientation) {
        // If the source pane has only one tab, we are essentially just moving the pane.
        // But for simplicity, we'll treat it as a split and let the cleanup logic handle the empty pane.

        TabPane newPane = createNewTabPane();

        Parent container = findContainerOf(targetPane);
        if (!(container instanceof SplitPane)) {
            // This case should ideally not happen if the root is a SplitPane
            managedTabPanes.remove(newPane); // Don't leak a new pane
            System.err.println("Cannot split: target pane is not in a SplitPane.");
            return;
        }
        SplitPane parentSplitPane = (SplitPane) container;

        int targetIndex = parentSplitPane.getItems().indexOf(targetPane);
        if (targetIndex == -1) {
             managedTabPanes.remove(newPane);
             System.err.println("Cannot split: could not find target pane in its parent.");
             return;
        }

        if (parentSplitPane.getOrientation() == orientation) {
            // Same orientation: add the new pane next to the source pane
            parentSplitPane.getItems().add(targetIndex + 1, newPane);
            parentSplitPane.setDividerPosition(targetIndex, 0.5);
        } else {
            // Different orientation: replace the source pane with a new SplitPane
            SplitPane nestedSplitPane = new SplitPane();
            nestedSplitPane.setOrientation(orientation);
            nestedSplitPane.getItems().addAll(targetPane, newPane);
            
            // Replace the original source pane with the new nested split pane
            parentSplitPane.getItems().set(targetIndex, nestedSplitPane);
            
            // Set divider position after the scene graph is updated
            Platform.runLater(() -> nestedSplitPane.setDividerPosition(0, 0.5));
        }

        // The actual tab move is done last, in Platform.runLater
        Platform.runLater(() -> {
            if (tab.getTabPane() != null) {
                tab.getTabPane().getTabs().remove(tab);
            }
            newPane.getTabs().add(tab);
            newPane.getSelectionModel().select(tab);
            newPane.requestFocus();

            // This will now correctly find the empty sourcePane and clean it up
            checkAndCleanupAllPanes();
        });
    }

    private void cleanupEmptyPane(TabPane pane) {
        if (managedTabPanes.size() <= 1) {
            return;
        }

        Parent container = findContainerOf(pane);
        managedTabPanes.remove(pane); 

        if (activeTabPane == pane) {
            activeTabPane = managedTabPanes.stream().findFirst().orElse(null);
        }

        if (container instanceof SplitPane) {
            SplitPane parentSplitPane = (SplitPane) container;
            
            parentSplitPane.getItems().remove(pane);

            if (parentSplitPane.getItems().size() == 1) {
                Node survivor = parentSplitPane.getItems().get(0);
                
                Parent grandParent = findContainerOf(parentSplitPane);
                if (grandParent instanceof SplitPane) {
                    SplitPane grandParentSplitPane = (SplitPane) grandParent;
                    int parentIndex = grandParentSplitPane.getItems().indexOf(parentSplitPane);
                    if (parentIndex != -1) {
                        grandParentSplitPane.getItems().set(parentIndex, survivor);
                    }
                }
            }
        }
    }

    private Parent findContainerOf(Node target) {
        if (rootContainer.getItems().contains(target)) {
            return rootContainer;
        }
        return findContainerRecursive(rootContainer, target);
    }

    private Parent findContainerRecursive(Parent parent, Node target) {
        if (parent instanceof SplitPane) {
            SplitPane sp = (SplitPane) parent;
            if (sp.getItems().contains(target)) {
                return sp;
            }
            for (Node child : sp.getItems()) {
                if (child instanceof Parent) {
                    Parent found = findContainerRecursive((Parent) child, target);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private int calculateDropIndex(TabPane tabPane, double sceneX) {
        int dropIndex = tabPane.getTabs().size();
        
        List<Node> sortedTabNodes = new ArrayList<>(tabPane.lookupAll(".tab"));
        // Exclude the preview tab itself from the calculation
        sortedTabNodes.removeIf(n -> n.getStyleClass().contains("drag-preview-tab"));
        
        sortedTabNodes.sort(Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinX()));

        int currentIndex = 0;
        for (Node tabNode : sortedTabNodes) {
            if (sceneX < tabNode.localToScene(tabNode.getBoundsInLocal()).getCenterX()) {
                dropIndex = currentIndex;
                break;
            }
            currentIndex++;
        }
        return dropIndex;
    }

    private Optional<Tab> findTabById(String tabId) {
        for (TabPane pane : managedTabPanes) {
            Optional<Tab> tabOpt = pane.getTabs().stream().filter(t -> tabId.equals(t.getId())).findFirst();
            if (tabOpt.isPresent()) {
                return tabOpt;
            }
        }
        return Optional.empty();
    }

    private void setupErrorTooltip(CodeArea codeArea, String tabId) {
        codeArea.setOnMouseMoved(e -> {
            tooltipDelay.stop();
            errorTooltip.hide();
            tooltipDelay.setOnFinished(event -> {
                int charIndex = codeArea.hit(e.getX(), e.getY()).getCharacterIndex().orElse(-1);
                if (charIndex == -1) return;

                List<SyntaxError> errors = tabErrors.get(tabId);
                if (errors == null) return;

                Optional<SyntaxError> errorOpt = errors.stream().filter(err -> {
                    // Validate line number before getting absolute position
                    if (err.line <= 0 || err.line > codeArea.getParagraphs().size()) {
                        return false;
                    }
                    int start = codeArea.getAbsolutePosition(err.line - 1, err.charPositionInLine);
                    int end = start + err.length;
                    return charIndex >= start && charIndex < end;
                }).findFirst();

                errorOpt.ifPresent(error -> {
                    errorTooltip.setText(error.message);
                    Point2D pos = codeArea.localToScreen(e.getX(), e.getY());
                    errorTooltip.show(codeArea, pos.getX() + 10, pos.getY() + 10);
                });
            });
            tooltipDelay.playFromStart();
        });
        codeArea.setOnMouseExited(e -> {
            tooltipDelay.stop();
            errorTooltip.hide();
        });
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }

    public void navigateTo(String filePath, int line, int column) {
        String tabId = "file-" + filePath;
        if (!hasTab(tabId)) {
            System.out.println("File not open: " + filePath);
            return;
        }

        selectTab(tabId);
        CodeArea codeArea = codeAreaMap.get(tabId);
        if (codeArea != null) {
            // line is 1-based, moveTo is 0-based
            codeArea.moveTo(line - 1, column);
            codeArea.requestFollowCaret(); // Make sure the caret is visible
            codeArea.requestFocus();
        }
    }
    
    public void reapplyAllEditorSettings() {
        for (CodeArea codeArea : codeAreaMap.values()) {
            applyStylesToCodeArea(codeArea);
        }
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

    private void setupEditorFeatures(CodeArea codeArea, String tabId) {
        applyStylesToCodeArea(codeArea);
        
        // 첫 번째 CodeArea를 기본 활성 CodeArea로 설정
        if (activeCodeArea == null) {
            activeCodeArea = codeArea;
        }

        // CodeArea에 포커스 리스너 추가
        codeArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                activeCodeArea = codeArea;
                // 현재 CodeArea가 포함된 TabPane을 찾아 활성 TabPane으로 설정 및 프롬프트 업데이트
                findTabById(tabId).ifPresent(tab -> {
                    if (tab.getTabPane() != null) {
                        activeTabPane = tab.getTabPane();
                    }
                    updateSearchPrompt(tab); // 프롬프트 업데이트 호출 추가
                });
            }
        });

        final double MIN_INITIAL_WIDTH = 60.0;
        final double RIGHT_PADDING_NUM = 15.0;
        final double LEFT_PADDING_NUM = 5.0;

        final DoubleProperty lineNumberPrefWidth = new SimpleDoubleProperty(MIN_INITIAL_WIDTH);

        codeArea.getParagraphs().addListener((ListChangeListener<Object>) c -> {
            int totalLines = Math.max(1, codeArea.getParagraphs().size());
            String maxLineNumberText = String.valueOf(totalLines);
            Text text = new Text(maxLineNumberText);
            text.setFont(Font.font(ConfigManager.getInstance().getFontFamily(), ConfigManager.getInstance().getFontSize()));
            double textWidth = text.getLayoutBounds().getWidth();
            double horizontalPadding = LEFT_PADDING_NUM + RIGHT_PADDING_NUM;
            double dynamicWidth = Math.ceil(textWidth + horizontalPadding);
            lineNumberPrefWidth.set(Math.max(MIN_INITIAL_WIDTH, dynamicWidth));
        });

        final String CARET_LINE_STYLE = "-fx-text-fill: #d4d4d4;";
        final String DEFAULT_LINE_STYLE = "-fx-text-fill: #585858;";
        final String ERR_LINE_STYLE="-fx-text-fill: #ff6666 !important;";

        codeArea.setParagraphGraphicFactory(lineIndex -> {
            Label lineLabel = new Label();
            lineLabel.setFont(Font.font(ConfigManager.getInstance().getFontFamily(), ConfigManager.getInstance().getFontSize()));
            lineLabel.setText(String.valueOf(lineIndex + 1));
            lineLabel.getStyleClass().add("lineno");
            lineLabel.setAlignment(Pos.CENTER);
            lineLabel.setPadding(new Insets(0, RIGHT_PADDING_NUM, 0, LEFT_PADDING_NUM));
            lineLabel.prefWidthProperty().bind(lineNumberPrefWidth);

            if (lineIndex == codeArea.getCurrentParagraph()) {
                lineLabel.setStyle(CARET_LINE_STYLE);
            } else {
                lineLabel.setStyle(DEFAULT_LINE_STYLE);
            }
            List<SyntaxError> errors = tabErrors.getOrDefault(tabId, new ArrayList<>());
            boolean hasError = errors.stream().anyMatch(e -> e.line - 1 == lineIndex);
            if (hasError) {
                if (!lineLabel.getStyle().contains(ERR_LINE_STYLE)) lineLabel.setStyle(ERR_LINE_STYLE);
            } else {
                if (lineIndex == codeArea.getCurrentParagraph()) lineLabel.setStyle(CARET_LINE_STYLE);
                else lineLabel.setStyle(DEFAULT_LINE_STYLE);
            }
            return lineLabel;
        });
        
        codeArea.currentParagraphProperty().addListener((obs, oldParagraph, newParagraph) -> {
            Label oldLabel = getLineNumberLabel(codeArea, oldParagraph);
            if (oldLabel != null) {
                updateLineNumberStyle(oldLabel, oldParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE);
            }
            Label newLabel = getLineNumberLabel(codeArea, newParagraph);
            if (newLabel != null) {
                updateLineNumberStyle(newLabel, newParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE);
            }
        });
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> codeArea.requestFollowCaret());
    }

    private void applyStylesToCodeArea(CodeArea codeArea) {
        ConfigManager configManager = ConfigManager.getInstance();
        final int FONT_SIZE = configManager.getFontSize();
        final String FONT_FAMILY = configManager.getFontFamily();
        final Font CODE_FONT = Font.font(FONT_FAMILY, FONT_SIZE);
        final double LINE_SPACING_FACTOR = 0.4;

        Text tempText = new Text("Ag");
        tempText.setFont(CODE_FONT);
        double fontHeight = tempText.getLayoutBounds().getHeight();
        double targetLineHeight = Math.ceil(fontHeight * (1 + LINE_SPACING_FACTOR));
        double verticalPadding = (targetLineHeight - fontHeight) / 2.0;
        double caretHeight = targetLineHeight + 1;
        
        String tabSizeCss = String.format(".paragraph-text { -fx-tab-size: %d; }", configManager.getTabSize());
        String dynamicStylingCss = String.format(
            ".text {" +
            "    -fx-font-family: '%s'; -fx-font-size: %dpx;" +
            "}" +
            ".paragraph-box {" +
            "    -fx-min-height: %.1fpx; -fx-max-height: %.1fpx; -fx-pref-height: %.1fpx;" +
            "    -fx-display: flex;" +
            "    -fx-alignment: center-left;" +
            "    -fx-padding: 0 0 0 10px;" +
            "}" +
            ".caret {" +
            "    -fx-shape: \"M0,0 H1 V%.1f\";" +
            "    -fx-stroke-width: 2px;" +
            "}" +
            ".syntax-error {" +
            "    -rtfx-background-color: rgba(255, 71, 71, 0.44);" +
            "    -fx-padding: %.1fpx 0;" +
            "}",
            FONT_FAMILY, FONT_SIZE,
            targetLineHeight, targetLineHeight, targetLineHeight,
            caretHeight,
            verticalPadding
        );

        String combinedCss = tabSizeCss + "\n" + dynamicStylingCss;
        String dataUri = "data:text/css;base64," + Base64.getEncoder().encodeToString(combinedCss.getBytes());
        
        codeArea.getStylesheets().clear();
        codeArea.getStylesheets().add(dataUri);
    }

    private Label getLineNumberLabel(CodeArea codeArea, int paragraphIndex) {
        if (paragraphIndex < 0 || paragraphIndex >= codeArea.getParagraphs().size()) {
            return null;
        }
        Node graphic = codeArea.getParagraphGraphic(paragraphIndex);
        if (graphic instanceof Label) {
            return (Label) graphic;
        }
        if (graphic instanceof Region && ((Region) graphic).lookup(".label") instanceof Label) {
            return (Label) ((Region) graphic).lookup(".label");
        }
        return null;
    }

    private void updateLineNumberStyle(Label lineLabel, int lineIndex, String tabId, CodeArea codeArea,
                                   String DEFAULT_LINE_STYLE, String CARET_LINE_STYLE, String ERR_LINE_STYLE) {
    
        List<SyntaxError> errors = tabErrors.getOrDefault(tabId, Collections.emptyList());
        boolean hasError = errors.stream().anyMatch(e -> e.line - 1 == lineIndex);

        if (hasError) {
            lineLabel.setStyle(ERR_LINE_STYLE);
        } else {
            if (lineIndex == codeArea.getCurrentParagraph()) {
                lineLabel.setStyle(CARET_LINE_STYLE);
            } else {
                lineLabel.setStyle(DEFAULT_LINE_STYLE);
            }
        }
    }

    // --- New Public Search API for MainController ---

    public String getCurrentSelectedText() {
        if (activeCodeArea != null) {
            return activeCodeArea.getSelectedText();
        }
        return "";
    }

    public void performSearchOnActiveTab(String query, boolean caseSensitive) {
        if (activeCodeArea == null) return;

        findTabIdForCodeArea(activeCodeArea).ifPresent(tabId -> {
            performSearch(activeCodeArea, tabId, query, caseSensitive);
        });
    }

    public void goToNextMatchOnActiveTab() {
        if (activeCodeArea == null) return;

        findTabIdForCodeArea(activeCodeArea).ifPresent(tabId -> {
            goToNextMatch(activeCodeArea, tabId);
        });
    }

    public void goToPreviousMatchOnActiveTab() {
        if (activeCodeArea == null) return;
        
        findTabIdForCodeArea(activeCodeArea).ifPresent(tabId -> {
            goToPreviousMatch(activeCodeArea, tabId);
        });
    }

    private Optional<String> findTabIdForCodeArea(CodeArea codeArea) {
        return codeAreaMap.entrySet().stream()
            .filter(entry -> entry.getValue().equals(codeArea))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    // --- Private Core Search Logic ---

    private void performSearch(CodeArea codeArea, String tabId, String query, boolean caseSensitive) {
        searchResultsMap.put(tabId, new ArrayList<>());
        currentMatchIndexMap.put(tabId, -1);

        if (query.isEmpty()) {
            highlightMatches(codeArea, tabId, query);
            totalMatches.set(0);
            currentMatchIndex.set(0);
            return;
        }

        String text = codeArea.getText();
        List<Integer> results = searchResultsMap.get(tabId);

        Pattern pattern = caseSensitive ? Pattern.compile(Pattern.quote(query)) : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            results.add(matcher.start());
        }

        totalMatches.set(results.size());
        highlightMatches(codeArea, tabId, query);

        if (!results.isEmpty()) {
            currentMatchIndexMap.put(tabId, 0);
            currentMatchIndex.set(1);
            goToMatch(codeArea, tabId, 0);
        } else {
            currentMatchIndex.set(0);
        }
    }

    private void highlightMatches(CodeArea codeArea, String tabId, String query) {
        HybridManager manager = hybridManagerMap.get(tabId);
        if (manager == null) {
            return;
        }

        List<Integer> searchResults = searchResultsMap.get(tabId);
        if (query.isEmpty() || searchResults == null || searchResults.isEmpty()) {
            manager.updateSearchHighlights(Collections.emptyList());
            return;
        }

        List<StyleToken> searchTokens = new ArrayList<>();
        for (Integer start : searchResults) {
            int end = start + query.length();
            searchTokens.add(new StyleToken(start, end, Collections.singletonList("search-highlight")));
        }
        
        manager.updateSearchHighlights(searchTokens);
    }

    private void goToNextMatch(CodeArea codeArea, String tabId) {
        List<Integer> results = searchResultsMap.get(tabId);
        if (results == null || results.isEmpty()) return;

        int currentIndex = currentMatchIndexMap.getOrDefault(tabId, -1);
        currentIndex = (currentIndex + 1) % results.size();
        currentMatchIndexMap.put(tabId, currentIndex);
        currentMatchIndex.set(currentIndex + 1);
        goToMatch(codeArea, tabId, currentIndex);
    }

    private void goToPreviousMatch(CodeArea codeArea, String tabId) {
        List<Integer> results = searchResultsMap.get(tabId);
        if (results == null || results.isEmpty()) return;

        int currentIndex = currentMatchIndexMap.getOrDefault(tabId, 0);
        currentIndex = (currentIndex - 1 + results.size()) % results.size();
        currentMatchIndexMap.put(tabId, currentIndex);
        currentMatchIndex.set(currentIndex + 1);
        goToMatch(codeArea, tabId, currentIndex);
    }

    private void goToMatch(CodeArea codeArea, String tabId, int index) {
        List<Integer> results = searchResultsMap.get(tabId);
        if (results == null || index < 0 || index >= results.size()) return;

        int pos = results.get(index);
        
        String query = mainController.getSearchQuery();
        if (query.isEmpty()) return;

        codeArea.selectRange(pos, pos + query.length());
        codeArea.requestFollowCaret();
    }
}
