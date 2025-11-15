package com.ethis2s.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONObject;

import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.util.ProtocolConstants;
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

    public void clearUserInfo() {
        this.userInfo = null;
    }

    // --- Request Methods ---

    public void createProjectRequest(String projectName) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"name\":\"%s\"}", userInfo.getId(), projectName).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_CREATE_PROJECT_REQUEST);
    }

    public void projectListRequest() {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\"}", userInfo.getId()).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_PROJECT_LIST_REQUEST);
    }

    public void fileListRequest(UserProjectsInfo userProjectsInfo) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"project_id\":\"%s\", \"owner\":\"%s\"}", userInfo.getId(), userProjectsInfo.getProjectID(),userProjectsInfo.getOwner()).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_FILETREE_LIST_REQUEST);
    }

    public void projectDeleteRequest(String projectId) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"project_id\":\"%s\", \"requester\":\"%s\"}", projectId, userInfo.getId()).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_DELETE_PROJECT_REQUEST);
    }
    
    public void sharedListRequest(String projectId) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"project_id\":\"%s\"}", userInfo.getId(), projectId).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_SHARED_LIST_REQUEST);
    }

    public void addShareRequest(String projectId, String nickname, String tag) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"project_id\":\"%s\", \"requester\":\"%s\", \"target_name\":\"%s\", \"target_tag\":\"%s\"}", projectId, userInfo.getId(), nickname, tag).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_ADD_SHARE_REQUEST);
    }

    public void shareDeleteRequest(String projectId, String targetName, String targetTag) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"project_id\":\"%s\", \"requester\":\"%s\", \"target_name\":\"%s\", \"target_tag\":\"%s\"}", projectId, userInfo.getId(), targetName, targetTag).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_DELETE_SHARE_REQUEST);
    }

    public void addFileRequest(String projectId, String path, String owner) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"project_id\":\"%s\", \"path\":\"%s\", \"owner\":\"%s\"}", userInfo.getId(),projectId, path, owner).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_ADD_FILE_REQUEST);
    }

    public void delFileRequest(String projectId, String path, String owner){
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"project_id\":\"%s\", \"path\":\"%s\", \"owner\":\"%s\"}", userInfo.getId(),projectId, path, owner).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_DELETE_FILE_REQUEST);
    }

    public void addFolderRequest(String projectId, String path, String owner) {
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"project_id\":\"%s\", \"path\":\"%s\", \"owner\":\"%s\"}", userInfo.getId(),projectId, path, owner).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_ADD_FOLDER_REQUEST);
    }
    public void delDirRequest(String projectId, String path, String owner){
        if (userInfo == null) return;
        byte[] payload = String.format("{\"requester\":\"%s\", \"project_id\":\"%s\", \"path\":\"%s\", \"owner\":\"%s\"}", userInfo.getId(),projectId, path, owner).getBytes(StandardCharsets.UTF_8);
        sendRequest(payload, ProtocolConstants.UF_DELETE_FOLDER_REQUEST);
    }

    private void sendRequest(byte[] payload, int userField) {
        new Thread(() -> {
            try {
                socketManager.sendPacket(payload, ProtocolConstants.UNFRAGED, userField, ProtocolConstants.PTYPE_JSON);
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