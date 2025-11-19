package com.ethis2s.util;

import com.ethis2s.controller.ProjectController;

/**
 * 특정 에디터 탭에 대한 모든 공통 자원과 상태를 담는 불변(immutable) 컨텍스트 객체입니다.
 * 여러 관리자 클래스에 걸쳐 반복적으로 전달되는 파라미터들을 하나로 묶어 코드의 복잡성을 줄입니다.
 */
public final class EditorContext {

    private final ProjectController projectController;
    private final EditorStateManager stateManager;
    private final String tabId;
    private final String filePath;

    public EditorContext(ProjectController projectController, EditorStateManager stateManager, String tabId, String filePath) {
        this.projectController = projectController;
        this.stateManager = stateManager;
        this.tabId = tabId;
        this.filePath = filePath;
    }

    // 각 자원에 접근할 수 있는 Getter 메서드들
    public ProjectController getProjectController() { return projectController; }
    public EditorStateManager getStateManager() { return stateManager; }
    public String getTabId() { return tabId; }
    public String getFilePath() { return filePath; }
}
