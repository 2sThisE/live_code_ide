package com.ethis2s.service;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
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
    private static final int MAX_PAYLOAD_SIZE = 8100; // Use a safer margin below the theoretical max of 8188

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

        // 타임아웃 5초로 지정
        SSLSocket tempSocket = (SSLSocket) factory.createSocket();
        tempSocket.connect(new InetSocketAddress(SERVER_IP, SERVER_PORT), 5000);
        tempSocket.startHandshake();

        sslSocket = tempSocket;
        out = new DataOutputStream(sslSocket.getOutputStream());
        in = new DataInputStream(sslSocket.getInputStream());

        if (callback != null) callback.onConnected();

        receivingThread = new Thread(this::startReceiving);
        receivingThread.start();
    }

    public void disconnect(boolean reconnect) {
        System.out.println("DEBUG: ClientSocketManager.disconnect() called.");
        isRunning = false; // Signal the loop to stop
        try {
            if (sslSocket != null && !sslSocket.isClosed()) {
                System.out.println("DEBUG: Closing socket.");
                sslSocket.close(); // This will interrupt the blocking read() call
            }
            if (receivingThread != null && receivingThread.isAlive()) {
                receivingThread.join(1000); // Wait for the thread to die
                if (receivingThread.isAlive()) {
                    receivingThread.interrupt(); // Forcefully interrupt if it's stuck
                }
            }
        } catch (IOException e) {
            // Ignore errors on close
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Preserve interrupt status
        }
        System.out.println("DEBUG: ClientSocketManager.disconnect() finished.");

        if (reconnect) {
            boolean socketDead = (sslSocket == null || sslSocket.isClosed());
            boolean threadDead = (receivingThread == null || !receivingThread.isAlive());
            if (socketDead && threadDead) {
                try {
                    isRunning = true;
                    connect();
                } catch (Exception e) {
                    System.out.println("DEBUG: ClientSocketManager.connect() fail: " + e);
                    isRunning = false;
                }
            }
        }
    }



    public void sendJsonPacket(JSONObject json, int userValue, byte payloadType) throws IOException {
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        int totalSize = payload.length;

        if (totalSize == 0) {
            sendPacket(new byte[0], ProtocolConstants.UNFRAGED, userValue, payloadType);
            out.flush();
            return;
        }

        // The raw JSON bytes are split here, which is the correct, intended behavior.
        int offset = 0;
        int chunksSent = 0;
        final int FLUSH_INTERVAL = 16; // Flush every 16 chunks

        while (offset < totalSize) {
            int chunkSize = Math.min(MAX_PAYLOAD_SIZE, totalSize - offset);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(payload, offset, chunk, 0, chunkSize);

            offset += chunkSize;

            byte fragFlag = (offset >= totalSize) ? ProtocolConstants.UNFRAGED : ProtocolConstants.FRAGED;
            
            sendPacket(chunk, fragFlag, userValue, payloadType);
            chunksSent++;

            if (chunksSent >= FLUSH_INTERVAL) {
                out.flush();
                chunksSent = 0;
            }
        }
        // Flush any remaining data in the buffer
        out.flush();
    }

    public void sendPacket(byte[] payload, byte fragFlag, int userValue, byte payloadType) throws IOException {
        if (out == null) throw new IOException("Output stream is not initialized.");
        byte[] packetBytes = protocol.toBytes(payload, fragFlag, payloadType, userValue, 8196);
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
            if (sslSocket != null && !sslSocket.isClosed()) sslSocket.close();
        } catch (IOException e) {}
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
                    callback.onFileContentResponse(
                        fileContentJson.getString("path"), 
                        fileContentJson.getString("content"), 
                        fileContentJson.getString("hash"),
                        fileContentJson.getLong("version")
                    );
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
                    callback.onClientErrorResponse(errorJson);
                    break;
                case ProtocolConstants.UF_FILE_EDIT_BROADCAST:
                    {
                        String jsonString = new String(finalPacket.getPayload(), StandardCharsets.UTF_8);
                        JSONObject editJson = new JSONObject(jsonString);
                        String editPath = editJson.getString("path");
                        String editType = editJson.getString("type");
                        String requesterId = editJson.getString("user");
                        int editPosition = editJson.getInt("position");
                        String text = editJson.optString("text", "");
                        int length = editJson.optInt("length", 0);
                        long newVersion = editJson.getLong("version");
                        String uniqId = editJson.getString("uniqId");
                        int cursorPosition = editJson.optInt("cursorPosition", -1);
                        callback.onFileEditBroadcast(editPath, editType, editPosition, text, length, newVersion, uniqId, requesterId, cursorPosition);
                    }
                    break;
                case ProtocolConstants.UF_HISTORY:
                    {
                        String jsonString = new String(finalPacket.getPayload(), StandardCharsets.UTF_8);
                        JSONArray opsArray = new JSONArray(jsonString);
                        if (!opsArray.isEmpty()) {
                            // 파일 경로는 모든 연산에서 동일하므로 첫 번째 연산에서 추출
                            String filePath = opsArray.getJSONObject(0).getString("path");
                            callback.onCatchUpResponse(filePath, opsArray);
                        }
                    }
                    break;
                case ProtocolConstants.UF_CURSOR_MOVE_BROADCAST:
                    {
                        String jsonString = new String(finalPacket.getPayload(), StandardCharsets.UTF_8);
                        JSONObject cursorJson = new JSONObject(jsonString);
                        String cursorPath = cursorJson.getString("path");
                        String nicknameAndTag = cursorJson.getString("user"); // 'user' is actually nickname#tag
                        int cursorPosition = cursorJson.getInt("cursorPosition");
                        callback.onCursorMoveBroadcast(cursorPath, nicknameAndTag, cursorPosition);
                    }
                    break;
                case ProtocolConstants.UF_GET_PROJECT_FILE_RESPONSE:
                    {
                        JSONArray filecontent=new JSONArray(new String(finalPacket.getPayload(),StandardCharsets.UTF_8));
                        callback.onGetProjectFileContent(filecontent);
                    }
                default:
                    callback.onPacketReceived(finalPacket);
            }
        } catch (PacketException | JSONException | IOException e) {
            if (callback != null) {
                callback.onError("Error parsing packet: " + e.getMessage());
                e.printStackTrace();
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
        void onFileContentResponse(String path, String content, String hash, long version);
        void onCreateProjectResponse(boolean result);
        void onDeleteProjectResponse(boolean result);
        void onSharedListResponse(String projectId, JSONArray sharedList);
        void onAddShareResponse(String projectId, boolean success);
        void onDeleteShareResponse(String projectID, boolean result);
        void onAddFileResponse(boolean result);
        void onAddFolderResponse(boolean result);
        void onLineLockUpdate(String filePath, int line, String userId, String userNickname);
        void onLineLockResponse(boolean success, int line);
        void onFileEditBroadcast(String filePath, String type, int position, String text, int length, long newVersion, String uniqId, String requesterId, int cursorPosition);
        void onClientErrorResponse(JSONObject error);
        void onCursorMoveBroadcast(String filePath, String nicknameAndTag, int position);
        void onCatchUpResponse(String filePath, JSONArray operations);
        void onGetProjectFileContent(JSONArray filecontent);
    }
}
