package com.ethis2s.view;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.AntlrLanguageService.SyntaxError;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.HybridManager;
import com.ethis2s.view.ProblemsView.Problem;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.control.TabPane.TabDragPolicy;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class EditorTabView {

    private final TabPane editorTabs;
    private Tab welcomeTab;
    private final List<HybridManager> activeManagers = new ArrayList<>();
    private final MainController mainController;
    
    private final Map<String, List<SyntaxError>> tabErrors = new HashMap<>();
    private final Map<String, String> tabFileNames = new HashMap<>();
    private final Map<String, CodeArea> codeAreaMap = new HashMap<>();
    
    private final Tooltip errorTooltip = new Tooltip();
    private final PauseTransition tooltipDelay = new PauseTransition(Duration.millis(500));

    public EditorTabView(MainController mainController) {
        this.mainController = mainController;
        this.welcomeTab = new Tab("Welcome");
        this.welcomeTab.setClosable(false);
        this.editorTabs = new TabPane(this.welcomeTab);
        this.editorTabs.setTabDragPolicy(TabDragPolicy.REORDER);
        errorTooltip.getStyleClass().add("error-tooltip");
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
        tabErrors.clear();
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

    private void aggregateAndSendProblems() {
        List<Problem> allProblems = new ArrayList<>();
        for (Map.Entry<String, List<SyntaxError>> entry : tabErrors.entrySet()) {
            String tabId = entry.getKey();
            String fileName = tabFileNames.get(tabId);
            List<SyntaxError> errors = entry.getValue();

            String filePath = null;
            if (tabId.startsWith("file-")) {
                filePath = tabId.substring(5);
            }

            if (filePath != null && fileName != null) {
                for (SyntaxError error : errors) {
                    allProblems.add(new Problem(filePath, fileName, error));
                }
            }
        }
        mainController.updateProblems(allProblems);
    }

    private void handleErrorUpdate(String tabId, String fileName, List<SyntaxError> errors) {
        tabErrors.put(tabId, errors);
        aggregateAndSendProblems();

        Tab tab = editorTabs.getTabs().stream().filter(t -> tabId.equals(t.getId())).findFirst().orElse(null);
        if (tab == null) return;

        Label tabLabel = (Label) tab.getGraphic();
        if (tabLabel == null) {
            tabLabel = new Label();
            tab.setGraphic(tabLabel);
        }

        if (errors.isEmpty()) {
            tabLabel.setText(fileName);
            tabLabel.getStyleClass().remove("tab-label-error");
        } else {
            tabLabel.setText(fileName + " (" + errors.size() + ")");
            if (!tabLabel.getStyleClass().contains("tab-label-error")) {
                tabLabel.getStyleClass().add("tab-label-error");
            }
        }

        CodeArea codeArea = codeAreaMap.get(tabId);
        if (codeArea != null) {
            // The existing factory now knows how to handle errors.
            // We just need to trigger a refresh of the paragraph graphics.
            codeArea.setParagraphGraphicFactory(codeArea.getParagraphGraphicFactory());
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
        
        String dynamicCSS = String.format(".paragraph-text { -fx-tab-size: %d; }", ConfigManager.TAB_SIZE);
        String dataUri = "data:text/css;base64," + Base64.getEncoder().encodeToString(dynamicCSS.getBytes());
        codeArea.getStylesheets().add(dataUri);
        codeAreaMap.put(tabId, codeArea);
        tabErrors.put(tabId, new ArrayList<>());
        tabFileNames.put(tabId, fileName);

        setupEditorFeatures(codeArea, tabId);
        
        setupErrorTooltip(codeArea, tabId);

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
            tabErrors.remove(tabId);
            codeAreaMap.remove(tabId);
            mainController.updateProblems(new ArrayList<>());
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

    private void setupErrorTooltip(CodeArea codeArea, String tabId) {
        codeArea.setOnMouseMoved(e -> {
            tooltipDelay.stop();
            errorTooltip.hide();
            tooltipDelay.setOnFinished(event -> {
                int charIndex = codeArea.hit(e.getX(), e.getY()).getCharacterIndex().orElse(-1);
                if (charIndex == -1) return;

                List<SyntaxError> errors = tabErrors.get(tabId);
                if (errors == null) return;

                Optional<SyntaxError> errorOpt = errors.stream().filter(err -> {
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

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? "" : filename.substring(dotIndex + 1);
    }

    public void navigateTo(String filePath, int line, int column) {
        String tabId = "file-" + filePath;
        if (!hasTab(tabId)) {
            System.out.println("File not open: " + filePath);
            return;
        }

        selectTab(tabId);
        CodeArea codeArea = codeAreaMap.get(tabId);
        if (codeArea != null) {
            // line is 1-based, moveTo is 0-based
            codeArea.moveTo(line - 1, column);
            codeArea.requestFollowCaret(); // Make sure the caret is visible
            codeArea.requestFocus();
        }
    }
    

    private void setupEditorFeatures(CodeArea codeArea, String tabId) {
        // =================================================================================
        // --- 1. 스타일의 "단일 소스(Single Source of Truth)" 정의 ---
        // =================================================================================
        final int FONT_SIZE = 16;
        final String FONT_FAMILY = "Consolas";
        final Font CODE_FONT = Font.font(FONT_FAMILY, FONT_SIZE);
        final Font LINE_NUMBER_FONT = Font.font(FONT_FAMILY, FONT_SIZE);
        final double LINE_SPACING_FACTOR = 0.4; // 줄 간격을 약간 늘려 가독성 확보
        final double MIN_INITIAL_WIDTH = 60.0;
        final double RIGHT_PADDING_NUM = 15.0;
        final double LEFT_PADDING_NUM = 5.0;

        // =================================================================================
        // --- 2. 폰트 메트릭스를 기반으로 동적 CSS 및 높이 계산 (개선된 방식) ---
        // =================================================================================

        // 폰트의 전체 높이를 더 정확하게 측정하기 위해 FontMetrics 사용 (JavaFX 8u60 이상 권장)
        // 또는 Text 객체를 사용하되, 더 안정적인 높이 계산을 적용합니다.
        Text tempText = new Text("Ag");
        tempText.setFont(CODE_FONT);
        double fontHeight = tempText.getLayoutBounds().getHeight(); // 기본 폰트 높이

        // 목표 라인 높이(Target Line Height)를 명확하게 정의합니다.
        // 이 값이 코드와 라인 번호의 실제 한 줄 높이가 됩니다.
        double targetLineHeight = Math.ceil(fontHeight * (1 + LINE_SPACING_FACTOR));

        // 라인 높이에 따른 상하 패딩 계산
        // (목표 높이 - 폰트 높이) / 2
        double caretHeight = targetLineHeight + 1;

        // 최종 CSS 규칙 생성
        String dynamicCSS = String.format(
            /*
            * .paragraph-box: Flexbox를 사용하여 텍스트를 수직 중앙 정렬합니다.
            *   - fx-display: flex; -> Flexbox 컨테이너로 만듭니다.
            *   - fx-alignment: center-left; -> 내용을 수직으로는 중앙, 수평으로는 왼쪽에 정렬합니다.
            * .caret: 보정된 높이(caretHeight)를 적용합니다.
            */
            ".paragraph-box {" +
            "    -fx-min-height: %.1fpx; -fx-max-height: %.1fpx; -fx-pref-height: %.1fpx;" +
            "    -fx-display: flex;" +
            "    -fx-alignment: center-left;" +
            "    -fx-padding: 0 0 0 10px;" +
            "}" +
            ".caret {" +
            "    -fx-shape: \"M0,0 H1 V%.1f\";" +
            "    -fx-stroke-width: 2px;" +
            "}",
            targetLineHeight, targetLineHeight, targetLineHeight,
            caretHeight // ★ 핵심: 보정된 커서 높이 적용
        );

        String dataUri = "data:text/css;base64," + Base64.getEncoder().encodeToString(dynamicCSS.getBytes());
        codeArea.getStylesheets().add(dataUri);

        // CodeArea 자체의 폰트 설정
        codeArea.setStyle(String.format(
            "-fx-font-family: '%s'; -fx-font-size: %dpx;",
            FONT_FAMILY,
            FONT_SIZE
        ));
        
        // =================================================================================
        // --- 3. 라인 번호 너비 및 스타일 설정 (개선된 높이 값 적용) ---
        // =================================================================================

        final DoubleProperty lineNumberPrefWidth = new SimpleDoubleProperty(MIN_INITIAL_WIDTH);

        // 라인 번호 너비 동적 계산 (이전 코드와 동일)
        codeArea.getParagraphs().addListener((ListChangeListener<Object>) c -> {
            int totalLines = Math.max(1, codeArea.getParagraphs().size());
            String maxLineNumberText = String.valueOf(totalLines);
            Text text = new Text(maxLineNumberText);
            text.setFont(LINE_NUMBER_FONT);
            double textWidth = text.getLayoutBounds().getWidth();
            double horizontalPadding = LEFT_PADDING_NUM + RIGHT_PADDING_NUM;
            double dynamicWidth = Math.ceil(textWidth + horizontalPadding);
            lineNumberPrefWidth.set(Math.max(MIN_INITIAL_WIDTH, dynamicWidth));
        });

        codeArea.setParagraphGraphicFactory(lineIndex -> {
            Label lineLabel = new Label();
            lineLabel.setFont(LINE_NUMBER_FONT);
            lineLabel.setText(String.valueOf(lineIndex + 1));
            lineLabel.getStyleClass().add("lineno");

            // *** 핵심: 라인 번호의 높이도 CSS와 동일한 targetLineHeight로 고정 ***
            lineLabel.setPrefHeight(targetLineHeight);
            lineLabel.setAlignment(Pos.CENTER); // 텍스트를 중앙에 정렬
            
            // 패딩은 좌우만 적용 (높이는 prefHeight로 제어하므로)
            lineLabel.setPadding(new Insets(0, RIGHT_PADDING_NUM, 0, LEFT_PADDING_NUM));
            
            lineLabel.prefWidthProperty().bind(lineNumberPrefWidth);
            
            // 에러 상태 처리
            List<SyntaxError> errors = tabErrors.getOrDefault(tabId, new ArrayList<>());
            boolean hasError = errors.stream().anyMatch(e -> e.line - 1 == lineIndex);
            if (hasError) {
                if (!lineLabel.getStyleClass().contains("lineno-error")) {
                    lineLabel.getStyleClass().add("lineno-error");
                }
            } else {
                lineLabel.getStyleClass().remove("lineno-error");
            }
            return lineLabel;
        });
        
        
        // 캐럿이 화면에 보이도록 자동 스크롤
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> codeArea.requestFollowCaret());
    }
}