package com.example.view;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import com.example.service.CompletionService;
import com.example.service.JavaCompletionService;
import com.example.service.PythonCompletionService;
import com.example.util.AntlrSyntaxHighlighter;
import com.example.util.EditorEnhancer;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TabPane.TabDragPolicy;

public class EditorTabView {

    private final TabPane editorTabs;
    private Tab welcomeTab;
    private final List<AntlrSyntaxHighlighter> activeHighlighters = new ArrayList<>();

    public EditorTabView() {
        this.welcomeTab = new Tab("Welcome");
        this.welcomeTab.setClosable(false);
        this.editorTabs = new TabPane(this.welcomeTab);
        this.editorTabs.setTabDragPolicy(TabDragPolicy.REORDER);
    }

    public void shutdownAllHighlighters() {
        activeHighlighters.forEach(AntlrSyntaxHighlighter::shutdown);
        activeHighlighters.clear();
    }

    public TabPane getTabPane() {
        return editorTabs;
    }

    public Tab getWelcomeTab() {
        return welcomeTab;
    }
    
    public void setWelcomeTabContent(Node content) {
        welcomeTab.setContent(content);
    }

    public void selectWelcomeTab() {
        editorTabs.getSelectionModel().select(welcomeTab);
    }
    
    public void closeAllClosableTabs() {
        shutdownAllHighlighters();
        editorTabs.getTabs().removeIf(Tab::isClosable);
        if (editorTabs.getTabs().isEmpty()) {
            this.welcomeTab = new Tab("Welcome");
            this.welcomeTab.setClosable(false);
            editorTabs.getTabs().add(welcomeTab);
        } else {
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

    public void openFileInEditor(String filePath, String content) {
        String fileName = Paths.get(filePath).getFileName().toString();
        String tabId = "file-" + filePath;

        if (hasTab(tabId)) {
            selectTab(tabId);
            return;
        }

        CodeArea codeArea = new CodeArea();
        
        codeArea.getStyleClass().add("code-area");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);

        String fileExtension = getFileExtension(filePath);

        // ANTLR-based Syntax Highlighting
        AntlrSyntaxHighlighter highlighter = new AntlrSyntaxHighlighter(codeArea, fileExtension);
        activeHighlighters.add(highlighter);

        codeArea.replaceText(0, 0, content);

        // Auto-Completion
        CompletionService completionService = null;
        switch (fileExtension.toLowerCase()) {
            case "java":
                completionService = new JavaCompletionService();
                break;
            case "py":
                completionService = new PythonCompletionService();
                break;
            // Add other languages here in the future
        }
        EditorEnhancer.enable(codeArea, completionService);
        
        Runnable onClose = () -> {
            highlighter.shutdown();
            activeHighlighters.remove(highlighter);
        };
        openTabWithCloseCallback(tabId, fileName, scrollPane, onClose);
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }
}
