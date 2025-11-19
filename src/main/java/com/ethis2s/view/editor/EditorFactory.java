package com.ethis2s.view.editor;

import com.ethis2s.controller.MainController;
import com.ethis2s.controller.ProjectController;
import com.ethis2s.service.ChangeInitiator;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.EditorListenerManager;
import com.ethis2s.util.EditorStateManager;
import com.ethis2s.util.HybridManager;
import com.ethis2s.util.OTManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.nio.file.Paths;
import java.util.Base64;
import com.ethis2s.service.RemoteCursorManager;

/**
 * 파일에 대한 새로운 에디터(CodeArea) 인스턴스를 생성하고 설정하는 클래스입니다.
 * 구문 강조, 에러 처리, 줄 번호, UI 스타일링 등을 담당합니다.
 */
public class EditorFactory {

    private final MainController mainController;
    private final EditorStateManager stateManager;
    private final EditorTabView owner;
    private final ProjectController projectController;

    private final Tooltip errorTooltip = new Tooltip();

    public EditorFactory(MainController mainController, EditorStateManager stateManager, EditorTabView owner, ProjectController projectController) {
        this.mainController = mainController;
        this.stateManager = stateManager;
        this.owner = owner;
        this.projectController = projectController;
        errorTooltip.getStyleClass().add("error-tooltip");
    }

    public Node createEditorForFile(String filePath, String content, String tabId, long initialVersion) {
        String fileName = Paths.get(filePath).getFileName().toString();
        CodeArea codeArea = new CodeArea();
        codeArea.getStyleClass().add("code-area");

        applyStylesToCodeArea(codeArea);

        HybridManager manager = new HybridManager(
            codeArea,
            getFileExtension(filePath),
            (errors) -> Platform.runLater(() -> owner.handleErrorUpdate(tabId, fileName, errors)),
            mainController::notifyAntlrTaskStarted,
            mainController::notifyAntlrTaskFinished,
            projectController,
            filePath,
            stateManager,
            initialVersion,
            tabId
        );
        OTManager otManager = new OTManager(initialVersion, projectController, manager, filePath);
        stateManager.registerOTManager(tabId, otManager);

        stateManager.registerTab(tabId, fileName, codeArea, manager);

        EditorListenerManager listenerManager = new EditorListenerManager(codeArea, owner, stateManager, tabId, projectController);
        listenerManager.attachListeners();

        // Create an overlay pane for remote cursors
        Pane cursorOverlay = new Pane();
        cursorOverlay.setMouseTransparent(true); // Clicks should go through to the CodeArea

        // Create the RemoteCursorManager
        RemoteCursorManager cursorManager = new RemoteCursorManager(codeArea, cursorOverlay, stateManager);
        stateManager.registerCursorManager(tabId, cursorManager);

        // Move caret to the beginning before inserting content
        
        manager.controlledReplaceText(0, 0, content, ChangeInitiator.SYSTEM);
        Platform.runLater(manager::requestImmediateAnalysis);
        manager.resetInitiatorToUser(); // Explicitly reset initiator after initial load
        
        // Notify the manager that the initial content is loaded and trigger the first analysis
        


        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        
        // Create a StackPane to hold the editor and the cursor overlay
        StackPane stackPane = new StackPane(scrollPane, cursorOverlay);
        return stackPane;
    }
    
    public void reapplyStylesToAllEditors() {
        for (CodeArea codeArea : stateManager.getAllCodeAreas()) {
            applyStylesToCodeArea(codeArea);
        }
    }

    private void applyStylesToCodeArea(CodeArea codeArea) {
        final int FONT_SIZE = ConfigManager.getInstance().get("editor", "fontSize", Integer.class, 14);
        final String FONT_FAMILY = ConfigManager.getInstance().get("editor", "font", String.class, "Consolas");
        final Font CODE_FONT = Font.font(FONT_FAMILY, FONT_SIZE);
        final double LINE_SPACING_FACTOR = 0.4;

        Text tempText = new Text("Ag");
        tempText.setFont(CODE_FONT);
        double fontHeight = tempText.getLayoutBounds().getHeight();
        double targetLineHeight = Math.ceil(fontHeight * (1 + LINE_SPACING_FACTOR));
        double verticalPadding = (targetLineHeight - fontHeight) / 2.0;
        double caretHeight = targetLineHeight + 1;

        String tabSizeCss = String.format(".paragraph-text { -fx-tab-size: %d; }", ConfigManager.getInstance().get("editor", "tabSize", Integer.class,4));
        String dynamicStylingCss = String.format(
            ".text { -fx-font-family: '%s'; -fx-font-size: %dpx; }" +
            ".paragraph-box { -fx-min-height: %.1fpx; -fx-max-height: %.1fpx; -fx-pref-height: %.1fpx; -fx-display: flex; -fx-alignment: center-left; -fx-padding: 0 0 0 10px; }" +
            ".caret { -fx-shape: \"M0,0 H1 V%.1f\"; -fx-stroke-width: 2px; }" +
            ".syntax-error { -rtfx-background-color: rgba(255, 71, 71, 0.44); -fx-padding: %.1fpx 0; }",
            FONT_FAMILY, FONT_SIZE, targetLineHeight, targetLineHeight, targetLineHeight, caretHeight, verticalPadding
        );

        String combinedCss = tabSizeCss + "\n" + dynamicStylingCss;
        String dataUri = "data:text/css;base64," + Base64.getEncoder().encodeToString(combinedCss.getBytes());
        
        codeArea.getStylesheets().clear();
        codeArea.getStylesheets().add(dataUri);
    }

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }
}