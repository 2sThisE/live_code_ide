package com.ethis2s.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;

import com.ethis2s.App;
import com.ethis2s.model.Operation;
import com.ethis2s.model.ProtocolConstants;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.service.ExecutionService;
import com.ethis2s.util.ConfigManager;
import com.ethis2s.util.MacosNativeUtil;
import com.ethis2s.util.VariableResolver;
import com.ethis2s.util.WindowsNativeUtil;
import com.ethis2s.view.CustomAlert;
import com.ethis2s.view.DebugView;
import com.ethis2s.view.FileExecutionSelectionView;
import com.ethis2s.view.LoginScreen;
import com.ethis2s.view.MainScreen;
import com.ethis2s.view.ProblemsView;
import com.ethis2s.view.ProblemsView.Problem;
import com.ethis2s.view.editor.EditorTabView;
import com.google.gson.Gson;
import com.ethis2s.view.ProjectPropertiesScreen;
import com.ethis2s.view.RegisterScreen;
import com.ethis2s.view.RunView;
import com.ethis2s.view.SettingsView;
import com.ethis2s.view.SharedOptionScreen;
import com.ethis2s.view.FileExecutionSelectionView.FileExecutionInfo;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import socketprotocol.ParsedPacket;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javafx.scene.layout.AnchorPane;

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
    private UserProjectsInfo currentActiveProject;
    
    private final AtomicInteger activeAntlrTasks = new AtomicInteger(0);
    
    private Map<String, Consumer<JSONArray>> pendingSharedListCallbacks = new HashMap<>();
    private Map<String, Consumer<Boolean>> pendingAddShareCallbacks = new HashMap<>();
    private Runnable searchAction;


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

    public void requestProjectSwitch(UserProjectsInfo selectedProject, ProjectController projectController) {
        if (editorTabView.hasTabsFromOtherProjects(selectedProject)) {
            // 닫을 탭이 있는 경우, 사용자에게 확인을 요청합니다.
            String title = "프로젝트 변경 확인";
            String header = "다른 프로젝트의 탭을 닫으시겠습니까?";
            String context = "현재 열려있는 다른 프로젝트의 파일 탭들이 모두 닫힙니다.\n계속하시겠습니까?";

            // 사용자가 '확인'을 눌렀을 때 실행될 동작
            Runnable onConfirm = () -> {
                setCurrentActiveProject(selectedProject);
                editorTabView.closeTabsBelongingToOtherProjects(selectedProject);
                mainScreen.switchToProjectDirView(selectedProject, projectController, this);
            };
            
            // 사용자가 '취소'를 눌렀을 때 실행될 동작 (아무것도 안 함)
            Runnable onCancel = () -> {};

            CustomAlert confirmAlert = new CustomAlert(primaryStage, title, header, context, onConfirm, onCancel);
            confirmAlert.showAndWait();
        } else {
            // 닫을 탭이 없는 경우, 즉시 프로젝트를 전환합니다.
            setCurrentActiveProject(selectedProject);
            mainScreen.switchToProjectDirView(selectedProject, projectController, this);
        }
    }

    public void setCurrentActiveProject(UserProjectsInfo projectInfo) {
        this.currentActiveProject = projectInfo;
    }

    public Optional<UserProjectsInfo> getCurrentActiveProject() {
        return Optional.ofNullable(currentActiveProject);
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

    public MainScreen getMainScreen() {
        return mainScreen;
    }

    public void initMainScreen() {
        this.statusBarLabel = new Label("로그인되지 않았습니다.");
        primaryStage.setMinWidth(600);
        primaryStage.setMinHeight(400);
        
        // Create the container for the editor tabs
        SplitPane editorArea = new SplitPane();
        editorArea.setStyle("-fx-background-color: transparent;");
        this.editorTabView = new EditorTabView(this, editorArea);

        // [핵심 수정] EditorTabView로부터 editor SplitPane을 가져옵니다.
        Node editorLayout = editorTabView.getLayout();

        // MainScreen은 이제 editorLayout만 받아서 내부 컨텐츠를 구성합니다.
        BorderPane mainContentPane = mainScreen.createMainScreen(primaryStage, editorLayout, statusBarLabel, this);
        mainContentPane.setStyle("-fx-background-color: transparent;");

        // --- 최상위 레이아웃 조립 (AnchorPane 사용) ---
        AnchorPane rootLayout = new AnchorPane();
        
        // FileExecutionSelectionView 가져오기
        Node executionView = editorTabView.getFileExecutionSelectionView().getView();
        executionView.setPickOnBounds(false); // 이벤트가 통과하도록 설정

        // 1. 메인 컨텐츠를 AnchorPane에 추가하고 모든 면에 앵커를 설정하여 꽉 채웁니다.
        rootLayout.getChildren().add(mainContentPane);
        AnchorPane.setTopAnchor(mainContentPane, 0.0);
        AnchorPane.setBottomAnchor(mainContentPane, 0.0);
        AnchorPane.setLeftAnchor(mainContentPane, 0.0);
        AnchorPane.setRightAnchor(mainContentPane, 0.0);


        HBox executionViewContainer = new HBox(executionView);
        executionViewContainer.setAlignment(Pos.TOP_CENTER); // HBox 내부의 아이템(executionView)을 위쪽 중앙에 배치
        executionViewContainer.setPickOnBounds(false); // HBox도 이벤트를 통과시켜야 함

        // 3. 이제 이 '컨테이너(HBox)'를 AnchorPane에 추가하고, 좌우로 꽉 채웁니다.
        rootLayout.getChildren().add(executionViewContainer);
        AnchorPane.setTopAnchor(executionViewContainer, 0.0);
        AnchorPane.setLeftAnchor(executionViewContainer, 0.0);
        AnchorPane.setRightAnchor(executionViewContainer, 0.0);

        // 2. 오버레이할 뷰를 AnchorPane에 추가합니다.
        executionViewContainer.setPadding(new javafx.geometry.Insets(0, 0, 0, 0));
        rootLayout.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            // 1. executionView가 현재 '보이는' 상태일 때만 로직을 실행합니다.
            if (editorTabView.getFileExecutionSelectionView().isVisible()) {
                
                // 2. executionView의 '화면 전체 기준'의 영역(Bounds)을 가져옵니다.
                Bounds boundsInScreen = executionView.localToScreen(executionView.getBoundsInLocal());
                
                // 3. 만약 영역 계산이 성공했고,
                //    '그리고' 클릭된 마우스의 화면 좌표(event.getScreenX/Y)가
                //    그 영역 안에 '포함되지 않는다면(!contains)'...
                if (boundsInScreen != null && !boundsInScreen.contains(event.getScreenX(), event.getScreenY())) {
                    
                    // 4. executionView를 보이지 않게 처리합니다.
                    editorTabView.getFileExecutionSelectionView().setVisible(false);
                    
                    // 5. [선택 사항] 이벤트를 소비(consume)하여, 클릭 이벤트가
                    //    뒤에 있는 다른 UI(예: 에디터)에 전달되지 않게 할 수 있습니다.
                    //    이렇게 하면 외부를 클릭했을 때 뷰가 닫히기만 하고, 다른 동작은 일어나지 않습니다.
                    event.consume();
                }
            }
        });
        
        this.problemsView = mainScreen.getProblemsView();
        this.debugView = mainScreen.getDebugView();
        
        this.mainScene = new Scene(rootLayout, 1280, 720); // Scene의 루트를 AnchorPane으로 설정
        mainScene.setFill(Color.TRANSPARENT);
        try {
            String cssPath = ConfigManager.getInstance().getThemePath("design","mainTheme");
            if (cssPath != null) {
                mainScene.getStylesheets().add(cssPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("테마 파일을 로드할 수 없습니다.");
        }
        
        primaryStage.setScene(mainScene);
        
        // --- Run 버튼 액션 연결 ---
        mainScreen.getRunButton().setOnAction(e -> {
            if (editorTabView != null) {
                editorTabView.toggleFileExecutionView();
            }
        });
        
        // --- 단축키 설정 ---
        mainScene.getAccelerators().put(
            new javafx.scene.input.KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
            () -> {
                String selectedText = editorTabView.getCurrentSelectedText();
                if (selectedText != null && !selectedText.isEmpty()) {
                    mainScreen.getSearchField().setText(selectedText);
                }
                mainScreen.getSearchField().requestFocus();
            }
        );

        // --- 검색창 리스너 설정 ---
        TextField searchField = mainScreen.getSearchField();
        ToggleButton caseSensitiveCheck = mainScreen.getCaseSensitiveCheck();

        if (searchField != null) {
            
            searchField.promptTextProperty().bind(editorTabView.activeTabTitleProperty());

            this.searchAction = () -> {
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
            
            // 검색 관련 컨트롤 목록 정의
            List<Node> searchRelatedControls = List.of(
                searchField,
                mainScreen.getPrevButton(),
                mainScreen.getNextButton(),
                caseSensitiveCheck,
                mainScreen.getSearchBox()
            );

            // Add a listener to the text field's focus property
            searchField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (isNowFocused) {
                    mainScreen.getSearchBox().getStyleClass().add("search-field-focused");
                } else {
                    // 포커스가 사라졌을 때, 새로 포커스를 받은 곳이 검색 관련 컨트롤이 아니라면 하이라이트를 제거
                    Platform.runLater(() -> {
                        Node currentFocusOwner = mainScene.getFocusOwner();
                        if (!searchRelatedControls.contains(currentFocusOwner)) {
                            editorTabView.clearSearchHighlights();
                        }
                    });
                    mainScreen.getSearchBox().getStyleClass().remove("search-field-focused");
                }
                updateSearchButtonVisibility();
            });
            if (mainScreen.getPauseOTButton() != null) {
                mainScreen.getPauseOTButton().setOnAction(e -> {
                    if (editorTabView != null) {
                        editorTabView.toggleActiveTabOTPause();
                    }
                });
            }

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
        primaryStage.setOnShown(e->{
            if (OS.contains("mac")) {
                // Mac 관련 설정
                MacosNativeUtil.applyUnifiedTitleBarStyle(primaryStage);
                MacosNativeUtil.calculateAndCacheOffset(primaryStage);
            } else if (OS.contains("win")) {
            // 1. 네이티브 스타일링을 먼저 적용합니다.
                WindowsNativeUtil.applyCustomWindowStyle(
                primaryStage, 
                mainScreen.getTopPane(),
                mainScreen.getNonDraggableNodes()
                );
                
                // MaximizationPatcher.apply(primaryStage, rootPane);
            } else System.out.println("미지원 OS");
        });
        
        primaryStage.maximizedProperty().addListener((obs, oldVal, isMaximized) -> {
            if (isMaximized) {
                mainScreen.getTopPane().getStyleClass().add("maximized");
                mainScreen.getStatusBar().getStyleClass().add("maximized");
                mainScreen.getWindowCloseButton().getStyleClass().add("maximized");
                Platform.runLater(() -> {
                    Screen screen = Screen.getPrimary();
                    Rectangle2D bounds = screen.getVisualBounds();
                    primaryStage.setX(0);
                    primaryStage.setY(0);
                    primaryStage.setWidth(bounds.getWidth());
                    primaryStage.setHeight(bounds.getHeight()-1);
                });
            }else{
                mainScreen.getTopPane().getStyleClass().remove("maximized");
                mainScreen.getStatusBar().getStyleClass().remove("maximized");
                mainScreen.getWindowCloseButton().getStyleClass().remove("maximized");
            }
            Platform.runLater(()->mainContentPane.applyCss());
        });
        primaryStage.setTitle(App.NATIVE_WINDOW_TITLE);
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
        socketManager.disconnect(true);
        Platform.runLater(() -> {
            mainScreen.clearProjectView();
            statusBarLabel.setText("로그인되지 않았습니다.");
            editorTabView.closeAllClosableTabs();
            mainScreen.setRunButtonVisible(false); // [핵심 수정] 로그아웃 시 버튼 숨기기
            showLoginView();
        });
    }

    public void showLoginView() {editorTabView.showLoginView(() -> loginScreen.createLoginView(this));}

    public void showRegisterView() {editorTabView.showRegisterView(() -> registerScreen.createRegisterView(socketManager, this));}

    public void showProjectPropertiesView(UserProjectsInfo userProjectsInfo) {
        editorTabView.showProjectPropertiesView(
            userProjectsInfo.getProjectID(),
            userProjectsInfo.getProjectName(),
            () -> {
                ProjectPropertiesScreen pps = new ProjectPropertiesScreen();
                return pps.creatProjectProperties(userProjectsInfo);
            }
        );
    }

    public void showSharedOptionView(UserProjectsInfo userProjectsInfo) {
        String projectId = userProjectsInfo.getProjectID();
        
        // onClose 콜백은 컨트롤러가 계속 관리합니다. (상태를 소유하므로)
        Runnable onClose = () -> {
            pendingSharedListCallbacks.remove(projectId);
            pendingAddShareCallbacks.remove(projectId);
        };

        editorTabView.showSharedOptionView(
            projectId,
            userProjectsInfo.getProjectName(),
            () -> { // 뷰 생성 로직을 람다로 전달합니다.
                SharedOptionScreen sos = new SharedOptionScreen();
                return sos.createShareOptionView(
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
            },
            onClose
        );
    }

    public void showSettingsView() {
        // saveCallback은 컨트롤러의 다른 컴포넌트(mainScene, mainScreen)에 영향을 주므로
        // 컨트롤러에 정의하는 것이 맞습니다.
        Runnable saveCallback = () -> {
            ConfigManager.getInstance().loadConfig();

            mainScene.getStylesheets().clear();
            ConfigManager configManager = ConfigManager.getInstance();
            String mainThemePath = configManager.getThemePath("design","mainTheme");
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

        editorTabView.showSettingsView(() -> {
            // closeTabCallback은 EditorTabView 내부에서 처리되도록 람다 안으로 이동할 수 있습니다.
            // 하지만 SettingsView가 직접 closeTabCallback을 필요로 하므로, 여기서 생성해서 전달합니다.
            Runnable closeTabCallback = () -> {
                if (editorTabView != null) {
                    editorTabView.closeTab("settings-tab");
                }
            };
            SettingsView settingsView = new SettingsView(saveCallback, closeTabCallback);
            return settingsView.createView();
        });
    }


    private String calculateSHA256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (int i = 0; i < encodedhash.length; i++) {
                String hex = Integer.toHexString(0xff & encodedhash[i]);
                if(hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
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
            editorTabView.updateRunButtonVisibility(); // [핵심 수정] 로그인 성공 시 버튼 가시성 첫 확인
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
    public void onFileContentResponse(String path, String content, String hash, long version) {
        String calculatedHash = calculateSHA256(content);
        if (calculatedHash.equals(hash)) {
            Platform.runLater(() -> {
                editorTabView.openFileInEditor(path, content, version);
            });
        } else {
            System.err.println("File content hash mismatch for: " + path + ". Retrying...");
            // Find the project info and re-request
            mainScreen.getCurrentProjectForFileTree().ifPresent(projectInfo -> {
                projectController.fileContentRequest(projectInfo, path);
            });
        }
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

    @Override
    public void onLineLockUpdate(String filePath, int line, String userId, String userNickname) {
        String tabId = "file-" + filePath;
        Runnable updateAction = () -> editorTabView.updateLineLockIndicator(filePath, line, userId, userNickname);

        if (editorTabView.getStateManager().isInitializing(tabId)) {
            editorTabView.getStateManager().queueUpdate(tabId, updateAction);
        } else {
            Platform.runLater(updateAction);
        }
    }

    @Override
    public void onLineLockResponse(boolean success, int line) {
        if (success) {
            Platform.runLater(() -> {
                if (userInfo == null) return;

                // Find the file path of the currently active editor
                editorTabView.getActiveCodeArea()
                    .flatMap(activeArea -> editorTabView.getStateManager().findTabIdForCodeArea(activeArea))
                    .ifPresent(tabId -> {
                        String filePath = tabId.substring("file-".length());
                        // Update the state manager with our own lock information
                        editorTabView.updateLineLockIndicator(filePath, line, userInfo.getId(), userInfo.getNickname());
                    });
            });
        }
    }
    @Override
    public void onFileEditBroadcast(String filePath, String type, int position, String text, int length, long newVersion, String uniqId, String requesterId, int cursorPosition) {
        String tabId = "file-" + filePath;
        
        Runnable updateAction = () -> {
            
            editorTabView.getStateManager().getOTManager(tabId).ifPresent(otManager -> {
                Operation op;
                if ("INSERT".equals(type)) {
                    op = new Operation(Operation.Type.INSERT, position, text, cursorPosition, newVersion, uniqId);
                } else { // "DELETE"
                    op = new Operation(Operation.Type.DELETE, position, text, length, cursorPosition, newVersion, uniqId);
                }

                // Delegate EVERYTHING to the OTManager.
                otManager.handleBroadcast(newVersion, uniqId, requesterId, op);
            });
        };
        if (editorTabView.getStateManager().isInitializing(tabId)) {
            editorTabView.getStateManager().queueUpdate(tabId, updateAction);
        } else {
            Platform.runLater(updateAction);
        }
    }

    @Override
    public void onCatchUpResponse(String filePath, JSONArray operations) {
        String tabId = "file-" + filePath;
        Platform.runLater(() -> {
            editorTabView.getStateManager().getOTManager(tabId).ifPresent(otManager -> {
            // 이제 otManager 변수는 진짜 OTManager 객체입니다.
            otManager.handleCatchUp(operations);
        });
        });
    }

    @Override
    public void onClientErrorResponse(JSONObject errData) {
        int ErrCode=errData.getInt("errorCode");
        Gson gson=new Gson();
        
        StringBuilder context=new StringBuilder("에러코드: "+ErrCode);
        Platform.runLater(() -> {
            CustomAlert alert=null;
            try {
                alert=new CustomAlert(primaryStage, null, errData.getString("errorMessage"), null, ()->performLogout());
                Map<String, String> contextMap=gson.fromJson(errData.getJSONObject("context").toString(), Map.class);
                contextMap.forEach((k,v)->context.append("\t"+k+": "+v+"\n"));
                alert.setContext(context.toString());
            } catch (Exception e) {}
            switch (ErrCode) {
                case ProtocolConstants.ERROR_CODE_NOT_AUTHORIZED:{
                    alert.setTitle("세션 권한 없음");
                    alert.showAndWait();
                    break;
                }
                case ProtocolConstants.ERROR_CODE_OWNER_VERIFICATION_FAILED:{
                    alert.setTitle("유효하지 않은 접근");
                    alert.showAndWait();
                    break;
                }
                case ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED:{
                    alert.setTitle("소유자 검증 실패");
                    alert.showAndWait();
                    break;
                }
                case ProtocolConstants.ERROR_CODE_LINE_LOCKED: {
                    int lineNumber = errData.getInt("lineNumber");
                    String lockOwnerId = errData.getString("lockOwner");
                    String lockOwnerNickname = errData.optString("lockOwnerNickname", lockOwnerId);
                    editorTabView.getActiveCodeArea()
                        .flatMap(activeArea -> editorTabView.getStateManager().findTabIdForCodeArea(activeArea))
                        .ifPresent(tabId -> {
                            String filePath = tabId.substring("file-".length());
                            editorTabView.updateLineLockIndicator(filePath, lineNumber, lockOwnerId, lockOwnerNickname);
                        });
                    break;
                }
                case ProtocolConstants.ERROR_CODE_SYNC_ERROR: {
                    editorTabView.getActiveCodeArea()
                        .flatMap(activeArea -> editorTabView.getStateManager().findTabIdForCodeArea(activeArea))
                        .ifPresent(this::reSyncFile); // [핵심 수정] 새로운 메소드 호출
                    break;
                }
            }
        });
    }

    /**
     * [추가] 지정된 탭 ID에 해당하는 파일을 강제로 다시 동기화하는 메소드.
     * 탭을 닫고 서버에 파일 내용을 다시 요청하여 탭을 새로 엽니다.
     * @param tabId 다시 동기화할 탭의 ID (e.g., "file-/path/to/file.java")
     */
    public void reSyncFile(String tabId) {
        if (tabId == null || !tabId.startsWith("file-")) return;

        String filePath = tabId.substring("file-".length());
        System.err.println("Force re-sync for " + filePath + ". Closing tab and re-requesting content.");
        
        editorTabView.closeTab(tabId);
        mainScreen.getCurrentProjectForFileTree().ifPresent(projectInfo -> {
            projectController.fileContentRequest(projectInfo, filePath);
        });
    }

    @Override
    public void onCursorMoveBroadcast(String filePath, String nicknameAndTag, int position) {
        // Do not show our own cursor from the server broadcast.
        if (userInfo != null && (userInfo.getNickname() + "#" + userInfo.getTag()).equals(nicknameAndTag)) {
            return;
        }
        
        Platform.runLater(() -> {
            if (editorTabView != null) {
                // Use the full nickname#tag for both the key and the display name.
                editorTabView.updateUserCursor(filePath, nicknameAndTag, nicknameAndTag, position);
            }
        });
    }

    @Override
    public void onGetProjectFileContent(JSONArray filecontent) {
        new Thread(() -> {
            Path runDirectory = null;
            try {
                runDirectory = Paths.get(".run").toAbsolutePath();
                if (Files.exists(runDirectory)) {
                    Files.walk(runDirectory)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
                }
                Files.createDirectories(runDirectory);
            
                FileExecutionSelectionView fileExecutionSelectionView = editorTabView.getFileExecutionSelectionView();
                String requestProjectId = (fileExecutionSelectionView.getUserProjectsInfo()).getProjectID();
                String selectedFile=fileExecutionSelectionView.getSelectedFile();
                final String executeCommandTemplate = fileExecutionSelectionView.getCommandToExecute(); 
                if (selectedFile.startsWith("/") || selectedFile.startsWith("\\")) selectedFile = selectedFile.substring(1);
                VariableResolver resolver=new VariableResolver(ConfigManager.getInstance(),projectController.buildDynamicContext(selectedFile));
                final String finalCommand = resolver.resolve(executeCommandTemplate);
                // 서버로부터 받은 파일 쓰기
                for (int i = 0; i < filecontent.length(); i++) {
                    JSONObject fileObject = filecontent.getJSONObject(i);
                    if (fileObject.getString("project_id").equals(requestProjectId)) {
                        final String originalPathStr = fileObject.getString("path"); // [수정] 원본 경로 보존
                        String base64Content = fileObject.getString("content");
                        byte[] fileBytes = Base64.getDecoder().decode(base64Content);
                    
                        String fsPathStr = originalPathStr; // 파일 시스템에서 사용할 경로
                        if (fsPathStr.startsWith("/") || fsPathStr.startsWith("\\")) {
                            fsPathStr = fsPathStr.substring(1);
                        }
                    
                        Path filePath = runDirectory.resolve(fsPathStr);
                        System.out.println("Writing server file to: " + filePath.toString());
                        Files.createDirectories(filePath.getParent());
                        Files.write(filePath, fileBytes);
                    
                        // [핵심 수정] UI 업데이트 시에는 '원본' 경로를 사용합니다.
                        Platform.runLater(() -> fileExecutionSelectionView.markFileAsCompleted(originalPathStr));
                    }
                }
            
                // 체크 안된 로컬 탭 파일 덮어쓰기
                List<FileExecutionInfo> uncheckedFiles = fileExecutionSelectionView.getFileList().stream()
                    .filter(f -> !f.isSelected())
                    .collect(Collectors.toList());
            
                for (FileExecutionInfo fileInfo : uncheckedFiles) {
                    String tabId = fileInfo.getTabId();
                    if (editorTabView.isTabOpen(tabId)) {
                        String content = editorTabView.getCodeAreaForTab(tabId).getText();
                        final String originalPathStr = fileInfo.getFilePath(); // [수정] 원본 경로 보존
                    
                        String fsPathStr = originalPathStr; // 파일 시스템에서 사용할 경로
                        if (fsPathStr.startsWith("/") || fsPathStr.startsWith("\\")) {
                            fsPathStr = fsPathStr.substring(1);
                        }
                    
                        Path filePath = runDirectory.resolve(fsPathStr);
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, content, StandardCharsets.UTF_8);
                    
                        // [핵심 수정] UI 업데이트 시에는 '원본' 경로를 사용합니다.
                        Platform.runLater(() -> fileExecutionSelectionView.markFileAsCompleted(originalPathStr));
                    }
                }
                final String workingDirPath = runDirectory.toAbsolutePath().toString();

                Platform.runLater(() -> {
                    if (mainScreen.getRunView() != null) {
                        mainScreen.switchToTab("RUN");
                        (mainScreen.getRunView()).executeProcess(finalCommand, workingDirPath);
                    }
                });
            } catch (IOException e) {
                final String finalRunDirectory = (runDirectory != null) ? runDirectory.toString() : "unknown location";
                Platform.runLater(() -> {
                    new CustomAlert(
                        primaryStage,
                        "파일 저장 실패",
                        "실행 파일을 준비하는 중 오류가 발생했습니다.",
                        "경로: " + finalRunDirectory + "\n오류: " + e.getMessage(),
                        () -> {}
                    ).showAndWait();
                });
                e.printStackTrace();
            } finally {
                Platform.runLater(() -> {
                    editorTabView.getFileExecutionSelectionView().setVisible(true);
                });

                
            }
            
            
        }).start();
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

    public void triggerSearch() {
        if (searchAction != null) {
            searchAction.run();
        }
    }

  
}