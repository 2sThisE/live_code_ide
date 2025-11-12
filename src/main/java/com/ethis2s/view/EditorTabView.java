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
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import com.ethis2s.util.Tm4eSyntaxHighlighter.StyleToken;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.layout.StackPane;
import java.util.stream.Stream;
import java.util.Collection;

public class EditorTabView {

    private final TabPane editorTabs;
    private Tab welcomeTab;
    private final List<HybridManager> activeManagers = new ArrayList<>();
    private final MainController mainController;
    
    private final Map<String, List<SyntaxError>> tabErrors = new HashMap<>();
    private final Map<String, String> tabFileNames = new HashMap<>();
    private final Map<String, CodeArea> codeAreaMap = new HashMap<>();
    private final Map<String, HybridManager> hybridManagerMap = new HashMap<>();
    private final Map<String, List<Integer>> searchResultsMap = new HashMap<>();
    private final Map<String, Integer> currentMatchIndexMap = new HashMap<>();
    
    private final IntegerProperty totalMatches = new SimpleIntegerProperty(0);
    private final IntegerProperty currentMatchIndex = new SimpleIntegerProperty(0);

    private final Tooltip errorTooltip = new Tooltip();
    private final PauseTransition tooltipDelay = new PauseTransition(Duration.millis(500));
    private final StringProperty activeTabTitle = new SimpleStringProperty("검색...");

    public EditorTabView(MainController mainController) {
        this.mainController = mainController;
        this.welcomeTab = new Tab("Welcome");
        this.welcomeTab.setClosable(false);
        this.editorTabs = new TabPane(this.welcomeTab);
        this.editorTabs.setTabDragPolicy(TabDragPolicy.REORDER);
        errorTooltip.getStyleClass().add("error-tooltip");

        // Listen for changes in the selected tab
        editorTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null && newTab.isClosable() && newTab.getGraphic() instanceof HBox) {
                // Assuming the file name is in a Label within the HBox
                Label tabLabel = (Label) ((HBox) newTab.getGraphic()).getChildren().get(0);
                if (tabLabel != null) {
                    activeTabTitle.set(tabLabel.getText() + "에서 검색");
                }
            } else {
                activeTabTitle.set("검색...");
            }
        });
    }

    public StringProperty activeTabTitleProperty() {
        return activeTabTitle;
    }

    public IntegerProperty totalMatchesProperty() {
        return totalMatches;
    }

    public IntegerProperty currentMatchIndexProperty() {
        return currentMatchIndex;
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

    public void closeTab(String tabId) {
        editorTabs.getTabs().stream()
            .filter(tab -> tabId.equals(tab.getId()))
            .findFirst()
            .ifPresent(tab -> editorTabs.getTabs().remove(tab));
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

        if (tab.getGraphic() instanceof HBox hbox) {
            Label errorLabel = (Label) hbox.lookup(".tab-error-count");
            if (errorLabel != null) {
                if (errors.isEmpty()) {
                    errorLabel.setText("");
                    errorLabel.getStyleClass().remove("has-errors");
                } else {
                    int errorCount = errors.size();
                    String errorText = errorCount > 9 ? "9+" : String.valueOf(errorCount);
                    errorLabel.setText(errorText);
                    if (!errorLabel.getStyleClass().contains("has-errors")) {
                        errorLabel.getStyleClass().add("has-errors");
                    }
                }
            }
        }

        CodeArea codeArea = codeAreaMap.get(tabId);
        if (codeArea != null) {
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
        
        codeAreaMap.put(tabId, codeArea);
        tabErrors.put(tabId, new ArrayList<>());
        tabFileNames.put(tabId, fileName);

        setupEditorFeatures(codeArea, tabId);
        
        setupErrorTooltip(codeArea, tabId);

        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);

        String fileExtension = getFileExtension(filePath);

        HybridManager manager = new HybridManager(
            codeArea, 
            fileExtension, 
            (errors) -> Platform.runLater(() -> handleErrorUpdate(tabId, fileName, errors)),
            mainController::notifyAntlrTaskStarted,
            mainController::notifyAntlrTaskFinished
        );
        
        activeManagers.add(manager);
        hybridManagerMap.put(tabId, manager);
        codeArea.replaceText(0, 0, content);

        Runnable onClose = () -> {
            manager.shutdown();
            activeManagers.remove(manager);
            hybridManagerMap.remove(tabId);
            tabErrors.remove(tabId);
            codeAreaMap.remove(tabId);
            searchResultsMap.remove(tabId);
            currentMatchIndexMap.remove(tabId);
            mainController.updateProblems(new ArrayList<>());
        };
        
        Tab newTab = new Tab(null, scrollPane);
        
        // --- 탭 그래픽 생성 (HBox 사용) ---
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.getStyleClass().add("tab-file-name"); // 파일 이름 라벨에 클래스 추가
        
        Label errorCountLabel = new Label("9+");
        errorCountLabel.getStyleClass().add("tab-error-count");
        errorCountLabel.setMinWidth(Region.USE_PREF_SIZE); // 내용에 맞게 최소 너비 설정
        errorCountLabel.setText("");
        HBox tabGraphic = new HBox(5, fileNameLabel, errorCountLabel); // 5px 간격
        tabGraphic.setAlignment(Pos.CENTER_LEFT);
        // --- 탭 그래픽 생성 끝 ---

        newTab.setGraphic(tabGraphic);
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
    
    public void reapplyAllEditorSettings() {
        for (CodeArea codeArea : codeAreaMap.values()) {
            applyStylesToCodeArea(codeArea);
        }
    }

    private void setupEditorFeatures(CodeArea codeArea, String tabId) {
        applyStylesToCodeArea(codeArea);
        
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
        final String ERR_LINE_STYLE="-fx-text-fill: #ff6666 !important;";

        codeArea.setParagraphGraphicFactory(lineIndex -> {
            Label lineLabel = new Label();
            lineLabel.setFont(Font.font(ConfigManager.getInstance().getFontFamily(), ConfigManager.getInstance().getFontSize()));
            lineLabel.setText(String.valueOf(lineIndex + 1));
            lineLabel.getStyleClass().add("lineno");
            lineLabel.setAlignment(Pos.CENTER);
            lineLabel.setPadding(new Insets(0, RIGHT_PADDING_NUM, 0, LEFT_PADDING_NUM));
            lineLabel.prefWidthProperty().bind(lineNumberPrefWidth);

            if (lineIndex == codeArea.getCurrentParagraph()) {
                lineLabel.setStyle(CARET_LINE_STYLE);
            } else {
                lineLabel.setStyle(DEFAULT_LINE_STYLE);
            }
            List<SyntaxError> errors = tabErrors.getOrDefault(tabId, new ArrayList<>());
            boolean hasError = errors.stream().anyMatch(e -> e.line - 1 == lineIndex);
            if (hasError) {
                if (!lineLabel.getStyle().contains(ERR_LINE_STYLE)) lineLabel.setStyle(ERR_LINE_STYLE);
            } else {
                if (lineIndex == codeArea.getCurrentParagraph()) lineLabel.setStyle(CARET_LINE_STYLE);
                else lineLabel.setStyle(DEFAULT_LINE_STYLE);
            }
            return lineLabel;
        });
        
        codeArea.currentParagraphProperty().addListener((obs, oldParagraph, newParagraph) -> {
            Label oldLabel = getLineNumberLabel(codeArea, oldParagraph);
            if (oldLabel != null) {
                updateLineNumberStyle(oldLabel, oldParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE);
            }
            Label newLabel = getLineNumberLabel(codeArea, newParagraph);
            if (newLabel != null) {
                updateLineNumberStyle(newLabel, newParagraph, tabId, codeArea, DEFAULT_LINE_STYLE, CARET_LINE_STYLE, ERR_LINE_STYLE);
            }
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
            ".text {" +
            "    -fx-font-family: '%s'; -fx-font-size: %dpx;" +
            "}" +
            ".paragraph-box {" +
            "    -fx-min-height: %.1fpx; -fx-max-height: %.1fpx; -fx-pref-height: %.1fpx;" +
            "    -fx-display: flex;" +
            "    -fx-alignment: center-left;" +
            "    -fx-padding: 0 0 0 10px;" +
            "}" +
            ".caret {" +
            "    -fx-shape: \"M0,0 H1 V%.1f\";" +
            "    -fx-stroke-width: 2px;" +
            "}" +
            ".syntax-error {" +
            "    -rtfx-background-color: rgba(255, 71, 71, 0.44);" +
            "    -fx-padding: %.1fpx 0;" +
            "}",
            FONT_FAMILY, FONT_SIZE,
            targetLineHeight, targetLineHeight, targetLineHeight,
            caretHeight,
            verticalPadding
        );

        String combinedCss = tabSizeCss + "\n" + dynamicStylingCss;
        String dataUri = "data:text/css;base64," + Base64.getEncoder().encodeToString(combinedCss.getBytes());
        
        codeArea.getStylesheets().clear();
        codeArea.getStylesheets().add(dataUri);
    }

    private Label getLineNumberLabel(CodeArea codeArea, int paragraphIndex) {
        if (paragraphIndex < 0 || paragraphIndex >= codeArea.getParagraphs().size()) {
            return null;
        }
        Node graphic = codeArea.getParagraphGraphic(paragraphIndex);
        if (graphic instanceof Label) {
            return (Label) graphic;
        }
        if (graphic instanceof Region && ((Region) graphic).lookup(".label") instanceof Label) {
            return (Label) ((Region) graphic).lookup(".label");
        }
        return null;
    }

    private void updateLineNumberStyle(Label lineLabel, int lineIndex, String tabId, CodeArea codeArea,
                                   String DEFAULT_LINE_STYLE, String CARET_LINE_STYLE, String ERR_LINE_STYLE) {
    
        List<SyntaxError> errors = tabErrors.getOrDefault(tabId, Collections.emptyList());
        boolean hasError = errors.stream().anyMatch(e -> e.line - 1 == lineIndex);

        if (hasError) {
            lineLabel.setStyle(ERR_LINE_STYLE);
        } else {
            if (lineIndex == codeArea.getCurrentParagraph()) {
                lineLabel.setStyle(CARET_LINE_STYLE);
            } else {
                lineLabel.setStyle(DEFAULT_LINE_STYLE);
            }
        }
    }

    // --- New Public Search API for MainController ---

    public String getCurrentSelectedText() {
        Tab selectedTab = editorTabs.getSelectionModel().getSelectedItem();
        if (selectedTab != null && selectedTab.getId() != null) {
            CodeArea codeArea = codeAreaMap.get(selectedTab.getId());
            if (codeArea != null) {
                return codeArea.getSelectedText();
            }
        }
        return "";
    }

    public void performSearchOnActiveTab(String query, boolean caseSensitive) {
        Tab selectedTab = editorTabs.getSelectionModel().getSelectedItem();
        if (selectedTab != null && selectedTab.getId() != null) {
            CodeArea codeArea = codeAreaMap.get(selectedTab.getId());
            if (codeArea != null) {
                performSearch(codeArea, selectedTab.getId(), query, caseSensitive);
            }
        }
    }

    public void goToNextMatchOnActiveTab() {
        Tab selectedTab = editorTabs.getSelectionModel().getSelectedItem();
        if (selectedTab != null && selectedTab.getId() != null) {
            CodeArea codeArea = codeAreaMap.get(selectedTab.getId());
            if (codeArea != null) {
                goToNextMatch(codeArea, selectedTab.getId());
            }
        }
    }

    public void goToPreviousMatchOnActiveTab() {
        Tab selectedTab = editorTabs.getSelectionModel().getSelectedItem();
        if (selectedTab != null && selectedTab.getId() != null) {
            CodeArea codeArea = codeAreaMap.get(selectedTab.getId());
            if (codeArea != null) {
                goToPreviousMatch(codeArea, selectedTab.getId());
            }
        }
    }

    // --- Private Core Search Logic ---

    private void performSearch(CodeArea codeArea, String tabId, String query, boolean caseSensitive) {
        searchResultsMap.put(tabId, new ArrayList<>());
        currentMatchIndexMap.put(tabId, -1);

        if (query.isEmpty()) {
            highlightMatches(codeArea, tabId, query);
            totalMatches.set(0);
            currentMatchIndex.set(0);
            return;
        }

        String text = codeArea.getText();
        List<Integer> results = searchResultsMap.get(tabId);

        Pattern pattern = caseSensitive ? Pattern.compile(Pattern.quote(query)) : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            results.add(matcher.start());
        }

        totalMatches.set(results.size());
        highlightMatches(codeArea, tabId, query);

        if (!results.isEmpty()) {
            currentMatchIndexMap.put(tabId, 0);
            currentMatchIndex.set(1);
            goToMatch(codeArea, tabId, 0);
        } else {
            currentMatchIndex.set(0);
        }
    }

    private void highlightMatches(CodeArea codeArea, String tabId, String query) {
        HybridManager manager = hybridManagerMap.get(tabId);
        if (manager == null) {
            return;
        }

        List<Integer> searchResults = searchResultsMap.get(tabId);
        if (query.isEmpty() || searchResults == null || searchResults.isEmpty()) {
            manager.updateSearchHighlights(Collections.emptyList());
            return;
        }

        List<StyleToken> searchTokens = new ArrayList<>();
        for (Integer start : searchResults) {
            int end = start + query.length();
            searchTokens.add(new StyleToken(start, end, Collections.singletonList("search-highlight")));
        }
        
        manager.updateSearchHighlights(searchTokens);
    }

    private void goToNextMatch(CodeArea codeArea, String tabId) {
        List<Integer> results = searchResultsMap.get(tabId);
        if (results == null || results.isEmpty()) return;

        int currentIndex = currentMatchIndexMap.getOrDefault(tabId, -1);
        currentIndex = (currentIndex + 1) % results.size();
        currentMatchIndexMap.put(tabId, currentIndex);
        currentMatchIndex.set(currentIndex + 1);
        goToMatch(codeArea, tabId, currentIndex);
    }

    private void goToPreviousMatch(CodeArea codeArea, String tabId) {
        List<Integer> results = searchResultsMap.get(tabId);
        if (results == null || results.isEmpty()) return;

        int currentIndex = currentMatchIndexMap.getOrDefault(tabId, 0);
        currentIndex = (currentIndex - 1 + results.size()) % results.size();
        currentMatchIndexMap.put(tabId, currentIndex);
        currentMatchIndex.set(currentIndex + 1);
        goToMatch(codeArea, tabId, currentIndex);
    }

    private void goToMatch(CodeArea codeArea, String tabId, int index) {
        List<Integer> results = searchResultsMap.get(tabId);
        if (results == null || index < 0 || index >= results.size()) return;

        int pos = results.get(index);
        
        String query = mainController.getSearchQuery();
        if (query.isEmpty()) return;

        codeArea.selectRange(pos, pos + query.length());
        codeArea.requestFollowCaret();
    }
}
