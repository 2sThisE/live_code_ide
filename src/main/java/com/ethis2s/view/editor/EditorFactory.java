package com.ethis2s.view.editor;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.HybridManager;
import com.ethis2s.view.EditorTabView;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 파일에 대한 새로운 에디터(CodeArea) 인스턴스를 생성하고 설정하는 클래스입니다.
 * 구문 강조, 에러 처리, 줄 번호, UI 스타일링 등을 담당합니다.
 */
public class EditorFactory {

    private final MainController mainController;
    private final EditorStateManager stateManager;
    private final EditorTabView owner;

    private final Tooltip errorTooltip = new Tooltip();

    public EditorFactory(MainController mainController, EditorStateManager stateManager, EditorTabView owner) {
        this.mainController = mainController;
        this.stateManager = stateManager;
        this.owner = owner;
        errorTooltip.getStyleClass().add("error-tooltip");
    }

    public VirtualizedScrollPane<CodeArea> createEditorForFile(String filePath, String content, String tabId) {
        String fileName = java.nio.file.Paths.get(filePath).getFileName().toString();
        CodeArea codeArea = new CodeArea();
        codeArea.getStyleClass().add("code-area");

        HybridManager manager = new HybridManager(
            codeArea,
            getFileExtension(filePath),
            (errors) -> Platform.runLater(() -> owner.handleErrorUpdate(tabId, fileName, errors)),
            mainController::notifyAntlrTaskStarted,
            mainController::notifyAntlrTaskFinished
        );

        stateManager.registerTab(tabId, fileName, codeArea, manager);

        setupEditorFeatures(codeArea, tabId);
        setupErrorTooltip(codeArea, tabId);

        codeArea.replaceText(0, 0, content);
        return new VirtualizedScrollPane<>(codeArea);
    }
    
    public void reapplyStylesToAllEditors() {
        for (CodeArea codeArea : stateManager.getAllCodeAreas()) {
            applyStylesToCodeArea(codeArea);
        }
    }

    private void setupEditorFeatures(CodeArea codeArea, String tabId) {
        applyStylesToCodeArea(codeArea);

        codeArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                owner.setActiveCodeArea(codeArea);
            }
        });

        final double MIN_INITIAL_WIDTH = 60.0;
        final double RIGHT_PADDING_NUM = 15.0;
        final double LEFT_PADDING_NUM = 5.0;
        final DoubleProperty lineNumberPrefWidth = new SimpleDoubleProperty(MIN_INITIAL_WIDTH);

        codeArea.getParagraphs().addListener((ListChangeListener<Object>) c -> {
            int totalLines = Math.max(1, codeArea.getParagraphs().size());
            String maxLineNumberText = String.valueOf(totalLines);
            Text text = new Text(maxLineNumberText);
            text.setFont(Font.font(ConfigManager.getInstance().getFontFamily(), ConfigManager.getInstance().getFontSize()));
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
            lineLabel.setFont(Font.font(ConfigManager.getInstance().getFontFamily(), ConfigManager.getInstance().getFontSize()));
            lineLabel.setText(String.valueOf(lineIndex + 1));
            lineLabel.getStyleClass().add("lineno");
            lineLabel.setAlignment(Pos.CENTER);
            lineLabel.setPadding(new Insets(0, RIGHT_PADDING_NUM, 0, LEFT_PADDING_NUM));
            lineLabel.prefWidthProperty().bind(lineNumberPrefWidth);
            updateLineNumberStyle(lineLabel, lineIndex, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE);
            return lineLabel;
        });

        codeArea.currentParagraphProperty().addListener((obs, oldParagraph, newParagraph) -> {
            getLineNumberLabel(codeArea, oldParagraph).ifPresent(label -> updateLineNumberStyle(label, oldParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE));
            getLineNumberLabel(codeArea, newParagraph).ifPresent(label -> updateLineNumberStyle(label, newParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE));
        });

        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> codeArea.requestFollowCaret());
    }

    private void applyStylesToCodeArea(CodeArea codeArea) {
        ConfigManager configManager = ConfigManager.getInstance();
        final int FONT_SIZE = configManager.getFontSize();
        final String FONT_FAMILY = configManager.getFontFamily();
        final Font CODE_FONT = Font.font(FONT_FAMILY, FONT_SIZE);
        final double LINE_SPACING_FACTOR = 0.4;

        Text tempText = new Text("Ag");
        tempText.setFont(CODE_FONT);
        double fontHeight = tempText.getLayoutBounds().getHeight();
        double targetLineHeight = Math.ceil(fontHeight * (1 + LINE_SPACING_FACTOR));
        double verticalPadding = (targetLineHeight - fontHeight) / 2.0;
        double caretHeight = targetLineHeight + 1;

        String tabSizeCss = String.format(".paragraph-text { -fx-tab-size: %d; }", configManager.getTabSize());
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

    private void setupErrorTooltip(CodeArea codeArea, String tabId) {
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

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }
}