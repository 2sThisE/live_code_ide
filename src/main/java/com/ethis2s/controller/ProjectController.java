package com.ethis2s.controller;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

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
    private Consumer<JSONObject> fileListCallback;

    private final BlockingQueue<RequestRecord> requestQueue = new LinkedBlockingQueue<>();
    private final Thread requestWorkerThread;

    // 내부 클래스로 요청 데이터 구조화
    private static class RequestRecord {
        final JSONObject payload;
        final int userField;

        RequestRecord(JSONObject payload, int userField) {
            this.payload = payload;
            this.userField = userField;
        }
    }

    public ProjectController(ClientSocketManager socketManager, MainScreen mainScreen, MainController mainController) {
        this.socketManager = socketManager;
        this.mainScreen = mainScreen;
        this.mainController = mainController;

        // 요청을 순차적으로 처리할 워커 스레드 시작
        this.requestWorkerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    RequestRecord request = requestQueue.take(); // 큐에서 요청을 꺼냄 (없으면 대기)
                    try {
                        socketManager.sendJsonPacket(request.payload, request.userField, ProtocolConstants.PTYPE_JSON);
                    } catch (Exception ex) {
                        System.err.println("Request failed for userField " + request.userField + ": " + ex.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 인터럽트 상태 유지
                System.out.println("Request worker thread interrupted.");
            }
        });
        this.requestWorkerThread.setDaemon(true);
        this.requestWorkerThread.start();
    }

    public void setUserInfo(UserInfo userInfo) {
        this.userInfo = userInfo;
    }

    public Optional<String> getCurrentUserId() {
        return (userInfo != null) ? Optional.of(userInfo.getId()) : Optional.empty();
    }

    public Optional<String> getCurrentUserNicknameAndTag() {
        if (userInfo != null) {
            return Optional.of(userInfo.getNickname() + "#" + userInfo.getTag());
        }
        return Optional.empty();
    }

    public void clearUserInfo() {
        this.userInfo = null;
    }
    public Map<String, String> buildDynamicContext(String relativeEntryPath) {
        // 1. 정보를 담을 가방(Map)을 준비합니다.
        Map<String, String> context = new HashMap<>();
        
        // relativeEntryPath 예시: "src/com/example/Hello.java"
        
        // 2. {file} 변수 채우기
        // 사용자가 JSON에 "{file}"이라고 쓴 자리를 이 값으로 바꿉니다.
        context.put("file", relativeEntryPath);

        // 3. 파일 이름만 뽑아내기
        Path entryPath = Paths.get(relativeEntryPath);
        String fileNameStr = entryPath.getFileName().toString(); // "Hello.java"
        
        // 4. {filename} 변수 채우기
        context.put("filename", fileNameStr);
        
        // 5. {name} 변수 채우기 (확장자 제거)
        // "Hello.java"에서 "." 앞부분만 잘라냅니다 -> "Hello"
        int dotIdx = fileNameStr.lastIndexOf('.');
        if (dotIdx > 0) {
            context.put("name", fileNameStr.substring(0, dotIdx));
        } else {
            context.put("name", fileNameStr);
        }

        // 6. 완성된 가방 반환
        return context;
    }

    // --- Request Methods ---

    public void createProjectRequest(String projectName) {
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("name", projectName);
        sendRequest(payload, ProtocolConstants.UF_CREATE_PROJECT_REQUEST);
    }

    public void closeFileRequest(UserProjectsInfo userProjectsInfo, String filePath){
        if (userInfo == null) return;
        JSONObject payload = new JSONObject();
        payload.put("requester", userInfo.getId());
        payload.put("project_id", userProjectsInfo.getProjectID());
        payload.put("owner", userProjectsInfo.getOwner());
        payload.put("path", filePath);
        sendRequest(payload, ProtocolConstants.UF_FILE_CLOSE_REQUEST);
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

    public void fileListRequest(UserProjectsInfo userProjectsInfo, Consumer<JSONObject> callback) {
        this.fileListCallback = callback;
        fileListRequest(userProjectsInfo);
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

    public void fileEditOperationRequest(String filePath, String type, int position, String text, int length, int cursorPosition, long version, String uniqId) {
        if (userInfo == null || socketManager == null) return;
        mainController.getCurrentActiveProject().ifPresent(projectInfo -> {
            JSONObject payload = new JSONObject();
            payload.put("requester", userInfo.getId());
            payload.put("project_id", projectInfo.getProjectID());
            payload.put("owner", projectInfo.getOwner());
            payload.put("path", filePath);
            payload.put("type", type);
            payload.put("position", position);
            payload.put("cursorPosition", cursorPosition);
            payload.put("version", version);
            payload.put("uniqId", uniqId);

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

    public void fileNameChangeRequest(JSONObject payload, String projectId){
        if(userInfo==null) return;
        payload.put("project_id",projectId);
        payload.put("requester", userInfo.getId());
        sendRequest(payload, ProtocolConstants.UF_CHANG_FILE_NAME_REQUEST);
    }

    public void fileLocationChangeRequest(JSONObject payload, String projectId){
        if(userInfo==null) return;
        payload.put("project_id",projectId);
        payload.put("requester", userInfo.getId());
        sendRequest(payload, ProtocolConstants.UF_CHANG_FILE_LOC_REQUEST);
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
        requestQueue.add(new RequestRecord(payload, userField));
    }

    public void getProjecFilesRequest(JSONObject payload){
        if(userInfo==null) return;
        payload.put("requester", userInfo.getId());
        sendRequest(payload, ProtocolConstants.UF_GET_PROJECT_FILE_REQUEST);
    }

    // --- Response Handlers ---

    public void handleProjectListResponse(List<UserProjectsInfo> projectList) {
        Platform.runLater(() -> {
            mainScreen.setProjectList(projectList, this, mainController, userInfo);
        });
    }

    public void handleFileListResponse(JSONObject fileList) {
        Platform.runLater(() -> {
            if (fileListCallback != null) {
                fileListCallback.accept(fileList);
                fileListCallback = null; // 콜백은 한 번만 사용
            } else {
                mainScreen.updateFileTree(fileList);
            }
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