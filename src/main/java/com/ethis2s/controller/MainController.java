package com.ethis2s.controller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.ProtocolConstants;
import com.ethis2s.util.ReSizeHelper;
import com.ethis2s.view.DebugView;
import com.ethis2s.view.EditorTabView;
import com.ethis2s.view.LoginScreen;
import com.ethis2s.view.MainScreen;
import com.ethis2s.view.ProblemsView;
import com.ethis2s.view.ProblemsView.Problem;
import com.ethis2s.view.ProjectPropertiesScreen;
import com.ethis2s.view.RegisterScreen;
import com.ethis2s.view.SettingsView;
import com.ethis2s.view.SharedOptionScreen;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.paint.Color;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import socketprotocol.ParsedPacket;

public class MainController implements ClientSocketManager.ClientSocketCallback {

    private final Stage primaryStage;
    private ClientSocketManager socketManager;

    private final MainScreen mainScreen;
    private final LoginScreen loginScreen;
    private final RegisterScreen registerScreen;
    private final EditorTabView editorTabView;
    
    private ProjectController projectController;
    private ProblemsView problemsView; // ProblemsView에 접근하기 위한 참조
    private DebugView debugView;

    private Scene mainScene;
    private Label statusBarLabel;

    private UserInfo userInfo;
    private String lastLoggedInId;
    private char[] lastLoggedInPassword;
    
    private final AtomicInteger activeAntlrTasks = new AtomicInteger(0);
    
    private Map<String, Consumer<JSONArray>> pendingSharedListCallbacks = new HashMap<>();
    private Map<String, Consumer<Boolean>> pendingAddShareCallbacks = new HashMap<>();
    private boolean isSearchActive = false;
    private final double SEARCH_FIELD_NARROW_WIDTH = 200;
    private final double SEARCH_FIELD_WIDE_WIDTH = 400;

    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.mainScreen = new MainScreen();
        this.loginScreen = new LoginScreen();
        this.registerScreen = new RegisterScreen();
        this.editorTabView = new EditorTabView(this); // EditorTabView에 자신을 전달
        primaryStage.setTitle("Live Code IDE");
    }

    /**
     * EditorTabView로부터 받은 에러 목록을 ProblemsView에 업데이트합니다.
     */
    public void updateProblems(List<Problem> problems) {
        if (problemsView != null) {
            problemsView.updateProblems(problems);
        }
        if (mainScreen != null) {
            mainScreen.updateProblemsTab(problems.size());
        }
    }

    public DebugView getDebugView() {
        return debugView;
    }

    public void shutdown() {
        editorTabView.shutdownAllManagers();
    }

    public void setSocketManager(ClientSocketManager socketManager) {
        this.socketManager = socketManager;
        this.projectController = new ProjectController(socketManager, mainScreen, this);
    }

    public ProjectController getProjectController() {
        return projectController;
    }

    public void initMainScreen() {
        this.statusBarLabel = new Label("로그인되지 않았습니다.");
        primaryStage.setMinWidth(600);
        primaryStage.setMinHeight(400);
        
        BorderPane rootPane = mainScreen.createMainScreen(primaryStage, editorTabView.getTabPane(), statusBarLabel, this);
        this.problemsView = mainScreen.getProblemsView(); // MainScreen으로부터 ProblemsView 참조를 얻음
        this.debugView = mainScreen.getDebugView(); // MainScreen으로부터 DebugView 참조를 얻음

        this.mainScene = new Scene(rootPane, 1280, 720);
        mainScene.setFill(Color.TRANSPARENT);
        try {
            String cssPath = com.ethis2s.util.ConfigManager.getInstance().getMainThemePath();
            if (cssPath != null) {
                mainScene.getStylesheets().add(cssPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("테마 파일을 로드할 수 없습니다.");
        }
        
        primaryStage.setScene(mainScene);
        
        // --- 단축키 설정 ---
        mainScene.getAccelerators().put(
            new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.F, javafx.scene.input.KeyCombination.CONTROL_DOWN),
            () -> {
                String selectedText = editorTabView.getCurrentSelectedText();
                if (selectedText != null && !selectedText.isEmpty()) {
                    mainScreen.getSearchField().setText(selectedText);
                }
                mainScreen.getSearchField().requestFocus();
            }
        );

        // --- 검색창 리스너 설정 ---
        javafx.scene.control.TextField searchField = mainScreen.getSearchField();
        javafx.scene.control.ToggleButton caseSensitiveCheck = mainScreen.getCaseSensitiveCheck();

        if (searchField != null) {
            
            searchField.promptTextProperty().bind(editorTabView.activeTabTitleProperty());

            Runnable searchAction = () -> {
                if (editorTabView != null) {
                    editorTabView.performSearchOnActiveTab(
                        searchField.getText(), 
                        caseSensitiveCheck.isSelected()
                    );
                }
            };

            searchField.textProperty().addListener((obs, oldText, newText) -> {
                searchAction.run();
                updateSearchButtonVisibility();
            });
            caseSensitiveCheck.setOnAction(e -> searchAction.run());

            // Add Enter key press handler for search field
            searchField.setOnAction(e -> editorTabView.goToNextMatchOnActiveTab());

            mainScreen.getPrevButton().setOnAction(e -> editorTabView.goToPreviousMatchOnActiveTab());
            mainScreen.getNextButton().setOnAction(e -> editorTabView.goToNextMatchOnActiveTab());
            
            // Add a listener to the text field's focus property
            searchField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (isNowFocused) {
                    mainScreen.getSearchBox().getStyleClass().add("search-field-focused");
                } else {
                    mainScreen.getSearchBox().getStyleClass().remove("search-field-focused");
                }
                updateSearchButtonVisibility();
            });

            mainScreen.getSearchBox().hoverProperty().addListener((obs, wasHovered, isNowHovered) -> {
                updateSearchButtonVisibility();
            });

            // Add a listener for tab changes to re-run the search
            editorTabView.getTabPane().getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                searchAction.run();
            });

            // Bind the result label to the search properties
            Label resultLabel = mainScreen.getResultLabel();
            if (resultLabel != null) {
                resultLabel.textProperty().bind(
                    Bindings.createStringBinding(() -> {
                        int total = editorTabView.totalMatchesProperty().get();
                        int current = editorTabView.currentMatchIndexProperty().get();
                        if (total == 0) {
                            return "";
                        }
                        return String.format("%d / %d", current, total);
                    },
                    editorTabView.totalMatchesProperty(),
                    editorTabView.currentMatchIndexProperty())
                );
            }
        }

        primaryStage.show();
        ReSizeHelper.addResizeListener(primaryStage);

        showLoginView();
    }

    private void updateSearchButtonVisibility() {
        boolean isTextPresent = !mainScreen.getSearchField().getText().isEmpty();
        boolean isHovering = mainScreen.getSearchBox().isHover();
        boolean isFocused = mainScreen.getSearchField().isFocused();

        boolean shouldBeVisible = isTextPresent || isHovering || isFocused;

        List<Node> searchTools = List.of(
            mainScreen.getPrevButton(),
            mainScreen.getNextButton(),
            mainScreen.getCaseSensitiveCheck(),
            mainScreen.getResultLabel()
        );

        searchTools.forEach(node -> {
            node.setVisible(shouldBeVisible);
            node.setManaged(shouldBeVisible);
        });
    }

    public void performLogin(String id, char[] password) {
        this.lastLoggedInId = id;
        this.lastLoggedInPassword = Arrays.copyOf(password, password.length);
        
        String passwordString = new String(password);
        String loginPayload = String.format("{\"id\":\"%s\", \"password\":\"%s\"}", id, passwordString);
        byte[] loginPayloadBytes = loginPayload.getBytes(StandardCharsets.UTF_8);
        
        Arrays.fill(password, '0');

        new Thread(() -> {
            try {
                socketManager.sendPacket(loginPayloadBytes, ProtocolConstants.UNFRAGED, ProtocolConstants.UF_LOGIN_REQUEST, ProtocolConstants.PTYPE_JSON);
            } catch (Exception ex) {
                Platform.runLater(() -> loginScreen.updateLoginStatus(-1));
            }
        }).start();
    }

    public void performLogout() {
        this.userInfo = null;
        this.lastLoggedInId = null;
        if (this.lastLoggedInPassword != null) {
            Arrays.fill(this.lastLoggedInPassword, '0');
            this.lastLoggedInPassword = null;
        }
        projectController.clearUserInfo();

        Platform.runLater(() -> {
            mainScreen.clearProjectView();
            statusBarLabel.setText("로그인되지 않았습니다.");
            editorTabView.closeAllClosableTabs();
            showLoginView();
        });
    }

    public void openFileInEditor(String filePath) {
        // TODO: 서버로부터 파일 내용을 요청하고 받아오는 로직 필요
        String dummyContent = "// " + filePath + " 의 내용을 여기에 로드합니다.";
        editorTabView.openFileInEditor(filePath, dummyContent);
    }

    public void showLoginView() {
        editorTabView.setWelcomeTabContent(loginScreen.createLoginView(this));
        editorTabView.selectWelcomeTab();
    }

    public void showRegisterView() {
        editorTabView.setWelcomeTabContent(registerScreen.createRegisterView(socketManager, this));
        editorTabView.selectWelcomeTab();
    }

    public void showProjectPropertiesView(UserProjectsInfo userProjectsInfo) {
        String tabId = "properties-" + userProjectsInfo.getProjectID();
        String title = userProjectsInfo.getProjectName() + " 속성";
        
        if (editorTabView.hasTab(tabId)) {
            editorTabView.selectTab(tabId);
            return;
        }
        
        ProjectPropertiesScreen pps = new ProjectPropertiesScreen();
        Node content = pps.creatProjectProperties(userProjectsInfo);
        editorTabView.openTab(tabId, title, content);
    }

    public void showSharedOptionView(UserProjectsInfo userProjectsInfo) {
        String projectId = userProjectsInfo.getProjectID();
        String tabId = "share-" + projectId;
        String title = userProjectsInfo.getProjectName() + " 공유";
        
        if (editorTabView.hasTab(tabId)) {
            editorTabView.selectTab(tabId);
            return;
        }

        SharedOptionScreen sos = new SharedOptionScreen();
        Node content = sos.createShareOptionView(
            this, projectController, userProjectsInfo, 
            (pId, dataCallback) -> {
                pendingSharedListCallbacks.put(pId, dataCallback);
                projectController.sharedListRequest(pId);
            }, 
            (pId, name, tag, resultCallback) -> {
                pendingAddShareCallbacks.put(pId, resultCallback);
                projectController.addShareRequest(pId, name, tag);
            }
        );
        
        Runnable onClose = () -> {
            pendingSharedListCallbacks.remove(projectId);
            pendingAddShareCallbacks.remove(projectId);
        };

        editorTabView.openTabWithCloseCallback(tabId, title, content, onClose);
    }

    public void showSettingsView() {
        String tabId = "settings-tab";
        String title = "설정";

        if (editorTabView.hasTab(tabId)) {
            editorTabView.selectTab(tabId);
            return;
        }

        Runnable saveCallback = () -> {
            // 1. Reload config from file
            ConfigManager.getInstance().loadConfig();

            // 2. Reload main scene's stylesheets
            mainScene.getStylesheets().clear();
            ConfigManager configManager = ConfigManager.getInstance();
            String mainThemePath = configManager.getMainThemePath();
            if (mainThemePath != null) {
                mainScene.getStylesheets().add(mainThemePath);
            }

            // 3. Reload component-specific stylesheets
            if (mainScreen != null) {
                mainScreen.reloadComponentCss();
            }

            // 4. Re-apply settings to all open editor tabs
            if (editorTabView != null) {
                editorTabView.reapplyAllEditorSettings();
            }
        };

        Runnable closeTabCallback = () -> {
            if (editorTabView != null) {
                editorTabView.closeTab(tabId);
            }
        };

        SettingsView settingsView = new SettingsView(saveCallback, closeTabCallback);
        Node content = settingsView.createView();
        editorTabView.openTab(tabId, title, content);
    }


    @Override
    public void onConnected() {
        Platform.runLater(() -> System.out.println("SocketManager: Connected to server."));
    }

    @Override
    public void onDisconnected() {
        Platform.runLater(() -> mainScreen.showReconnectingStatus(true));
    }

    @Override
    public void onReconnected() {
        Platform.runLater(() -> {
            mainScreen.showReconnectingStatus(false);
            mainScreen.showConnectedStatus();
            if (lastLoggedInId != null && lastLoggedInPassword != null) {
                performLogin(lastLoggedInId, lastLoggedInPassword);
            }
        });
    }

    @Override
    public void onPacketReceived(ParsedPacket packet) {}

    @Override
    public void onError(String message) {
        Platform.runLater(() -> System.err.println("SocketManager Error: " + message));
    }

    @Override
    public void onRegisterResponse(int responseCode) {
        registerScreen.updateRegistrationStatus(responseCode, this);
    }

    @Override
    public void onLoginResponse(int responseCode) {
        loginScreen.updateLoginStatus(responseCode);
    }

    @Override
    public void onUserInfoReceived(UserInfo userInfo) {
        this.userInfo = userInfo;
        projectController.setUserInfo(userInfo);
        Platform.runLater(() -> {
            VBox successView = new VBox(20, new Label("환영합니다, " + userInfo.getNickname() + "#" + userInfo.getTag() + "님!"), new Label("파일 탐색기에서 워크스페이스를 확인하세요."));
            successView.setAlignment(Pos.CENTER);
            editorTabView.getWelcomeTab().setContent(successView);
            editorTabView.getWelcomeTab().setClosable(true);
            statusBarLabel.setText("로그인됨: " + userInfo.getNickname() + "#" + userInfo.getTag());
            projectController.projectListRequest();
        });
    }

    @Override
    public void onProjectListResponse(List<UserProjectsInfo> projectList) {
        projectController.handleProjectListResponse(projectList);
    }

    @Override
    public void onFileListResponse(String projectID, JSONObject fileList) {
        projectController.handleFileListResponse(fileList);
    }

    @Override
    public void onCreateProjectResponse(boolean result) {
        projectController.handleCreateProjectResponse(result);
    }
    
    @Override
    public void onDeleteProjectResponse(boolean result) {
        projectController.handleDeleteProjectResponse(result);
    }

    @Override
    public void onSharedListResponse(String projectId, JSONArray sharedList) {
        Consumer<JSONArray> callback = pendingSharedListCallbacks.get(projectId);
        if (callback != null) {
            Platform.runLater(() -> callback.accept(sharedList));
        }
    }

    @Override
    public void onAddShareResponse(String projectId, boolean success) {
        Consumer<Boolean> callback = pendingAddShareCallbacks.get(projectId);
        if (callback != null) {
            Platform.runLater(() -> callback.accept(success));
            pendingAddShareCallbacks.remove(projectId);
        }
    }

    @Override
    public void onDeleteShareResponse(String projectID, boolean result) {
        projectController.handleDeleteShareResponse(result);
        if (result) {
            Consumer<JSONArray> callback = pendingSharedListCallbacks.get(projectID);
            if (callback != null) {
                projectController.sharedListRequest(projectID);
            }
        }
    }

    @Override
    public void onAddFileResponse(boolean result) {
        projectController.handleAddFileResponse(result);
    }

    @Override
    public void onAddFolderResponse(boolean result) {
        projectController.handleAddFolderResponse(result);
    }

    /**
     * ANTLR 분석 작업이 시작되었음을 알립니다.
     * 실행 중인 작업 수를 1 증가시키고, 첫 작업인 경우 인디케이터를 표시합니다.
     */
    public void notifyAntlrTaskStarted() {
        if (activeAntlrTasks.getAndIncrement() == 0) {
            if (mainScreen != null) {
                mainScreen.showAntlrIndicator(true);
            }
        }
    }

    /**
     * ANTLR 분석 작업이 종료되었음을 알립니다.
     * 실행 중인 작업 수를 1 감소시키고, 마지막 작업이었던 경우 인디케이터를 숨깁니다.
     */
    public void notifyAntlrTaskFinished() {
        if (activeAntlrTasks.decrementAndGet() == 0) {
            if (mainScreen != null) {
                mainScreen.showAntlrIndicator(false);
            }
        }
    }

    public void navigateToError(Problem problem) {
        if (editorTabView != null && problem != null) {
            editorTabView.navigateTo(problem.filePath, problem.error.line, problem.error.charPositionInLine);
        }
    }

    public String getSearchQuery() {
        if (mainScreen != null && mainScreen.getSearchField() != null) {
            return mainScreen.getSearchField().getText();
        }
        return "";
    }
}