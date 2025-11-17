package com.ethis2s.util;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.EditorStateManager.UserLockInfo;
import com.ethis2s.view.editor.EditorTabView;

import javafx.animation.PauseTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.Optional;

public class EditorListenerManager {

    private final CodeArea codeArea;
    private final EditorTabView owner;
    private final EditorStateManager stateManager;
    private final String tabId;
    private final Tooltip errorTooltip = new Tooltip();
    private final ProjectController projectController;


    public EditorListenerManager(CodeArea codeArea, EditorTabView owner, EditorStateManager stateManager, String tabId, ProjectController projectController) {
        this.codeArea = codeArea;
        this.owner = owner;
        this.stateManager = stateManager;
        this.tabId = tabId;
        this.projectController = projectController;
        errorTooltip.getStyleClass().add("error-tooltip");
    }

    public void attachListeners() {
        attachFocusListener();
        attachParagraphsListener();
        attachCurrentParagraphListener();
        attachCaretPositionListener();
        attachErrorTooltipListeners();
    }

    private void attachFocusListener() {
        codeArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                owner.setActiveCodeArea(codeArea);
            }
        });
    }

    private void attachParagraphsListener() {
        final double MIN_INITIAL_WIDTH = 60.0;
        final double RIGHT_PADDING_NUM = 15.0;
        final double LEFT_PADDING_NUM = 5.0;
        final DoubleProperty lineNumberPrefWidth = new SimpleDoubleProperty(MIN_INITIAL_WIDTH);

        codeArea.getParagraphs().addListener((ListChangeListener<Object>) c -> {
            int totalLines = Math.max(1, codeArea.getParagraphs().size());
            String maxLineNumberText = String.valueOf(totalLines);
            Text text = new Text(maxLineNumberText);
            text.setFont(Font.font(ConfigManager.getInstance().get("editor", "font", String.class, "Consolas"), ConfigManager.getInstance().get("editor", "font", Double.class, 14.0)));
            double textWidth = text.getLayoutBounds().getWidth();
            double horizontalPadding = LEFT_PADDING_NUM + RIGHT_PADDING_NUM;
            double dynamicWidth = Math.ceil(textWidth + horizontalPadding);
            lineNumberPrefWidth.set(Math.max(MIN_INITIAL_WIDTH, dynamicWidth));
        });

        final String CARET_LINE_STYLE = "-fx-text-fill: #d4d4d4;";
        final String DEFAULT_LINE_STYLE = "-fx-text-fill: #585858;";
        final String ERR_LINE_STYLE = "-fx-text-fill: #ff6666 !important;";

        codeArea.setParagraphGraphicFactory(lineIndex -> {
            Label lineLabel = new Label();
            lineLabel.setFont(Font.font(ConfigManager.getInstance().get("editor", "font", String.class, "Consolas"), ConfigManager.getInstance().get("editor", "fontSize", Double.class, 14.0)));
            lineLabel.setText(String.valueOf(lineIndex + 1));
            lineLabel.getStyleClass().add("lineno");
            lineLabel.setAlignment(Pos.CENTER);
            lineLabel.setPadding(new Insets(0, RIGHT_PADDING_NUM, 0, LEFT_PADDING_NUM));
            lineLabel.prefWidthProperty().bind(lineNumberPrefWidth);
            updateLineNumberStyle(lineLabel, lineIndex, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE);
            return lineLabel;
        });
    }

    private void attachCurrentParagraphListener() {
        final String CARET_LINE_STYLE = "-fx-text-fill: #d4d4d4;";
        final String DEFAULT_LINE_STYLE = "-fx-text-fill: #585858;";
        final String ERR_LINE_STYLE = "-fx-text-fill: #ff6666 !important;";

        codeArea.currentParagraphProperty().addListener((obs, oldParagraph, newParagraph) -> {
            getLineNumberLabel(codeArea, oldParagraph).ifPresent(label -> updateLineNumberStyle(label, oldParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE));
            getLineNumberLabel(codeArea, newParagraph).ifPresent(label -> updateLineNumberStyle(label, newParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE));
        });
    }

    private void attachCaretPositionListener() {
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> codeArea.requestFollowCaret());
    }

    private void attachErrorTooltipListeners() {
        PauseTransition tooltipDelay = new PauseTransition(Duration.millis(500));
        codeArea.setOnMouseMoved(e -> {
            tooltipDelay.stop();
            errorTooltip.hide();
            tooltipDelay.setOnFinished(event -> {
                int charIndex = codeArea.hit(e.getX(), e.getY()).getCharacterIndex().orElse(-1);
                if (charIndex == -1) return;

                List<SyntaxError> errors = stateManager.getErrorsForTab(tabId);
                Optional<SyntaxError> errorOpt = errors.stream().filter(err -> {
                    if (err.line <= 0 || err.line > codeArea.getParagraphs().size()) return false;
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

    private Optional<Label> getLineNumberLabel(CodeArea codeArea, int paragraphIndex) {
        if (paragraphIndex < 0 || paragraphIndex >= codeArea.getParagraphs().size()) return Optional.empty();
        Node graphic = codeArea.getParagraphGraphic(paragraphIndex);
        if (graphic instanceof Label) return Optional.of((Label) graphic);
        return Optional.empty();
    }

    private void updateLineNumberStyle(Label lineLabel, int lineIndex, String tabId, CodeArea codeArea,
                                       String DEFAULT_LINE_STYLE, String CARET_LINE_STYLE, String ERR_LINE_STYLE) {
        final String LOCKED_BY_OTHER_STYLE = "-fx-text-fill: #ffab70 !important;";

        Optional<EditorStateManager.UserLockInfo> lockInfoOpt = stateManager.getLineLockInfo(tabId, lineIndex + 1); // stateManager uses 1-based indexing

        lineLabel.setText(String.valueOf(lineIndex + 1)); // Always set the line number without nickname

        if (lockInfoOpt.isPresent()) {
            EditorStateManager.UserLockInfo lockInfo = lockInfoOpt.get();
            String currentUserId = projectController.getCurrentUserId().orElse("");

            // Check if the lock owner is valid and not the current user
            if (lockInfo.userId != null && !"null".equals(lockInfo.userId) && !lockInfo.userId.equals(currentUserId)) {
                lineLabel.setStyle(LOCKED_BY_OTHER_STYLE);
                return; // Locked by other, so no other style applies
            }
        }

        // If not locked by other, apply normal styles
        List<SyntaxError> errors = stateManager.getErrorsForTab(tabId);
        boolean hasError = errors.stream().anyMatch(e -> e.line - 1 == lineIndex);
        if (hasError) {
            lineLabel.setStyle(ERR_LINE_STYLE);
        } else if (lineIndex == codeArea.getCurrentParagraph()) {
            lineLabel.setStyle(CARET_LINE_STYLE);
        } else {
            lineLabel.setStyle(DEFAULT_LINE_STYLE);
        }
    }
}
