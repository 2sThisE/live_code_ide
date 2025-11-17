package com.ethis2s.util;

import com.ethis2s.controller.MainController;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.view.editor.EditorTabView;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.fxmisc.richtext.CodeArea;

import java.util.Set;

public class TabPaneFocusManager {

    private final MainController mainController;
    private final EditorTabView editorTabView;
    private final EditorStateManager stateManager;
    private final Set<TabPane> managedTabPanes;
    private TabPane activeTabPane;

    public TabPaneFocusManager(MainController mainController, EditorTabView editorTabView, EditorStateManager stateManager, Set<TabPane> managedTabPanes) {
        this.mainController = mainController;
        this.editorTabView = editorTabView;
        this.stateManager = stateManager;
        this.managedTabPanes = managedTabPanes;
    }

    public void registerTabPane(TabPane tabPane) {
        if (activeTabPane == null) {
            activeTabPane = tabPane;
        }

        Runnable updateAndResearch = () -> {
            Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();

            if (selectedTab != null && selectedTab.getUserData() instanceof UserProjectsInfo projectInfo) {
                mainController.setCurrentActiveProject(projectInfo);
            }

            CodeArea newActiveCodeArea = null;
            if (selectedTab != null && selectedTab.getId() != null) {
                newActiveCodeArea = stateManager.getCodeArea(selectedTab.getId()).orElse(null);
            }
            editorTabView.setActiveCodeArea(newActiveCodeArea);

            String query = mainController.getSearchQuery();
            if (query != null && !query.isEmpty()) {
                mainController.triggerSearch();
            }
        };

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (tabPane.isFocused() || activeTabPane == tabPane) {
                updateAndResearch.run();
            }
        });

        tabPane.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                activeTabPane = tabPane;
                updateAndResearch.run();
            }
        });
    }

    public TabPane getActiveTabPane() {
        return activeTabPane;
    }

    public void setActiveTabPane(TabPane tabPane) {
        this.activeTabPane = tabPane;
    }
}
