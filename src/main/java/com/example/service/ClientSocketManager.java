package com.example.service;

import javax.net.ssl.*;
import com.example.model.UserInfo;
import com.example.model.UserProjectsInfo;
import com.example.util.ProtocolConstants;
import socketprotocol.SocketProtocol;
import socketprotocol.ParsedPacket;
import socketprotocol.PacketException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
        new Thread(this::startReceiving).start();
    }

    public void disconnect() {
        try {
            if (sslSocket != null && !sslSocket.isClosed()) {
                sslSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    public void sendPacket(byte[] payload, byte fragFlag, int userValue, byte payloadType) throws IOException {
        if (out == null) throw new IOException("Output stream is not initialized.");
        byte[] packetBytes = protocol.toBytes(payload, fragFlag, payloadType, userValue, 8192);
        out.write(packetBytes);
        out.flush();
    }
    
    private void startReceiving() {
        try {
            while (sslSocket != null && !sslSocket.isClosed()) {
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
            handleDisconnection();
        }
    }

    private void handleDisconnection() {
        if (isReconnecting) return;
        isReconnecting = true;
        disconnect();
        if (callback != null) callback.onDisconnected();

        Thread reconnectThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(RECONNECT_INTERVAL);
                    System.out.println("Attempting to reconnect...");
                    connect();
                    isReconnecting = false;
                    if (callback != null) callback.onReconnected();
                    break; // Exit loop on successful connection
                } catch (Exception e) {
                    System.err.println("Reconnect failed: " + e.getMessage());
                }
            }
        });
        reconnectThread.setDaemon(true); // Set as a daemon thread
        reconnectThread.start();
    }

    private void handlePacket(byte[] packetBytes) {
        // ... (handlePacket logic remains the same)
        try {
            ParsedPacket parsed = protocol.parsePacket(packetBytes);
            int userField = parsed.getUserField();
            byte[] payload = parsed.getPayload();

            switch (userField) {
                case ProtocolConstants.UF_REGISTER_RESPONSE:
                    callback.onRegisterResponse(ByteBuffer.wrap(payload).getInt());
                    break;
                case ProtocolConstants.UF_LOGIN_RESPONSE:
                    callback.onLoginResponse(ByteBuffer.wrap(payload).getInt());
                    break;
                case ProtocolConstants.UF_USER_INFO:
                    JSONObject userJson = new JSONObject(new String(payload));
                    callback.onUserInfoReceived(new UserInfo(userJson.getString("id"), userJson.getString("nickname"), userJson.getString("tag")));
                    break;
                case ProtocolConstants.UF_PROJECT_LIST_RESPONSE:
                    JSONArray projectsJson = new JSONArray(new String(payload));
                    List<UserProjectsInfo> projects = new ArrayList<>();
                    for (int i = 0; i < projectsJson.length(); i++) {
                        projects.add(new UserProjectsInfo(projectsJson.getJSONObject(i)));
                    }
                    callback.onProjectListResponse(projects);
                    break;
                case ProtocolConstants.UF_FILETRE_LIST_RESPONSE:
                    JSONArray fileListJson = new JSONArray(new String(payload));
                    String pId = fileListJson.getJSONObject(0).getString("project_id");
                    callback.onFileListResponse(pId, fileListJson.getJSONObject(1));
                    break;
                case ProtocolConstants.UF_CREATE_PROJECT_RSPONSE:
                    callback.onCreateProjectResponse(payload[0] != 0);
                    break;
                case ProtocolConstants.UF_DELETE_PROJECT_RSPONSE:
                    callback.onDeleteProjectResponse(payload[0] != 0);
                    break;
                case ProtocolConstants.UF_DELETE_SHARE_RESPONSE:
                    JSONObject delShareJson = new JSONObject(new String(payload));
                    callback.onDeleteShareResponse(delShareJson.getString("project_id"), delShareJson.getBoolean("result"));
                    break;
                case ProtocolConstants.UF_SHARED_LIST_RESPONSE:
                    JSONObject sharedListJson = new JSONObject(new String(payload));
                    callback.onSharedListResponse(sharedListJson.getString("project_id"), sharedListJson.getJSONArray("shared_with"));
                    break;
                case ProtocolConstants.UF_ADD_SHARE_RESPONSE:
                    JSONObject addShareJson = new JSONObject(new String(payload));
                    callback.onAddShareResponse(addShareJson.getString("project_id"), addShareJson.getBoolean("result"));
                    break;
                case ProtocolConstants.UF_ADD_FILE_RESPONSE:
                    callback.onAddFileResponse(payload[0] != 0);
                    break;
                case ProtocolConstants.UF_ADD_FOLDER_RESPONSE:
                    callback.onAddFolderResponse(payload[0] != 0);
                    break;
                default:
                    callback.onPacketReceived(parsed);
                    break;
            }
        } catch (PacketException | JSONException e) {
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
        void onCreateProjectResponse(boolean result);
        void onDeleteProjectResponse(boolean result);
        void onSharedListResponse(String projectId, JSONArray sharedList);
        void onAddShareResponse(String projectId, boolean success);
        void onDeleteShareResponse(String projectID, boolean result);
        void onAddFileResponse(boolean result);
        void onAddFolderResponse(boolean result);
    }
}
