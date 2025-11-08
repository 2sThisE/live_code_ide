package com.example.view;

import com.example.util.HybridManager; // HybridManager를 import
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TabPane.TabDragPolicy;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 애플리케이션의 메인 에디터 탭들을 관리하는 최종 관리자 클래스.
 * 각 탭에 대한 모든 언어 서비스(하이라이팅, 문법 검사, 자동 완성 등)를
 * HybridManager를 통해 위임하여 관리합니다.
 */
public class EditorTabView {

    private final TabPane editorTabs;
    private Tab welcomeTab;
    
    // [수정] 이제 Tm4eSyntaxHighlighter가 아닌, HybridManager를 추적합니다.
    private final List<HybridManager> activeManagers = new ArrayList<>();

    public EditorTabView() {
        this.welcomeTab = new Tab("Welcome");
        this.welcomeTab.setClosable(false);
        this.editorTabs = new TabPane(this.welcomeTab);
        this.editorTabs.setTabDragPolicy(TabDragPolicy.REORDER);
    }

    /**
     * 애플리케이션 종료 시, 모든 활성 매니저의 백그라운드 스레드를 안전하게 종료합니다.
     */
    public void shutdownAllManagers() {
        activeManagers.forEach(HybridManager::shutdown);
        activeManagers.clear();
        System.out.println("[DEBUG] EditorTabView: All HybridManagers have been shut down.");
    }

    public TabPane getTabPane() {
        return editorTabs;
    }
    
    // ... (getWelcomeTab, setWelcomeTabContent, selectWelcomeTab 등 다른 메소드는 변경 없음) ...
    public Tab getWelcomeTab() { return welcomeTab; }
    public void setWelcomeTabContent(Node content) { welcomeTab.setContent(content); }
    public void selectWelcomeTab() { editorTabs.getSelectionModel().select(welcomeTab); }

    public void closeAllClosableTabs() {
        shutdownAllManagers();
        editorTabs.getTabs().removeIf(Tab::isClosable);
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

    // ... (openTab, openTabWithCloseCallback 메소드는 단순 탭 관리를 위해 유지) ...
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
     * 지정된 경로의 파일을 새로운 에디터 탭으로 엽니다.
     * HybridManager를 생성하여 구문 강조, 문법 검사, 자동 완성을 포함한 모든 에디터 기능을 활성화합니다.
     * @param filePath 파일의 전체 경로
     * @param content 파일의 내용
     */
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

        // --- 여기가 모든 마법이 일어나는 곳입니다 ---
        
        // 1. HybridManager를 생성합니다. 이 한 줄이 모든 것을 시작합니다.
        //    TM4E 하이라이팅, ANTLR 서비스, 오류 밑줄, 자동 완성, 괄호 완성까지
        //    모든 기능이 이 안에서 유기적으로 설정됩니다.
        HybridManager manager = new HybridManager(codeArea, fileExtension);
        
        // 2. 생성된 매니저를 "활성 목록"에 추가하여 추적합니다.
        activeManagers.add(manager);
        System.out.println("[DEBUG] EditorTabView: HybridManager created and now tracking for: " + fileName);

        codeArea.replaceText(0, 0, content);

        // 3. 탭이 닫힐 때, 해당 탭의 매니저만 정확히 종료하도록 콜백을 설정합니다.
        Runnable onClose = () -> {
            manager.shutdown();
            activeManagers.remove(manager);
            System.out.println("[DEBUG] EditorTabView: HybridManager shut down and removed for: " + fileName);
        };
        
        // 4. 새로운 탭을 엽니다. (openTabWithCloseCallback을 재사용하지 않고 직접 생성)
        Tab newTab = new Tab(fileName, scrollPane);
        newTab.setId(tabId);
        newTab.setClosable(true);
        newTab.setOnClosed(e -> onClose.run()); // 탭의 '닫기' 이벤트에 콜백 연결
        
        editorTabs.getTabs().add(newTab);
        editorTabs.getSelectionModel().select(newTab);
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }
}