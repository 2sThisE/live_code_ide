package com.ethis2s.service;

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

import com.ethis2s.view.editor.EditorTabView;

/**
 * 탭의 드래그 앤 드롭, 창 분할 및 병합 로직을 처리하는 클래스입니다.
 */
public class TabDragDropManager {
    public enum SplitDirection { TOP, BOTTOM, LEFT, RIGHT }
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
        // onDragOver, onDragExited 핸들러는 이전 버전과 동일하게 유지합니다.
        tabPane.setOnDragOver(event -> {
            if (draggingTab == null) return;
            event.acceptTransferModes(TransferMode.MOVE);
            this.dropTargetPane = tabPane;
            Node headerArea = tabPane.lookup(".tab-header-area");
            if (headerArea == null) {
                feedbackPane.setVisible(true);
                repositionFeedbackPane(tabPane);
                return;
            }
            Bounds headerBoundsInLocal = headerArea.getBoundsInLocal();
            if (event.getY() <= headerBoundsInLocal.getMaxY()) {
                feedbackPane.setVisible(false);
                updatePreviewTab(tabPane, event.getSceneX());
            } else {
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
            event.consume();
        });

        tabPane.setOnDragExited(event -> {
            Node targetNode = event.getPickResult().getIntersectedNode();
            boolean mouseIsOverFeedbackPane = false;
            if (targetNode != null) {
                Parent p = targetNode.getParent();
                while(p != null) {
                    if (p == feedbackPane) {
                        mouseIsOverFeedbackPane = true;
                        break;
                    }
                    p = p.getParent();
                }
            }
            if (previewTab != null && previewTab.getTabPane() == tabPane) {
                tabPane.getTabs().remove(previewTab);
            }
            if (!mouseIsOverFeedbackPane) {
                feedbackPane.setVisible(false);
            }
        });

        tabPane.setOnDragDropped(event -> {
            if (draggingTab == null) return;
            Node headerArea = tabPane.lookup(".tab-header-area");
            if (headerArea != null) {
                Bounds headerBoundsInLocal = headerArea.getBoundsInLocal();
                if (event.getY() <= headerBoundsInLocal.getMaxY()) {
                    if (previewTab != null && previewTab.getTabPane() != null) {
                        previewTab.getTabPane().getTabs().remove(previewTab);
                    }
                    // 새로 수정한 calculateDropIndex가 여기서 사용됩니다.
                    int dropIndex = calculateDropIndex(tabPane, event.getSceneX());
                    tabPane.getTabs().add(dropIndex, draggingTab);
                    tabPane.getSelectionModel().select(draggingTab);
                    event.setDropCompleted(true);
                }
            }
            event.consume();
        });
    }

    public void splitTab(Tab tab, SplitDirection direction) {
        if (tab.getTabPane() != null) {
            splitTab(tab, tab.getTabPane(), direction);
        }
    }
    
    private void splitTab(Tab tab, TabPane targetPane, SplitDirection direction) {
        if (targetPane == null) return;

        TabPane newPane = editorTabView.createNewTabPane();
        Parent container = findContainerOf(targetPane);
        if (!(container instanceof SplitPane parentSplitPane)) {
            System.err.println("Cannot split: target pane is not in a SplitPane.");
            return;
        }

        int targetIndex = parentSplitPane.getItems().indexOf(targetPane);
        
        // 방향에 따라 Orientation과 새 창의 삽입 위치를 결정
        Orientation orientation = (direction == SplitDirection.LEFT || direction == SplitDirection.RIGHT)
                                ? Orientation.HORIZONTAL : Orientation.VERTICAL;
        boolean addBefore = (direction == SplitDirection.LEFT || direction == SplitDirection.TOP);

        if (parentSplitPane.getOrientation() == orientation) {
            // 부모 SplitPane과 방향이 같으면, 바로 옆에 추가
            int insertionIndex = addBefore ? targetIndex : targetIndex + 1;
            parentSplitPane.getItems().add(insertionIndex, newPane);
            parentSplitPane.setDividerPosition(addBefore ? targetIndex -1 : targetIndex, 0.5);

        } else {
            // 부모 SplitPane과 방향이 다르면, 새로운 SplitPane으로 감싸서 교체
            SplitPane nestedSplitPane = new SplitPane();
            nestedSplitPane.setOrientation(orientation);
            
            // 방향에 따라 노드 추가 순서를 변경
            if (addBefore) {
                nestedSplitPane.getItems().addAll(newPane, targetPane);
            } else {
                nestedSplitPane.getItems().addAll(targetPane, newPane);
            }
            
            parentSplitPane.getItems().set(targetIndex, nestedSplitPane);
            Platform.runLater(() -> nestedSplitPane.setDividerPosition(0, 0.5));
        }

        // 탭을 새로운 Pane으로 이동
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
                    case "split-top"    -> splitTab(draggingTab, dropTargetPane, SplitDirection.TOP);
                    case "split-bottom" -> splitTab(draggingTab, dropTargetPane, SplitDirection.BOTTOM);
                    case "split-left"   -> splitTab(draggingTab, dropTargetPane, SplitDirection.LEFT);
                    case "split-right"  -> splitTab(draggingTab, dropTargetPane, SplitDirection.RIGHT);
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
        // 화면에 보이는 실제 탭(.tab 스타일을 가진 노드) 목록을 가져옵니다.
        List<Node> realTabNodes = new ArrayList<>(tabPane.lookupAll(".tab"));
        
        // 이 목록에서 미리보기 탭은 확실히 제외합니다.
        realTabNodes.removeIf(n -> n.getStyleClass().contains("drag-preview-tab"));
        
        // 탭들을 화면상 X좌표 순서대로 정렬합니다.
        realTabNodes.sort(Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinX()));

        int index = 0;
        for (Node tabNode : realTabNodes) {
            // 마우스의 X좌표가 특정 탭의 중심보다 왼쪽에 있다면, 바로 그 위치가 삽입될 인덱스입니다.
            if (sceneX < tabNode.localToScene(tabNode.getBoundsInLocal()).getCenterX()) {
                return index; // 위치를 찾았으면 즉시 반환
            }
            index++;
        }

        // 반복문이 끝까지 돌았다면, 마우스가 모든 탭의 오른쪽에 있다는 의미입니다.
        // 즉, 맨 마지막 위치에 삽입해야 합니다.
        // 맨 마지막 인덱스는 실제 탭의 개수와 같습니다.
        return realTabNodes.size();
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