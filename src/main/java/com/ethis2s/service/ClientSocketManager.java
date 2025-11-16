package com.ethis2s.service;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.ethis2s.model.ProtocolConstants;
import com.ethis2s.model.UserInfo;
import com.ethis2s.model.UserProjectsInfo;

import socketprotocol.PacketException;
import socketprotocol.ParsedPacket;
import socketprotocol.SocketProtocol;

public class ClientSocketManager {
    private static final String SERVER_IP = "sjc07250.iptime.org";
    private static final int SERVER_PORT = 8080;
    private static final int RECONNECT_INTERVAL = 2000; // 2 seconds

    private SSLSocket sslSocket;
    private DataOutputStream out;
    private DataInputStream in;
    private final SocketProtocol protocol;
    private final ClientSocketCallback callback;
    private volatile boolean isReconnecting = false;
    private volatile boolean isRunning = true;
    private Thread receivingThread;
    private final Map<Integer, ByteArrayOutputStream> fragmentBuffers = new HashMap<>();
    private static final int MAX_PAYLOAD_SIZE = 8188; // 8196 - 8 (Header + CRC32)

    public ClientSocketManager(ClientSocketCallback callback) {
        this.callback = callback;
        this.protocol = new SocketProtocol();
    }

    public void connect() throws Exception {
        TrustManager[] trustAllCerts = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }};
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        SSLSocketFactory factory = sslContext.getSocketFactory();
        sslSocket = (SSLSocket) factory.createSocket(SERVER_IP, SERVER_PORT);
        sslSocket.startHandshake();

        out = new DataOutputStream(sslSocket.getOutputStream());
        in = new DataInputStream(sslSocket.getInputStream());

        if (callback != null) callback.onConnected();
        receivingThread = new Thread(this::startReceiving);
        receivingThread.start();
    }

    public void disconnect() {
        System.out.println("DEBUG: ClientSocketManager.disconnect() called.");
        isRunning = false; // Signal the loop to stop
        try {
            if (sslSocket != null && !sslSocket.isClosed()) {
                System.out.println("DEBUG: Closing socket.");
                sslSocket.close(); // This will interrupt the blocking read() call
            }
            if (receivingThread != null && receivingThread.isAlive()) {
                System.out.println("DEBUG: Waiting for receiving thread to join.");
                receivingThread.join(1000); // Wait for the thread to die
                if (receivingThread.isAlive()) {
                    System.out.println("DEBUG: Thread is still alive, interrupting.");
                    receivingThread.interrupt(); // Forcefully interrupt if it's stuck
                }
            }
        } catch (IOException e) {
            // Ignore errors on close
        } catch (InterruptedException e) {
            System.out.println("DEBUG: Interrupted while waiting for thread to join.");
            Thread.currentThread().interrupt(); // Preserve interrupt status
        }
        System.out.println("DEBUG: ClientSocketManager.disconnect() finished.");
    }

    public void sendJsonPacket(JSONObject json, int userValue, byte payloadType) throws IOException {
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        int payloadSize = payload.length;

        if (payloadSize <= MAX_PAYLOAD_SIZE) {
            sendPacket(payload, ProtocolConstants.UNFRAGED, userValue, payloadType);
            return;
        }

        int offset = 0;
        while (offset < payloadSize) {
            int length = Math.min(MAX_PAYLOAD_SIZE, payloadSize - offset);
            byte[] chunk = Arrays.copyOfRange(payload, offset, offset + length);
            
            offset += length; // Move offset before the check for the last chunk

            byte fragFlag = (offset >= payloadSize) ? ProtocolConstants.UNFRAGED : ProtocolConstants.FRAGED;
            
            sendPacket(chunk, fragFlag, userValue, payloadType);
        }
    }

    public void sendPacket(byte[] payload, byte fragFlag, int userValue, byte payloadType) throws IOException {
        if (out == null) throw new IOException("Output stream is not initialized.");
        byte[] packetBytes = protocol.toBytes(payload, fragFlag, payloadType, userValue, 8192);
        out.write(packetBytes);
        out.flush();
    }
    
    private void startReceiving() {
        System.out.println("DEBUG: startReceiving() thread started.");
        try {
            while (isRunning) {
                if (sslSocket == null || sslSocket.isClosed()) {
                    break; 
                }
                
                byte[] headerBytes = new byte[4];
                int bytesRead = in.read(headerBytes);
                if (bytesRead < 4) {
                    throw new IOException("Connection closed or stream ended abruptly.");
                }

                ByteBuffer headerBuffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
                long packetLength = (headerBuffer.getInt(0) >> 4) & 0x1FFF;
                if (packetLength < 8) continue;

                byte[] fullPacketBytes = new byte[(int) packetLength];
                System.arraycopy(headerBytes, 0, fullPacketBytes, 0, 4);
                in.readFully(fullPacketBytes, 4, (int) packetLength - 4);

                handlePacket(fullPacketBytes);
            }
        } catch (IOException e) {
            System.out.println("DEBUG: IOException in startReceiving(): " + e.getMessage());
            if (isRunning) {
                handleDisconnection();
            }
        }
        System.out.println("DEBUG: startReceiving() thread finished.");
    }

    public void initiateReconnection() {
        if (isRunning) {
            handleDisconnection();
        }
    }

    private void handleDisconnection() {
        if (!isRunning || isReconnecting) {
            return;
        }
        isReconnecting = true;
        
        try {
            if (sslSocket != null && !sslSocket.isClosed()) {
                sslSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        if (callback != null) callback.onDisconnected();

        Thread reconnectThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(RECONNECT_INTERVAL);
                    System.out.println("Attempting to reconnect...");
                    connect();
                    isReconnecting = false;
                    if (callback != null) callback.onReconnected();
                    break;
                } catch (Exception e) {
                    System.err.println("Reconnect failed: " + e.getMessage());
                }
            }
        });
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void handlePacket(byte[] packetBytes) {
        try {
            ParsedPacket parsed = protocol.parsePacket(packetBytes);
            int userField = parsed.getUserField();
            byte[] payload = parsed.getPayload();
            byte[] finalPayload;

            if (parsed.getFragmentFlag() == ProtocolConstants.FRAGED) {
                fragmentBuffers.computeIfAbsent(userField, k -> new ByteArrayOutputStream()).write(payload);
                return; 
            } 
            
            ByteArrayOutputStream buffer = fragmentBuffers.get(userField);
            if (buffer != null) {
                buffer.write(payload);
                finalPayload = buffer.toByteArray();
                fragmentBuffers.remove(userField);
            } else {
                finalPayload = payload;
            }

            // Create a new, definitive ParsedPacket object from the (potentially reassembled) payload.
            ParsedPacket finalPacket = new ParsedPacket(
                parsed.getProtocolVersion(),
                finalPayload.length + 8, // +8 for header and CRC
                ProtocolConstants.UNFRAGED,
                parsed.getPayloadType(),
                userField,
                finalPayload
            );

            // All subsequent logic will now operate on the consistent finalPacket.
            System.out.println("ReciveFormServer: "+new String(finalPacket.getPayload()));
            switch (finalPacket.getUserField()) {
                case ProtocolConstants.UF_REGISTER_RESPONSE:
                    callback.onRegisterResponse(ByteBuffer.wrap(finalPacket.getPayload()).getInt());
                    break;
                case ProtocolConstants.UF_LOGIN_RESPONSE:
                    callback.onLoginResponse(ByteBuffer.wrap(finalPacket.getPayload()).getInt());
                    break;
                case ProtocolConstants.UF_USER_INFO:
                    JSONObject userJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    callback.onUserInfoReceived(new UserInfo(userJson.getString("id"), userJson.getString("nickname"), userJson.getString("tag")));
                    break;
                case ProtocolConstants.UF_PROJECT_LIST_RESPONSE:
                    JSONArray projectsJson = new JSONArray(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    List<UserProjectsInfo> projects = new ArrayList<>();
                    for (int i = 0; i < projectsJson.length(); i++) {
                        projects.add(new UserProjectsInfo(projectsJson.getJSONObject(i)));
                    }
                    callback.onProjectListResponse(projects);
                    break;
                case ProtocolConstants.UF_FILETREE_LIST_RESPONSE:
                    JSONArray fileListJson = new JSONArray(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    String pId = fileListJson.getJSONObject(0).getString("project_id");
                    callback.onFileListResponse(pId, fileListJson.getJSONObject(1));
                    break;
                case ProtocolConstants.UF_FILE_CONTENT_RESPONSE:
                    JSONObject fileContentJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    callback.onFileContentResponse(fileContentJson.getString("path"), fileContentJson.getString("content"), fileContentJson.getString("hash"));
                    break;
                case ProtocolConstants.UF_CREATE_PROJECT_RSPONSE:
                    callback.onCreateProjectResponse(finalPacket.getPayload()[0] != 0);
                    break;
                case ProtocolConstants.UF_DELETE_PROJECT_RSPONSE:
                    callback.onDeleteProjectResponse(finalPacket.getPayload()[0] != 0);
                    break;
                case ProtocolConstants.UF_DELETE_SHARE_RESPONSE:
                    JSONObject delShareJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    callback.onDeleteShareResponse(delShareJson.getString("project_id"), delShareJson.getBoolean("result"));
                    break;
                case ProtocolConstants.UF_SHARED_LIST_RESPONSE:
                    JSONObject sharedListJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    callback.onSharedListResponse(sharedListJson.getString("project_id"), sharedListJson.getJSONArray("shared_with"));
                    break;
                case ProtocolConstants.UF_ADD_SHARE_RESPONSE:
                    JSONObject addShareJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    callback.onAddShareResponse(addShareJson.getString("project_id"), addShareJson.getBoolean("result"));
                    break;
                case ProtocolConstants.UF_ADD_FILE_RESPONSE:
                    callback.onAddFileResponse(finalPacket.getPayload()[0] != 0);
                    break;
                case ProtocolConstants.UF_ADD_FOLDER_RESPONSE:
                    callback.onAddFolderResponse(finalPacket.getPayload()[0] != 0);
                    break;
                case ProtocolConstants.UF_LINE_LOCK_BROADCAST:
                    {
                        JSONObject lockJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                        String filePath = lockJson.getString("path");
                        int line = lockJson.getInt("lineNumber");
                        String lockOwner = lockJson.getString("lockOwner");
                        callback.onLineLockUpdate(filePath, line, lockOwner, lockOwner);
                    }
                    break;
                case ProtocolConstants.UF_LINE_UNLOCK_BROADCAST:
                    {
                        JSONObject unlockJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                        String filePath = unlockJson.getString("path");
                        int line = unlockJson.getInt("lineNumber");
                        // No owner means the lock is released.
                        callback.onLineLockUpdate(filePath, line, null, null);
                    }
                    break;
                case ProtocolConstants.UF_LINE_LOCK_RESPONSE:
                    JSONObject lockResponseJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    callback.onLineLockResponse(
                        lockResponseJson.getBoolean("success"),
                        lockResponseJson.getInt("lineNumber")
                    );
                    break;
                case ProtocolConstants.UF_CLIENT_ERROR:
                    JSONObject errorJson = new JSONObject(new String(finalPacket.getPayload(), StandardCharsets.UTF_8));
                    if (errorJson.has("lineNumber") && errorJson.has("lockOwner")) {
                        int lineNumber = errorJson.getInt("lineNumber");
                        String lockOwnerId = errorJson.getString("lockOwner");
                        String lockOwnerNickname = errorJson.optString("lockOwnerNickname", lockOwnerId);
                        callback.onFileEditErrorResponse(lineNumber, lockOwnerId, lockOwnerNickname);
                    }
                    break;
                case ProtocolConstants.UF_FILE_EDIT_BROADCAST:
                    {
                        String jsonString = new String(finalPacket.getPayload(), StandardCharsets.UTF_8);
                        System.out.println("[DEBUG] ClientSocketManager: Received UF_FILE_EDIT_BROADCAST: " + jsonString);
                        JSONObject editJson = new JSONObject(jsonString);
                        String editPath = editJson.getString("path");
                        String editType = editJson.getString("type");
                        String user = editJson.getString("user"); // 규약에 따라 user 정보 파싱
                        int editPosition = editJson.getInt("position");
                        String text = editJson.optString("text", "");
                        int length = editJson.optInt("length", 0);
                        // TODO: onFileEditBroadcast 콜백에 user 정보를 전달하도록 추후 수정 필요
                        callback.onFileEditBroadcast(editPath, editType, editPosition, text, length);
                    }
                    break;
                case ProtocolConstants.UF_CURSOR_MOVE_BROADCAST:
                    String jsonString = new String(finalPacket.getPayload(), StandardCharsets.UTF_8);
                    System.out.println("[DEBUG] ClientSocketManager: Received UF_CURSOR_MOVE_BROADCAST: " + jsonString);
                    JSONObject cursorJson = new JSONObject(jsonString);
                    String cursorPath = cursorJson.getString("path");
                    String user = cursorJson.getString("user");
                    int cursorPosition = cursorJson.getInt("cursorPosition");
                    callback.onCursorMoveBroadcast(cursorPath, user, user, cursorPosition);
                    break;
                default:
                    callback.onPacketReceived(finalPacket);
            }
        } catch (PacketException | JSONException | IOException e) {
            if (callback != null) {
                callback.onError("Error parsing packet: " + e.getMessage());
            }
        }
    }

    public interface ClientSocketCallback {
        void onConnected();
        void onDisconnected();
        void onReconnected();
        void onPacketReceived(ParsedPacket packet);
        void onError(String message);
        void onRegisterResponse(int responseCode);
        void onLoginResponse(int responseCode);
        void onUserInfoReceived(UserInfo userInfo);
        void onProjectListResponse(List<UserProjectsInfo> projectList);
        void onFileListResponse(String projectID, JSONObject fileList);
        void onFileContentResponse(String path, String content, String hash);
        void onCreateProjectResponse(boolean result);
        void onDeleteProjectResponse(boolean result);
        void onSharedListResponse(String projectId, JSONArray sharedList);
        void onAddShareResponse(String projectId, boolean success);
        void onDeleteShareResponse(String projectID, boolean result);
        void onAddFileResponse(boolean result);
        void onAddFolderResponse(boolean result);
        void onLineLockUpdate(String filePath, int line, String userId, String userNickname);
        void onLineLockResponse(boolean success, int line);
        void onFileEditBroadcast(String filePath, String type, int position, String text, int length);
        void onFileEditErrorResponse(int lineNumber, String lockOwnerId, String lockOwnerNickname);
        void onCursorMoveBroadcast(String filePath, String userId, String userNickname, int position);
    }
}
