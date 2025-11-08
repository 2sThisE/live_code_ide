package com.ethis2s.view;

import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.HybridManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TabPane.TabDragPolicy;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class EditorTabView {

    private final TabPane editorTabs;
    private Tab welcomeTab;
    private final List<HybridManager> activeManagers = new ArrayList<>();
    
    // 각 탭(tabId)별로 에러가 발생한 라인 번호들을 저장하는 맵
    private final Map<String, Set<Integer>> tabErrorLines = new HashMap<>();
    // 각 탭(tabId)별로 CodeArea 인스턴스를 저장하는 맵
    private final Map<String, CodeArea> codeAreaMap = new HashMap<>();

    public EditorTabView() {
        this.welcomeTab = new Tab("Welcome");
        this.welcomeTab.setClosable(false);
        this.editorTabs = new TabPane(this.welcomeTab);
        this.editorTabs.setTabDragPolicy(TabDragPolicy.REORDER);
    }

    public void shutdownAllManagers() {
        activeManagers.forEach(HybridManager::shutdown);
        activeManagers.clear();
    }

    public TabPane getTabPane() {
        return editorTabs;
    }
    
    public Tab getWelcomeTab() { return welcomeTab; }
    public void setWelcomeTabContent(Node content) { welcomeTab.setContent(content); }
    public void selectWelcomeTab() { editorTabs.getSelectionModel().select(welcomeTab); }

    public void closeAllClosableTabs() {
        shutdownAllManagers();
        editorTabs.getTabs().removeIf(Tab::isClosable);
        tabErrorLines.clear();
        codeAreaMap.clear();
        if (editorTabs.getTabs().isEmpty()) {
            this.welcomeTab = new Tab("Welcome");
            this.welcomeTab.setClosable(false);
            editorTabs.getTabs().add(welcomeTab);
        } else if (!editorTabs.getTabs().contains(welcomeTab)) {
            this.welcomeTab = editorTabs.getTabs().get(0);
            this.welcomeTab.setClosable(false);
        }
    }

    public boolean hasTab(String tabId) {
        return editorTabs.getTabs().stream().anyMatch(tab -> tabId.equals(tab.getId()));
    }

    public void selectTab(String tabId) {
        editorTabs.getTabs().stream()
            .filter(tab -> tabId.equals(tab.getId()))
            .findFirst()
            .ifPresent(tab -> editorTabs.getSelectionModel().select(tab));
    }

    public void openTab(String tabId, String title, Node content) {
        Tab newTab = new Tab(title);
        newTab.setId(tabId);
        newTab.setContent(content);
        newTab.setClosable(true);
        editorTabs.getTabs().add(newTab);
        editorTabs.getSelectionModel().select(newTab);
    }
    public void openTabWithCloseCallback(String tabId, String title, Node content, Runnable onClose) {
        Tab newTab = new Tab(title);
        newTab.setId(tabId);
        newTab.setContent(content);
        newTab.setClosable(true);
        newTab.setOnClosed(e -> onClose.run());
        editorTabs.getTabs().add(newTab);
        editorTabs.getSelectionModel().select(newTab);
    }

    /**
     * HybridManager로부터 에러 정보를 받아 UI를 업데이트하는 콜백 메소드.
     */
    private void handleErrorUpdate(String tabId, String fileName, List<SyntaxError> errors) {
        Tab tab = editorTabs.getTabs().stream().filter(t -> tabId.equals(t.getId())).findFirst().orElse(null);
        if (tab == null) return;

        Label tabLabel = (Label) tab.getGraphic();
        if (tabLabel == null) {
            tabLabel = new Label();
            tab.setGraphic(tabLabel);
        }

        Set<Integer> errorLines = tabErrorLines.get(tabId);
        errorLines.clear();

        if (errors.isEmpty()) {
            tabLabel.setText(fileName);
            tabLabel.getStyleClass().remove("tab-label-error");
        } else {
            tabLabel.setText(fileName + " (" + errors.size() + ")");
            if (!tabLabel.getStyleClass().contains("tab-label-error")) {
                tabLabel.getStyleClass().add("tab-label-error");
            }
            errorLines.addAll(errors.stream().map(e -> e.line - 1).collect(Collectors.toSet()));
        }

        // 라인 번호 UI를 강제로 다시 그리도록 요청
        CodeArea codeArea = codeAreaMap.get(tabId);
        if (codeArea != null) {
            // setParagraphGraphicFactory는 IntFunction<? extends Node>를 받는다.
            // 따라서, 올바른 람다식을 직접 전달해야 한다.
            codeArea.setParagraphGraphicFactory(lineIndex -> {
                Label lineLabel = new Label(String.valueOf(lineIndex + 1));
                lineLabel.getStyleClass().add("lineno");
                if (tabErrorLines.getOrDefault(tabId, Set.of()).contains(lineIndex)) {
                    lineLabel.getStyleClass().add("lineno-error");
                }
                return lineLabel;
            });
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
        codeAreaMap.put(tabId, codeArea); // 맵에 CodeArea 등록
        tabErrorLines.put(tabId, new HashSet<>()); // 맵에 에러 라인 Set 등록

        // setParagraphGraphicFactory는 IntFunction<? extends Node>를 받는다.
        codeArea.setParagraphGraphicFactory(lineIndex -> {
            Label lineLabel = new Label(String.valueOf(lineIndex + 1));
            lineLabel.getStyleClass().add("lineno");
            if (tabErrorLines.getOrDefault(tabId, Set.of()).contains(lineIndex)) {
                lineLabel.getStyleClass().add("lineno-error");
            }
            return lineLabel;
        });
        
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        String fileExtension = getFileExtension(filePath);

        HybridManager manager = new HybridManager(codeArea, fileExtension, (errors) -> {
            Platform.runLater(() -> handleErrorUpdate(tabId, fileName, errors));
        });
        
        activeManagers.add(manager);
        codeArea.replaceText(0, 0, content);

        Runnable onClose = () -> {
            manager.shutdown();
            activeManagers.remove(manager);
            tabErrorLines.remove(tabId); // 탭이 닫히면 맵에서 제거
            codeAreaMap.remove(tabId);   // 탭이 닫히면 맵에서 제거
        };
        
        Tab newTab = new Tab(null, scrollPane);
        Label tabLabel = new Label(fileName);
        newTab.setGraphic(tabLabel);
        newTab.setId(tabId);
        newTab.setClosable(true);
        newTab.setOnClosed(e -> onClose.run());
        
        editorTabs.getTabs().add(newTab);
        editorTabs.getSelectionModel().select(newTab);
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }
}