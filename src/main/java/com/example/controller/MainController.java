package com.example.controller;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import com.example.model.UserInfo;
import com.example.model.UserProjectsInfo;
import com.example.service.ClientSocketManager;
import com.example.util.ProtocolConstants;
import com.example.util.ReSizeHelper;
import com.example.view.EditorTabView;
import com.example.view.LoginScreen;
import com.example.view.MainScreen;
import com.example.view.ProjectPropertiesScreen;
import com.example.view.RegisterScreen;
import com.example.view.SharedOptionScreen;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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

    private Scene mainScene;
    private Label statusBarLabel;

    private UserInfo userInfo;
    private String lastLoggedInId;
    private char[] lastLoggedInPassword;
    
    private Map<String, Consumer<JSONArray>> pendingSharedListCallbacks = new HashMap<>();
    private Map<String, Consumer<Boolean>> pendingAddShareCallbacks = new HashMap<>();


    public MainController(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.mainScreen = new MainScreen();
        this.loginScreen = new LoginScreen();
        this.registerScreen = new RegisterScreen();
        this.editorTabView = new EditorTabView();
        primaryStage.setTitle("Live Code IDE");
    }

    public void shutdown() {
        editorTabView.shutdownAllHighlighters();
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

        this.mainScene = new Scene(rootPane, 1280, 720);
        mainScene.setFill(Color.TRANSPARENT);
        mainScene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        
        primaryStage.setScene(mainScene);
        primaryStage.show();
        ReSizeHelper.addResizeListener(primaryStage);

        showLoginView();
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
}