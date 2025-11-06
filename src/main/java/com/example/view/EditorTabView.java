package com.example.view;
import java.nio.file.Paths;
import java.util.Optional;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import com.example.util.SyntaxHighlighter;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TabPane.TabDragPolicy;

public class EditorTabView {

    private final TabPane editorTabs;
    private Tab welcomeTab;

    public EditorTabView() {
        this.welcomeTab = new Tab("Welcome");
        this.welcomeTab.setClosable(false);
        this.editorTabs = new TabPane(this.welcomeTab);
        this.editorTabs.setTabDragPolicy(TabDragPolicy.REORDER);
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
        SyntaxHighlighter highlighter = new SyntaxHighlighter(codeArea, fileExtension);

        // It's important to set the text *after* the highlighter is attached,
        // so the initial highlighting gets triggered.
        codeArea.replaceText(0, 0, content);

        openTabWithCloseCallback(tabId, fileName, scrollPane, highlighter::shutdown);
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }
}
