package com.ethis2s.controller;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

import com.ethis2s.App;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.MacosNativeUtil;
import com.ethis2s.util.MaximizationPatcher;
import com.ethis2s.util.ProtocolConstants;
import com.ethis2s.util.WindowsNativeUtil;
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
import com.sun.jna.platform.win32.WinDef;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import socketprotocol.ParsedPacket;

public class MainController implements ClientSocketManager.ClientSocketCallback {

    private final Stage primaryStage;
    private ClientSocketManager socketManager;

    private final MainScreen mainScreen;
    private final LoginScreen loginScreen;
    private final RegisterScreen registerScreen;
    private EditorTabView editorTabView;
    
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
        this.editorTabView = null; // Will be initialized in initMainScreen
        // primaryStage.setTitle("Live Code IDE");
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

    // public List<Node> getTitleBarComponentArea() {
    //     return mainScreen.getTitleBarInteractiveNodes();
    // }

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
        
        // Create the container for the editor tabs
        SplitPane editorArea = new SplitPane();
        this.editorTabView = new EditorTabView(this, editorArea);

        BorderPane rootPane = mainScreen.createMainScreen(primaryStage, editorArea, statusBarLabel, this);
        rootPane.setStyle("-fx-background-color: transparent;"); // 마우스 이벤트를 통과시키기 위한 테스트

        this.problemsView = mainScreen.getProblemsView(); // MainScreen으로부터 ProblemsView 참조를 얻음
        this.debugView = mainScreen.getDebugView(); // MainScreen으로부터 DebugView 참조를 얻음
        // rootPane.setStyle("-fx-border-color: red; -fx-border-width: 3;"); // 1번 용의자 (최종 보스)
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

            // The listener for tab changes is now managed within EditorTabView itself.
            // editorTabView.getTabPane().getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            //     searchAction.run();
            // });

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

        final String OS = System.getProperty("os.name").toLowerCase();
        if (OS.contains("mac")) {
            // Mac 관련 설정
            MacosNativeUtil.applyUnifiedTitleBarStyle(primaryStage);
        } else if (OS.contains("win")) {
            // 1. 네이티브 스타일링을 먼저 적용합니다.
            primaryStage.setOnShown(e->{
                WindowsNativeUtil.applyCustomWindowStyle(
                primaryStage, 
                mainScreen.getTopPane(),
                mainScreen.getNonDraggableNodes()
                );
            });
        
            // MaximizationPatcher.apply(primaryStage, rootPane);
        } else System.out.println("미지원 OS");
        
        primaryStage.maximizedProperty().addListener((obs, oldVal, isMaximized) -> {
            if (isMaximized) {
                Platform.runLater(() -> {
                    Platform.runLater(() -> {
                        Screen screen = Screen.getPrimary();
                        Rectangle2D bounds = screen.getVisualBounds();
                        
                        System.out.println("--- Recalibration Debug ---");
                        System.out.printf("Bounds from Screen: x=%.1f, y=%.1f, w=%.1f, h=%.1f\n",
                                bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
                        Platform.runLater(()->{
                            primaryStage.setX(0);
                            primaryStage.setY(0);
                            primaryStage.setWidth(bounds.getWidth());
                            primaryStage.setHeight(bounds.getHeight());
                        });
                        Platform.runLater(()->{
                            primaryStage.setWidth(bounds.getWidth());
                        });
                        
                        System.out.printf("Stage After Set:  x=%.1f, y=%.1f, w=%.1f, h=%.1f\n",
                                primaryStage.getX(), primaryStage.getY(), primaryStage.getWidth(), primaryStage.getHeight());
                        System.out.println("---------------------------");
                    });
                });
            }
        });
        primaryStage.setTitle(com.ethis2s.App.NATIVE_WINDOW_TITLE);
        primaryStage.show();
        
        

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
        String tabId = "login-tab";
        
        // Close the register tab if it's open
        if (editorTabView.hasTab("register-tab")) {
            editorTabView.closeTab("register-tab");
        }

        if (editorTabView.hasTab(tabId)) {
            editorTabView.selectTab(tabId);
            return;
        }
        Node loginView = loginScreen.createLoginView(this);
        editorTabView.openTab(tabId, "로그인", loginView);
    }

    public void showRegisterView() {
        String tabId = "register-tab";

        // Close the login tab if it's open
        if (editorTabView.hasTab("login-tab")) {
            editorTabView.closeTab("login-tab");
        }

        if (editorTabView.hasTab(tabId)) {
            editorTabView.selectTab(tabId);
            return;
        }
        Node registerView = registerScreen.createRegisterView(socketManager, this);
        editorTabView.openTab(tabId, "회원가입", registerView);
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
            ConfigManager.getInstance().loadConfig();

            mainScene.getStylesheets().clear();
            ConfigManager configManager = ConfigManager.getInstance();
            String mainThemePath = configManager.getMainThemePath();
            if (mainThemePath != null) {
                mainScene.getStylesheets().add(mainThemePath);
            }

            if (mainScreen != null) {
                mainScreen.reloadComponentCss();
            }

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
            editorTabView.closeTab("login-tab");
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