package com.ethis2s.view.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ethis2s.view.EditorTabView;

/**
 * 탭의 드래그 앤 드롭, 창 분할 및 병합 로직을 처리하는 클래스입니다.
 */
public class TabDragDropManager {

    private final EditorTabView editorTabView;
    private final SplitPane rootContainer;
    private final Pane feedbackPane;

    private Tab draggingTab = null;
    private TabPane dropTargetPane = null;
    private TabPane sourcePaneForDrag = null;
    private int sourceIndexForDrag = -1;
    private Node originalGraphic = null;
    private Tab previewTab = null;

    public TabDragDropManager(EditorTabView editorTabView, SplitPane rootContainer) {
        this.editorTabView = editorTabView;
        this.rootContainer = rootContainer;
        this.feedbackPane = createFeedbackPane();
        setupGlobalDragHandlers();
    }

    public void registerDraggableTab(Tab tab, Node graphic) {
        graphic.setOnDragDetected(event -> {
            if (!tab.isClosable()) return;

            draggingTab = tab;
            sourcePaneForDrag = tab.getTabPane();
            sourceIndexForDrag = sourcePaneForDrag.getTabs().indexOf(tab);
            originalGraphic = tab.getGraphic();

            Dragboard db = graphic.startDragAndDrop(TransferMode.MOVE);
            WritableImage snapshot = snapshotTabHeader(graphic);
            db.setDragView(snapshot, 0, 0);

            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(tab.getId());
            db.setContent(clipboardContent);

            // [변경점] 여기서 바로 feedbackPane을 띄우지 않습니다.
            // showFeedbackPane(sourcePaneForDrag); 
            // feedbackPane은 DragOver 이벤트에서 조건부로 띄웁니다.

            sourcePaneForDrag.getTabs().remove(tab);
            event.consume();
        });

        graphic.setOnDragDone(event -> {
            feedbackPane.setVisible(false); // 드래그가 끝나면 무조건 숨김
            if (previewTab != null && previewTab.getTabPane() != null) {
                previewTab.getTabPane().getTabs().remove(previewTab);
            }
            
            if (event.getTransferMode() != TransferMode.MOVE) {
                if (sourcePaneForDrag != null) {
                    int addIndex = Math.min(sourceIndexForDrag, sourcePaneForDrag.getTabs().size());
                    sourcePaneForDrag.getTabs().add(addIndex, draggingTab);
                }
            }
            if (draggingTab != null && originalGraphic != null) {
                draggingTab.setGraphic(originalGraphic);
            }
            resetDragState();
            event.consume();
        });
    }
    
    

    public void registerDropTarget(TabPane tabPane) {
        tabPane.setOnDragOver(event -> {
            // --- [디버깅 시작] ---
            System.out.println("\n--- DragOver Event Fired on TabPane ---");

            if (draggingTab == null) {
                System.out.println("[DEBUG] draggingTab is null. Exiting.");
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            this.dropTargetPane = tabPane;

            System.out.println("[DEBUG] Looking for .tab-header-area...");
            Node headerArea = tabPane.lookup(".tab-header-area");

            if (headerArea == null) {
                System.out.println("[DEBUG] !!! FAILED to find .tab-header-area. Defaulting to split mode.");
                // 헤더를 찾을 수 없으면 분할 모드만 지원
                feedbackPane.setVisible(true);
                repositionFeedbackPane(tabPane);
                System.out.println("[DEBUG] Final feedbackPane visibility: " + feedbackPane.isVisible());
                event.consume();
                return;
            }
            System.out.println("[DEBUG] Found .tab-header-area.");

            Bounds headerBoundsInLocal = headerArea.getBoundsInLocal();
            double eventY = event.getY();
            double headerMaxY = headerBoundsInLocal.getMaxY();

            System.out.println(String.format("[DEBUG] Mouse Y: %.2f  |  Header Max Y: %.2f", eventY, headerMaxY));

            if (eventY <= headerMaxY) {
                System.out.println("[DEBUG] Decision: IN HEADER -> Showing preview tab, Hiding feedback pane.");
                // --- 헤더 영역에 있을 때 (탭 순서 변경 모드) ---
                feedbackPane.setVisible(false);
                updatePreviewTab(tabPane, event.getSceneX());

            } else {
                System.out.println("[DEBUG] Decision: IN CONTENT -> Hiding preview tab, Showing feedback pane.");
                // --- 콘텐츠 영역에 있을 때 (창 분할 모드) ---
                if (previewTab != null && previewTab.getTabPane() != null) {
                    previewTab.getTabPane().getTabs().remove(previewTab);
                }
                
                Pane sceneRoot = (Pane) rootContainer.getScene().getRoot();
                if (!sceneRoot.getChildren().contains(feedbackPane)) {
                    sceneRoot.getChildren().add(feedbackPane);
                }
                feedbackPane.setVisible(true);
                repositionFeedbackPane(tabPane);
            }

            System.out.println("[DEBUG] Final feedbackPane visibility: " + feedbackPane.isVisible());
            System.out.println("--- DragOver Event End ---");
            // --- [디버깅 끝] ---

            event.consume();
        });

        tabPane.setOnDragExited(event -> {
            if (previewTab != null && previewTab.getTabPane() == tabPane) {
                tabPane.getTabs().remove(previewTab);
            }
            feedbackPane.setVisible(false);
            System.out.println("\n--- DragExited Event Fired. All panes hidden. ---\n");
        });
    }

    public void splitTab(Tab tab, Orientation orientation) {
        splitTab(tab, tab.getTabPane(), orientation);
    }
    
    private void splitTab(Tab tab, TabPane targetPane, Orientation orientation) {
        if (targetPane == null) return;
        TabPane newPane = editorTabView.createNewTabPane();
        Parent container = findContainerOf(targetPane);
        if (!(container instanceof SplitPane)) {
            System.err.println("Cannot split: target pane is not in a SplitPane.");
            return;
        }
        SplitPane parentSplitPane = (SplitPane) container;
        int targetIndex = parentSplitPane.getItems().indexOf(targetPane);

        if (parentSplitPane.getOrientation() == orientation) {
            parentSplitPane.getItems().add(targetIndex + 1, newPane);
            parentSplitPane.setDividerPosition(targetIndex, 0.5);
        } else {
            SplitPane nestedSplitPane = new SplitPane(targetPane, newPane);
            nestedSplitPane.setOrientation(orientation);
            parentSplitPane.getItems().set(targetIndex, nestedSplitPane);
            Platform.runLater(() -> nestedSplitPane.setDividerPosition(0, 0.5));
        }

        Platform.runLater(() -> {
            if (tab.getTabPane() != null) tab.getTabPane().getTabs().remove(tab);
            newPane.getTabs().add(tab);
            newPane.getSelectionModel().select(tab);
            newPane.requestFocus();
            editorTabView.checkAndCleanupAllPanes();
        });
    }

    private void setupGlobalDragHandlers() {
        feedbackPane.addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (draggingTab != null) event.acceptTransferModes(TransferMode.MOVE);
        });

        feedbackPane.addEventFilter(DragEvent.DRAG_DROPPED, event -> {
            if (draggingTab == null || dropTargetPane == null) return;
            if (event.getTarget() instanceof Node node && node.getUserData() instanceof String zoneId) {
                switch (zoneId) {
                    case "split-top" -> splitTab(draggingTab, dropTargetPane, Orientation.VERTICAL);
                    case "split-bottom" -> splitTab(draggingTab, dropTargetPane, Orientation.VERTICAL);
                    case "split-left" -> splitTab(draggingTab, dropTargetPane, Orientation.HORIZONTAL);
                    case "split-right" -> splitTab(draggingTab, dropTargetPane, Orientation.HORIZONTAL);
                    case "merge-center" -> {
                        if (previewTab != null && previewTab.getTabPane() != null) {
                            previewTab.getTabPane().getTabs().remove(previewTab);
                        }
                        int dropIndex = calculateDropIndex(dropTargetPane, event.getSceneX());
                        dropTargetPane.getTabs().add(dropIndex, draggingTab);
                        dropTargetPane.getSelectionModel().select(draggingTab);
                        Platform.runLater(editorTabView::checkAndCleanupAllPanes);
                    }
                }
                event.setDropCompleted(true);
                event.consume();
            }
        });
    }

    private WritableImage snapshotTabHeader(Node graphic) {
        Node tabHeaderNode = graphic;
        Parent parent = graphic.getParent();
        while (parent != null) {
            if (parent.getStyleClass().contains("tab")) {
                tabHeaderNode = parent;
                break;
            }
            parent = parent.getParent();
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        return tabHeaderNode.snapshot(params, null);
    }
    
    private void showFeedbackPane(TabPane referencePane) {
        Pane sceneRoot = (Pane) rootContainer.getScene().getRoot();
        if (!sceneRoot.getChildren().contains(feedbackPane)) {
            sceneRoot.getChildren().add(feedbackPane);
        }
        // repositionFeedbackPane을 호출하기 전에 visible = true 설정
        feedbackPane.setVisible(true); 
        repositionFeedbackPane(referencePane);
    }

    private void repositionFeedbackPane(TabPane referencePane) {
        if (!feedbackPane.isVisible()) return;
        Node contentArea = referencePane.lookup(".tab-content-area");
        Bounds bounds = (contentArea != null) ? contentArea.localToScene(contentArea.getLayoutBounds())
                                              : referencePane.localToScene(referencePane.getLayoutBounds());
        feedbackPane.setLayoutX(bounds.getMinX());
        feedbackPane.setLayoutY(bounds.getMinY());
        feedbackPane.setPrefSize(bounds.getWidth(), bounds.getHeight());
        feedbackPane.resize(bounds.getWidth(), bounds.getHeight());
    }

    private void updatePreviewTab(TabPane tabPane, double sceneX) {
        int dropIndex = calculateDropIndex(tabPane, sceneX);
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
            tabPane.getTabs().add(safeIndex, previewTab);
        } else {
            int currentPreviewIndex = tabPane.getTabs().indexOf(previewTab);
            if (currentPreviewIndex != safeIndex) {
                tabPane.getTabs().remove(previewTab);
                int newSafeIndex = Math.min(safeIndex, tabPane.getTabs().size());
                tabPane.getTabs().add(newSafeIndex, previewTab);
            }
        }
    }
    
    private int calculateDropIndex(TabPane tabPane, double sceneX) {
        List<Node> sortedTabNodes = new ArrayList<>(tabPane.lookupAll(".tab"));
        sortedTabNodes.removeIf(n -> n.getStyleClass().contains("drag-preview-tab"));
        sortedTabNodes.sort(Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinX()));
        
        int currentIndex = 0;
        for (Node tabNode : sortedTabNodes) {
            if (sceneX < tabNode.localToScene(tabNode.getBoundsInLocal()).getCenterX()) {
                return currentIndex;
            }
            currentIndex++;
        }
        return tabPane.getTabs().size();
    }
    
    private Pane createFeedbackPane() {
        BorderPane dropPane = new BorderPane();
        dropPane.setTop(createDropZone("split-top", 80, -1));
        dropPane.setBottom(createDropZone("split-bottom", 80, -1));
        dropPane.setLeft(createDropZone("split-left", -1, 80));
        dropPane.setRight(createDropZone("split-right", -1, 80));
        dropPane.setCenter(createDropZone("merge-center", -1, -1));
        dropPane.setVisible(false);
        return dropPane;
    }

    private Region createDropZone(String zoneId, double prefHeight, double prefWidth) {
        Region zone = new Region();
        zone.setUserData(zoneId);
        zone.getStyleClass().add("drop-zone");
        if (prefHeight > 0) zone.setPrefHeight(prefHeight);
        if (prefWidth > 0) zone.setPrefWidth(prefWidth);
        
        zone.setOnDragEntered(e -> { if (draggingTab != null) { zone.getStyleClass().add("active"); e.consume(); } });
        zone.setOnDragExited(e -> { if (draggingTab != null) { zone.getStyleClass().remove("active"); e.consume(); } });
        
        return zone;
    }

    private void resetDragState() {
        draggingTab = null;
        sourcePaneForDrag = null;
        sourceIndexForDrag = -1;
        originalGraphic = null;
        dropTargetPane = null;
    }
    
    public Parent findContainerOf(Node target) {
        if (rootContainer.getItems().contains(target)) return rootContainer;
        return findContainerRecursive(rootContainer, target);
    }

    private Parent findContainerRecursive(Parent parent, Node target) {
        if (parent instanceof SplitPane sp && sp.getItems().contains(target)) return sp;
        if (parent instanceof SplitPane sp) {
            for (Node child : sp.getItems()) {
                if (child instanceof Parent p) {
                    Parent found = findContainerRecursive(p, target);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }
}