package com.ethis2s.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.json.JSONObject;

import com.ethis2s.model.ProtocolConstants;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.view.MainScreen;

import javafx.application.Platform;

public class ProjectController {

    private final ClientSocketManager socketManager;
    private final MainScreen mainScreen;
    private final MainController mainController;
    private UserInfo userInfo;

    public ProjectController(ClientSocketManager socketManager, MainScreen mainScreen, MainController mainController) {
        this.socketManager = socketManager;
        this.mainScreen = mainScreen;
        this.mainController = mainController;
    }

    public void setUserInfo(UserInfo userInfo) {
        this.userInfo = userInfo;
    }

    public Optional<String> getCurrentUserId() {
        return (userInfo != null) ? Optional.of(userInfo.getId()) : Optional.empty();
    }

    public void clearUserInfo() {
        this.userInfo = null;
    }

    // --- Request Methods ---

    public void createProjectRequest(String projectName) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("name", projectName);
        sendRequest(payload, ProtocolConstants.UF_CREATE_PROJECT_REQUEST);
    }

    public void projectListRequest() {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        sendRequest(payload, ProtocolConstants.UF_PROJECT_LIST_REQUEST);
    }

    public void fileListRequest(UserProjectsInfo userProjectsInfo) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", userProjectsInfo.getProjectID());
        payload.put("owner", userProjectsInfo.getOwner());
        sendRequest(payload, ProtocolConstants.UF_FILETREE_LIST_REQUEST);
    }

    public void fileContentRequest(UserProjectsInfo userProjectsInfo, String path) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", userProjectsInfo.getProjectID());
        payload.put("owner", userProjectsInfo.getOwner());
        payload.put("path", path);
        sendRequest(payload, ProtocolConstants.UF_FILE_CONTENT_REQUEST);
    }

    public void fileEditOperationRequest(String filePath, String type, int position, String text, int length) {
        if (userInfo == null || socketManager == null) return;
        mainController.getCurrentActiveProject().ifPresent(projectInfo -> {
            JSONObject payload = new JSONObject();
            payload.put("requester", userInfo.getId());
            payload.put("project_id", projectInfo.getProjectID());
            payload.put("owner", projectInfo.getOwner());
            payload.put("path", filePath);
            payload.put("type", type);
            payload.put("position", position);

            if ("INSERT".equals(type)) {
                payload.put("text", text);
            } else if ("DELETE".equals(type)) {
                payload.put("length", length);
            }
            sendRequest(payload, ProtocolConstants.UF_FILE_EDIT_OPERATION);
        });
    }

    public void lineLockRequest(String filePath, int line) {
        if (userInfo == null) return;
        mainController.getCurrentActiveProject().ifPresent(projectInfo -> {
            JSONObject payload = new JSONObject();
            payload.put("requester", userInfo.getId());
            payload.put("project_id", projectInfo.getProjectID());
            payload.put("owner", projectInfo.getOwner());
            payload.put("path", filePath);
            payload.put("lineNumber", line);
            sendRequest(payload, ProtocolConstants.UF_LINE_LOCK_REQUEST);
        });
    }

    public void cursorMoveRequest(String filePath, int cursorPosition) {
        if (userInfo == null) return;
        mainController.getCurrentActiveProject().ifPresent(projectInfo -> {
            JSONObject payload = new JSONObject();
            payload.put("requester", userInfo.getId());
            payload.put("project_id", projectInfo.getProjectID());
            payload.put("owner", projectInfo.getOwner());
            payload.put("path", filePath);
            payload.put("cursorPosition", cursorPosition);
            sendRequest(payload, ProtocolConstants.UF_CURSOR_MOVE);
        });
    }

    public void projectDeleteRequest(String projectId) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("project_id", projectId);
        payload.put("requester", userInfo.getId());
        sendRequest(payload, ProtocolConstants.UF_DELETE_PROJECT_REQUEST);
    }
    
    public void sharedListRequest(String projectId) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", projectId);
        sendRequest(payload, ProtocolConstants.UF_SHARED_LIST_REQUEST);
    }

    public void addShareRequest(String projectId, String nickname, String tag) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("project_id", projectId);
        payload.put("requester", userInfo.getId());
        payload.put("target_name", nickname);
        payload.put("target_tag", tag);
        sendRequest(payload, ProtocolConstants.UF_ADD_SHARE_REQUEST);
    }

    public void shareDeleteRequest(String projectId, String targetName, String targetTag) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("project_id", projectId);
        payload.put("requester", userInfo.getId());
        payload.put("target_name", targetName);
        payload.put("target_tag", targetTag);
        sendRequest(payload, ProtocolConstants.UF_DELETE_SHARE_REQUEST);
    }

    public void addFileRequest(String projectId, String path, String owner) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", projectId);
        payload.put("path", path);
        payload.put("owner", owner);
        sendRequest(payload, ProtocolConstants.UF_ADD_FILE_REQUEST);
    }

    public void delFileRequest(String projectId, String path, String owner){
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", projectId);
        payload.put("path", path);
        payload.put("owner", owner);
        sendRequest(payload, ProtocolConstants.UF_DELETE_FILE_REQUEST);
    }

    public void addFolderRequest(String projectId, String path, String owner) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", projectId);
        payload.put("path", path);
        payload.put("owner", owner);
        sendRequest(payload, ProtocolConstants.UF_ADD_FOLDER_REQUEST);
    }
    public void delDirRequest(String projectId, String path, String owner){
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", projectId);
        payload.put("path", path);
        payload.put("owner", owner);
        sendRequest(payload, ProtocolConstants.UF_DELETE_FOLDER_REQUEST);
    }

    private void sendRequest(JSONObject payload, int userField) {
        new Thread(() -> {
            try {
                socketManager.sendJsonPacket(payload, userField, ProtocolConstants.PTYPE_JSON);
            } catch (Exception ex) {
                System.err.println("Request failed for userField " + userField + ": " + ex.getMessage());
            }
        }).start();
    }

    // --- Response Handlers ---

    public void handleProjectListResponse(List<UserProjectsInfo> projectList) {
        Platform.runLater(() -> {
            mainScreen.setProjectList(projectList, this, mainController, userInfo);
        });
    }

    public void handleFileListResponse(JSONObject fileList) {
        Platform.runLater(() -> {
            mainScreen.updateFileTree(fileList);
        });
    }
    
    public void handleDeleteProjectResponse(boolean result) {
        if (result) {
            projectListRequest();
        }
    }

    public void handleDeleteShareResponse(boolean result) {
        if (result) {
            projectListRequest();
        }
    }

    public void handleAddFileResponse(boolean result) {
        if (result) {
            mainScreen.refreshCurrentFileTree(this);
        }
    }

    public void handleAddFolderResponse(boolean result) {
        if (result) {
            mainScreen.refreshCurrentFileTree(this);
        }
    }

    public void handleCreateProjectResponse(boolean result) {
        if (result) {
            projectListRequest();
        } else {
            Platform.runLater(() -> {
                mainScreen.getOutputView().appendText("[ERROR] 프로젝트 생성에 실패했습니다.\n");
            });
        }
    }
}