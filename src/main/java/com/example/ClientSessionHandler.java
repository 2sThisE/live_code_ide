package com.example;
import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Formatter;
import java.util.List;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import socketprotocol.PacketException;
import socketprotocol.ParsedPacket;
import socketprotocol.SocketProtocol;

/**
 * 클라이언트와의 개별 세션을 처리하는 클래스입니다.
 * 이 클래스는 클라이언트 개발자를 위한 실시간 동시 편집 프로토콜 가이드를 포함합니다.
 *
 * ## 실시간 동시 편집 클라이언트 구현 가이드 ##
 *
 * 클라이언트는 아래의 절차에 따라 서버와 통신하여 실시간 동시 편집 기능을 구현해야 합니다.
 * 모든 통신 페이로드는 JSON 형식을 사용합니다.
 *
 * ### 1단계: 파일 열기 요청
 * 사용자가 파일을 열 때, 서버에 파일의 전체 내용을 요청합니다.
 * - **프로토콜**: `UF_FILE_CONTENT_REQUEST`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "owner": "프로젝트_소유자_ID",
 *     "path": "열고자_하는_파일_경로"
 *   }
 *   ```
 * - **서버 응답**: `UF_FILE_CONTENT_RESPONSE` 프로토콜과 함께 파일의 전체 내용(`content`)을 받습니다.
 * - **클라이언트 동작**: 받은 `content`를 에디터에 표시합니다. 이 시점부터 서버는 해당 클라이언트를 파일의 '편집자'로 인식합니다.
 *
 * ### 2단계: 라인 락(Lock) 획득
 * 사용자가 특정 라인을 편집하기 **직전** (예: 키보드 입력 시작, 붙여넣기 시도), 반드시 해당 라인에 대한 락을 요청해야 합니다.
 * - **프로토콜**: `UF_LINE_LOCK_REQUEST`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "owner": "프로젝트_소유자_ID",
 *     "path": "현재_파일_경로",
 *     "lineNumber": 1 // 1부터 시작하는 라인 번호
 *   }
 *   ```
 * - **서버 응답**: `UF_LINE_LOCK_RESPONSE` 프로토콜과 함께 성공 여부를 받습니다.
 *   - **성공 시**: `{"success": true, ...}`
 *   - **실패 시**: `{"success": false, "lockOwner": "다른_사용자_ID", ...}`
 * - **클라이언트 동작**: 락 획득에 성공하면 사용자 입력을 허용합니다. 실패하면 해당 라인의 편집을 막고, UI에 누가 락을 소유하고 있는지 표시할 수 있습니다.
 *
 * ### 3단계: 편집 연산(Operation) 전송
 * 라인 락을 획득한 상태에서 사용자가 내용을 수정하면(예: 한 글자 입력/삭제), 즉시 해당 변경 사항을 '연산'으로 서버에 전송합니다.
 * - **프로토콜**: `UF_FILE_EDIT_OPERATION`
 * - **전송할 JSON**:
 *   ```json
 *   // 텍스트 삽입 시
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "...",
 *     "owner": "...",
 *     "path": "...",
 *     "type": "INSERT",
 *     "position": 10, // 전체 텍스트 기준 삽입 위치 (0부터 시작)
 *     "text": "a", // 삽입된 텍스트
 *     "cursorPosition": 11 // (선택 사항) 연산 후의 새로운 커서 위치
 *   }
 *
 *   // 텍스트 삭제 시
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "...",
 *     "owner": "...",
 *     "path": "...",
 *     "type": "DELETE",
 *     "position": 10, // 전체 텍스트 기준 삭제 시작 위치
 *     "length": 1, // 삭제된 글자 수
 *     "cursorPosition": 10 // (선택 사항) 연산 후의 새로운 커서 위치
 *   }
 *   ```
 * - **서버 동작**: 서버는 이 연산을 수신하여 파일에 적용하고, 다른 모든 편집자에게 이 연산과 함께 커서 위치를 브로드캐스트합니다.
 *
 * ### 4단계: 편집 연산 수신 (브로드캐스트)
 * 다른 사용자가 보낸 편집 연산을 서버로부터 수신합니다.
 * - **프로토콜**: `UF_FILE_EDIT_BROADCAST`
 * - **수신할 JSON**: 3단계의 편집 연산과 동일한 형식의 JSON을 받습니다. `cursorPosition` 필드가 포함될 수 있습니다.
 * - **클라이언트 동작**: 수신한 연산을 로컬 에디터의 텍스트에 **즉시 적용**하고, `cursorPosition`이 있다면 해당 사용자의 커서를 업데이트합니다. 이 연산을 다시 서버로 보내면 안 됩니다(무한 루프 방지).
 *
 * ### 5단계: 라인 락 해제
 * 사용자의 커서가 다른 라인으로 이동하거나, 해당 라인에서 일정 시간 입력이 없으면, 점유했던 락을 **반드시 해제**해야 합니다.
 * - **프로토콜**: `UF_LINE_UNLOCK_REQUEST`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "...",
 *     "owner": "...",
 *     "path": "...",
 *     "lineNumber": 1 // 해제할 라인 번호
 *   }
 *   ```
 * - **서버 응답**: `UF_LINE_UNLOCK_RESPONSE` 프로토콜과 함께 성공 여부를 받습니다.
 * - **클라이언트 동작**: 락이 해제되면 다른 사용자가 해당 라인을 편집할 수 있게 됩니다.
 *
 * ### 6단계: 커서 이동 정보 전송 (코드 변경 없음)
 * 사용자가 코드를 변경하지 않고 키보드나 마우스로 커서 위치만 이동했을 때, 이 정보를 서버에 전송합니다.
 * - **프로토콜**: `UF_CURSOR_MOVE`
 * - **전송할 JSON**:
 *   ```json
 *   {
 *     "requester": "본인_사용자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "owner": "프로젝트_소유자_ID",
 *     "path": "현재_파일_경로",
 *     "cursorPosition": 25 // 커서의 현재 위치 (0부터 시작하는 문자열 인덱스)
 *   }
 *   ```
 * - **서버 응답**: 별도의 직접적인 응답은 없으며, 서버는 이 정보를 다른 편집자에게 브로드캐스트합니다.
 *
 * ### 7단계: 커서 이동 정보 수신 (브로드캐스트)
 * 다른 사용자가 보낸 커서 이동 정보를 서버로부터 수신합니다.
 * - **프로토콜**: `UF_CURSOR_MOVE_BROADCAST`
 * - **수신할 JSON**:
 *   ```json
 *   {
 *     "user_id": "커서_소유자_ID",
 *     "project_id": "현재_프로젝트_ID",
 *     "path": "현재_파일_경로",
 *     "cursorPosition": 25 // 커서의 현재 위치
 *   }
 *   ```
 * - **클라이언트 동작**: 수신한 `user_id`의 커서를 `cursorPosition`으로 업데이트하여 UI에 표시합니다.
 *
 * ### 연결 종료
 * 클라이언트의 연결이 끊어지면, 서버는 해당 사용자가 소유했던 모든 라인 락을 자동으로 해제합니다.
 */
public class ClientSessionHandler implements Runnable {
    private final String REQUESTER="requester";
    private final String TARGETID="target_id";
    private final String PID="project_id";
    private final SSLSocket clientSocket;
    private final String DB_URL;
    private final String DB_USER;
    private final String DB_PASSWORD;
    private UserInfo userInfo=null;
    private SocketProtocol socketProtocol;
    private UserManager userManager;
    private final FileManager fileManager; // FileManager 추가
    private String activeFileIdentifier = null; // 현재 활성 파일 식별자
    private final java.io.ByteArrayOutputStream packetReassemblyBuffer = new java.io.ByteArrayOutputStream(); // 패킷 재조립 버퍼
    private boolean isReassembling = false; // 현재 패킷을 재조립하고 있는지 여부
    private int reassemblyUserField = -1;   // 재조립 중인 패킷의 UserField

    public ClientSessionHandler(SSLSocket clientSocket, String DB_URL, String DB_USER, String DB_PASSWORD, FileManager fileManager) {
        this.clientSocket = clientSocket;
        this.DB_URL = DB_URL;
        this.DB_USER = DB_USER;
        this.DB_PASSWORD = DB_PASSWORD;
        this.fileManager = fileManager; // FileManager 초기화
    }

    @Override
    public void run() {
        this.socketProtocol = new SocketProtocol();
        OutputStream out = null;
        try (Connection dbConnection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             InputStream in = clientSocket.getInputStream()) {
            
            out = clientSocket.getOutputStream();
            this.userManager = new UserManager(dbConnection);
            System.out.println("클라이언트와 SSL 핸드셰이크 완료: " + clientSocket.getInetAddress());

            byte[] headerBuffer = new byte[4];
            while (in.read(headerBuffer) != -1) {
                ByteBuffer wrappedHeader = ByteBuffer.wrap(headerBuffer);
                wrappedHeader.order(ByteOrder.LITTLE_ENDIAN);

                long packetLength = (wrappedHeader.getInt(0) >> 4) & 0x1FFF;
                byte fragFlag = (byte) ((wrappedHeader.getInt(0) >> 17) & 0x01);

                if (packetLength < 8) {
                    System.err.println("유효하지 않은 길이의 패킷 수신: " + packetLength);
                    break;
                }

                byte[] fullPacketBytes = new byte[(int) packetLength];
                System.arraycopy(headerBuffer, 0, fullPacketBytes, 0, 4);

                int remainingBytesToRead = (int) packetLength - 4;
                int totalBytesRead = 0;
                while (totalBytesRead < remainingBytesToRead) {
                    int bytesRead = in.read(fullPacketBytes, 4 + totalBytesRead, remainingBytesToRead - totalBytesRead);
                    if (bytesRead == -1) {
                        System.err.println("패킷 본문을 읽는 중 클라이언트가 예기치 않게 연결을 끊었습니다.");
                        break;
                    }
                    totalBytesRead += bytesRead;
                }

                if (totalBytesRead < remainingBytesToRead) {
                    System.err.println("패킷 본문을 완전히 읽지 못했습니다. 예상: " + remainingBytesToRead + ", 실제: " + totalBytesRead);
                    break;
                }

                try {
                    ParsedPacket parsedPacket = socketProtocol.parsePacket(fullPacketBytes);
                    
                    if (fragFlag == ProtocolConstants.FRAGED) {
                        if (!isReassembling) {
                            // 새로운 분할 전송 시작
                            isReassembling = true;
                            reassemblyUserField = parsedPacket.getUserField();
                            packetReassemblyBuffer.reset();
                            packetReassemblyBuffer.write(parsedPacket.getPayload());
                        } else {
                            // 진행 중인 분할 전송에 추가
                            if (parsedPacket.getUserField() != reassemblyUserField) {
                                // 오류: 다른 종류의 분할 패킷이 중간에 끼어듦
                                System.err.println("패킷 재조립 오류: 예상 UserField " + reassemblyUserField + ", 실제 " + parsedPacket.getUserField());
                                isReassembling = false;
                                packetReassemblyBuffer.reset();
                            } else {
                                packetReassemblyBuffer.write(parsedPacket.getPayload());
                            }
                        }
                    } else { // UNFRAGED
                        ParsedPacket finalPacket;
                        if (isReassembling) {
                            // 분할 전송의 마지막 패킷
                            if (parsedPacket.getUserField() != reassemblyUserField) {
                                System.err.println("패킷 재조립 오류: 예상 UserField " + reassemblyUserField + ", 실제 " + parsedPacket.getUserField());
                                isReassembling = false;
                                packetReassemblyBuffer.reset();
                                continue; // 이 패킷은 무시
                            }
                            packetReassemblyBuffer.write(parsedPacket.getPayload());
                            byte[] completePayload = packetReassemblyBuffer.toByteArray();
                            
                            finalPacket = new ParsedPacket(
                                parsedPacket.getProtocolVersion(),
                                completePayload.length + 8,
                                ProtocolConstants.UNFRAGED,
                                parsedPacket.getPayloadType(),
                                reassemblyUserField,
                                completePayload
                            );

                            // 재조립 상태 초기화
                            isReassembling = false;
                            packetReassemblyBuffer.reset();
                        } else {
                            // 단일 패킷
                            finalPacket = parsedPacket;
                        }
                        
                        // 최종 패킷 처리
                        switch (finalPacket.getUserField()) {
                            case ProtocolConstants.UF_REGISTER_REQUEST:
                                handleRegisterRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_LOGIN_REQUEST:
                                handleLoginRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_PROJECT_LIST_REQUEST:
                                handleProjectRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_CREATE_PROJECT_REQUEST:
                                handleCreateProjectRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_DELETE_PROJECT_REQUEST:
                                handleDeleteProjectRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_ADD_SHARE_REQUEST:
                                handleAddShareRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_DELETE_SHARE_REQUEST:
                                handleDeleteShareRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_SHARED_LIST_REQUEST:
                                handleSharedListRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_FILETREE_LIST_REQUEST:
                                handleFileListRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_ADD_FOLDER_REQUEST:
                                handleMkdir(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_DELETE_FOLDER_REQUEST:
                                handleDelDir(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_ADD_FILE_REQUEST:
                                handleMkFileRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_DELETE_FILE_REQUEST:
                                handleDelFileRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_FILE_CONTENT_REQUEST:
                                handleFileContentRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_LINE_LOCK_REQUEST:
                                handleLineLockRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_LINE_UNLOCK_REQUEST:
                                handleLineUnlockRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_FILE_EDIT_OPERATION:
                                handleFileEditOperation(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_CURSOR_MOVE:
                                handleCursorMoveRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_FILE_CLOSE_REQUEST:
                                handleFileCloseRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_GET_PROJECT_FILE_REQUEST:
                                handleGetProjectFileRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_CHANG_FILE_NAME_REQUEST:
                                handleChangeFileNameRequest(finalPacket, out);
                                break;
                            case ProtocolConstants.UF_CHANG_FILE_LOC_REQUEST:
                                handleChangeFileLocationRequest(finalPacket, out);
                                break;
                            default:
                                handleUnknownRequest(finalPacket, out, clientSocket.getInetAddress().toString());
                                break;
                        }
                    }
        
                } catch (PacketException e) {
                    System.err.println("클라이언트 " + clientSocket.getInetAddress() + "로부터의 패킷 프로토콜 오류: " + e.getMessage());
                    e.printStackTrace(); // 스택 트레이스 출력 추가
                    // 오류 응답 전송
                    byte[] errorResponse = socketProtocol.toBytes(("서버 패킷 처리 오류: " + e.getMessage()).getBytes(), ProtocolConstants.UNFRAGED, ProtocolConstants.PTYPE_STRING, ProtocolConstants.UF_SERVER_ERROR);
                    out.write(errorResponse);
                    out.flush();
                } catch (Exception e) {
                    System.err.println("클라이언트 " + clientSocket.getInetAddress() + "로부터의 패킷 처리 오류: " + e.getMessage());
                    e.printStackTrace();
                    // 오류 응답 전송
                    byte[] errorResponse = socketProtocol.toBytes("서버 내부 오류".getBytes(), ProtocolConstants.UNFRAGED, ProtocolConstants.PTYPE_STRING, ProtocolConstants.UF_SERVER_ERROR);
                    out.write(errorResponse);
                    out.flush();
                }
            }
        } catch (SQLException e) {
            System.err.println("클라이언트 " + clientSocket.getInetAddress() + " 데이터베이스 연결 오류: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("클라이언트 " + clientSocket.getInetAddress() + " 처리 오류: " + e.getMessage());
        } finally {
            if (out != null) {
                // userInfo가 null이 아닌 경우에만 userId를 전달하여 라인 락 해제
                fileManager.removeClientFromAllSessions(out, userInfo != null ? userInfo.getId() : null);
            }
            try {
                if (clientSocket != null) {
                    clientSocket.close();
                    System.out.println("클라이언트 연결 해제됨: " + clientSocket.getInetAddress());
                }
            } catch (IOException e) {
                System.err.println("클라이언트 소켓 닫기 오류: " + e.getMessage());
            }
        }
    }

    private void handleGetProjectFileRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        JSONArray filePaths = payload.getJSONArray("path");

        if (checkAllVerfication(requester, pID, userInfo, null, out)) {
            // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }
            Path rootPath = requester.equals(owner) ?
                Paths.get("D:/liveCode",userInfo.getId(),pID):
                Paths.get("D:/liveCode",owner,pID);
            
            JSONArray filesData = new JSONArray();

            for (int i = 0; i < filePaths.length(); i++) {
                String relativePathStr = filePaths.getString(i);
                // 프로젝트 루트 기준 상대 경로만 사용하도록 선행 슬래시 제거
                if (relativePathStr.startsWith("/") || relativePathStr.startsWith("\\")) {
                    relativePathStr = relativePathStr.substring(1);
                }
                JSONObject fileData = new JSONObject();
                fileData.put("project_id", pID);
                fileData.put("owner", owner);
                fileData.put("path", relativePathStr);

                try {
                    String fileIdentifier = fileManager.createFileIdentifier(owner, pID, relativePathStr);
                    FileSession session = fileManager.getSession(fileIdentifier);

                    byte[] fileBytes=null;
                    if (session != null) {
                        fileBytes=(session.getContent()).getBytes();
                    }
                    
                    Path fullPath = Paths.get(rootPath.toString(), relativePathStr);
                    
                    // 파일 내용을 raw byte로 읽은 후, Base64로 인코딩하여 JSON에 안전하게 삽입
                    fileBytes = fileBytes==null?Files.readAllBytes(fullPath):fileBytes;
                    String base64Content = Base64.getEncoder().encodeToString(fileBytes);
                    fileData.put("content", base64Content);
                    
                } catch (Exception e) {
                    System.err.println("파일을 찾을 수 없거나 읽을 수 없습니다: " + relativePathStr + " in project " + pID);
                    e.printStackTrace();
                    fileData.put("error", "파일을 찾을 수 없거나 읽을 수 없습니다.");
                    fileData.put("content", "");
                }
                filesData.put(fileData);
            }

            // Send the JSON array of file data back to the client
            // applyNormalization을 true로 (기본값) 사용하도록 수정
            sendData(out, filesData.toString().getBytes(StandardCharsets.UTF_8), ProtocolConstants.PTYPE_JSONARR, ProtocolConstants.UF_GET_PROJECT_FILE_RESPONSE);
        }
    }

    private void handleCursorMoveRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String filePath = payload.getString("path");
        int cursorPosition = payload.getInt("cursorPosition");

        FileSession session = fileManager.getOrCreateSession(owner, pID, filePath, out, userInfo.getNickname() + "#" + userInfo.getTag());

        if (session.isUserAuthorized(userInfo.getId())) {
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }
            
            String userDisplayIdentifier = userInfo.getNickname() + "#" + userInfo.getTag();
            session.broadcastCursorMove(userDisplayIdentifier, cursorPosition, out);
        } else {
            // 캐시에 권한이 없는 경우 (비정상 접근)
            sendStandardClientError(out, ProtocolConstants.ERROR_CODE_NOT_AUTHORIZED, "User not authorized for this file session.", null);
            System.err.println("비인가 접근 시도: " + requester + ", 파일: " + filePath);
        }
    }

    private void handleFileEditOperation(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        String jsonString = new String(parsedPacket.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
        JSONObject payload = new JSONObject(jsonString);
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String filePath = payload.getString("path");
        String operationType = payload.getString("type");
        int position = payload.getInt("position");
        long version=payload.getInt("version");
        String uniqId= payload.getString("uniqId");

        FileSession session = fileManager.getOrCreateSession(owner, pID, filePath, out, userInfo.getNickname() + "#" + userInfo.getTag());

        if (session.isUserAuthorized(userInfo.getId())) {
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }

            Operation operation = null;
            int currentLine = session.getLineFromPosition(position);

            if (session.isLineLocked(currentLine) && !userInfo.getId().equals(session.getLineLockOwner(currentLine))) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("errorCode", ProtocolConstants.ERROR_CODE_LINE_LOCKED);
                errorResponse.put("error", "Line locked by another user.");
                errorResponse.put("lockOwner", session.getLineLockOwner(currentLine));
                errorResponse.put("lineNumber", currentLine);
                errorResponse.put("requestedPosition", position);
                errorResponse.put("operationType", operationType);
                writeByUnfragedJson(errorResponse, ProtocolConstants.UF_CLIENT_ERROR, out);
                return;
            }
            int cursorPosition = payload.optInt("cursorPosition", -1);
            if ("INSERT".equals(operationType)) {
                String text = payload.getString("text");
                operation = new Operation(Operation.Type.INSERT, position, text, cursorPosition,version, uniqId);
            } else if ("DELETE".equals(operationType)) {
                int length = payload.getInt("length");
                operation = new Operation(Operation.Type.DELETE, position, length, cursorPosition,version, uniqId);
            }

            if (operation != null) {
                try {
                    if(session.applyOperation(operation)){
                        String userDisplayIdentifier = userInfo.getNickname() + "#" + userInfo.getTag();
                        session.broadcastOperation(operation, userDisplayIdentifier, out);
                    }else{
                        System.err.println("[DEBUG] VERSION_MISMATCH. Client version: " + version + ", Server version: " + session.getVersion() + ". Sending history...");
                        List<Operation> history=session.getHistoy(version);
                        if(history !=null){
                            JSONArray response = new JSONArray();
                            for (Operation op : history) {
                                JSONObject opJson=new JSONObject();
                                opJson.put("project_id", pID);
                                opJson.put("owner", owner);
                                opJson.put("path", filePath);
                                opJson.put("type", op.getType().toString());
                                opJson.put("position", op.getPosition());
                                opJson.put("version",op.getVersion());

                                 if (op.getType() == Operation.Type.INSERT) {
                                    opJson.put("text", op.getText());
                                } else if (op.getType() == Operation.Type.DELETE) {
                                    opJson.put("length", op.getLength());
                                }
                                
                                // 커서 위치가 있다면 추가
                                if (op.getCursorPosition() != -1) {
                                    opJson.put("cursorPosition", op.getCursorPosition());
                                }
                                response.put(opJson);
                            }
                            sendData(out, response.toString().getBytes(), ProtocolConstants.PTYPE_JSONARR, ProtocolConstants.UF_HISTORY);
                        }else{
                            JSONObject errorResponse = new JSONObject();
                            errorResponse.put("errorCode", ProtocolConstants.ERROR_CODE_SYNC_ERROR);
                            errorResponse.put("error", "Content synchronization error.");
                            writeByUnfragedJson(errorResponse, ProtocolConstants.UF_CLIENT_ERROR, out);
                        }
                    }
                } catch (StringIndexOutOfBoundsException e) {
                    JSONObject errorResponse = new JSONObject();
                    errorResponse.put("errorCode", ProtocolConstants.ERROR_CODE_SYNC_ERROR);
                    errorResponse.put("error", "Content synchronization error.");
                    errorResponse.put("serverContentLength", session.getContent().length());
                    errorResponse.put("lineNumber", currentLine);
                    errorResponse.put("requestedPosition", position);
                    errorResponse.put("operationType", operationType);
                    errorResponse.put("lockOwner", "null");
                    
                    writeByUnfragedJson(errorResponse, ProtocolConstants.UF_CLIENT_ERROR, out);
                    System.err.println("동기화 오류 감지 (IndexOutOfBounds): 클라이언트 " + requester +
                                       ", 라인: " + currentLine + ", 요청 위치: " + position + ", 서버 파일 길이: " + session.getContent().length());
                }
            } else {
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_INVALID_OPERATION, "Invalid operation type or missing parameters.", null);
            }
        } else {
            sendStandardClientError(out, ProtocolConstants.ERROR_CODE_NOT_AUTHORIZED, "User not authorized for this file session.", null);
            System.err.println("비인가 접근 시도: " + requester + ", 파일: " + filePath);
        }
    }

    private void handleLineUnlockRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String filePath = payload.getString("path");
        int lineNumber = payload.getInt("lineNumber");

        JSONObject responsePayload = new JSONObject();
        responsePayload.put(PID, pID);
        responsePayload.put("path", filePath);
        responsePayload.put("lineNumber", lineNumber);

        if (checkAllVerfication(requester, pID, userInfo, null, out)) {
            // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }
        
            FileSession session = fileManager.getOrCreateSession(owner, pID, filePath, out, userInfo.getNickname() + "#" + userInfo.getTag());
            // 락을 요청한 사용자가 현재 락 소유자인 경우에만 해제 시도
            if (userInfo.getId().equals(session.getLineLockOwner(lineNumber))) {
                if (session.unlockLine(lineNumber, userInfo.getId())) {
                    responsePayload.put("success", true);
                    // 다른 클라이언트에게 락 해제 사실 브로드캐스트
                    String unlockerDisplay = userInfo.getNickname() + "#" + userInfo.getTag();
                    session.broadcastLineUnlock(lineNumber, unlockerDisplay, out);

                } else {
                    responsePayload.put("success", false);
                }
            } else {
                responsePayload.put("success", false);
                String lockOwnerId = session.getLineLockOwner(lineNumber);
                String lockOwnerDisplay = lockOwnerId; // 기본값은 ID
                if (lockOwnerId != null) {
                    UserInfo ownerInfo = userManager.getUserInfoById(lockOwnerId);
                    if (ownerInfo != null) {
                        lockOwnerDisplay = ownerInfo.getNickname() + "#" + ownerInfo.getTag();
                    }
                }
                responsePayload.put("lockOwner", lockOwnerDisplay);
                responsePayload.put("message", "You do not own the lock for this line.");
                System.err.println("User " + userInfo.getId() + " tried to unlock line " + lineNumber + " in " + filePath + " but does not own the lock.");
            }
            writeByUnfragedJson(responsePayload, ProtocolConstants.UF_LINE_UNLOCK_RESPONSE, out);
        }
    }

    private void handleLineLockRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String filePath = payload.getString("path");
        int lineNumber = payload.getInt("lineNumber");

        JSONObject responsePayload = new JSONObject();
        responsePayload.put(PID, pID);
        responsePayload.put("path", filePath);
        responsePayload.put("lineNumber", lineNumber);

        if (checkAllVerfication(requester, pID, userInfo, null, out)) {
            // // 1. 요청된 파일 식별자 생성
            // String requestedFileIdentifier = String.join("/", owner, pID, filePath);

            // // 2. 활성 파일이 변경되었는지 확인
            // if (activeFileIdentifier != null && !activeFileIdentifier.equals(requestedFileIdentifier)) {
            //     // 이전 파일에서 클라이언트 정리 (편집자 목록 제거 및 락 해제)
            //     fileManager.cleanupClientFromFileSession(activeFileIdentifier, out, userInfo.getId());
            // }

            // // 3. 현재 활성 파일 식별자 업데이트
            // this.activeFileIdentifier = requestedFileIdentifier;

            // 4. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }

            FileSession session = fileManager.getOrCreateSession(owner, pID, filePath, out, userInfo.getNickname() + "#" + userInfo.getTag());
            
            // 권한 검사를 통과했으므로 캐시에 사용자 추가
            session.addUserToAuthorized(userInfo.getId());

            String userDisplayIdentifier = userInfo.getNickname() + "#" + userInfo.getTag();

            // 5. 이 파일에서 사용자의 기존 락을 모두 해제하고, 해제된 라인들을 브로드캐스트
            List<Integer> previouslyLockedLines = session.removeAndGetLocksByUserId(userInfo.getId());
            for (Integer unlockedLine : previouslyLockedLines) {
                if (unlockedLine != lineNumber) { // 새로 잠글 라인과 다른 경우에만 해제 브로드캐스트
                    session.broadcastLineUnlock(unlockedLine, userDisplayIdentifier, out);
                }
            }
            
            // 6. 새로운 라인에 락 시도
            if (session.lockLine(lineNumber, userInfo.getId())) {
                responsePayload.put("success", true);
                
                // 다른 클라이언트에게 락 사실 브로드캐스트
                session.broadcastLineLock(lineNumber, userDisplayIdentifier, out);

            } else {
                // 다른 사람이 이미 락을 소유한 경우
                String lockOwnerId = session.getLineLockOwner(lineNumber);
                String lockOwnerDisplay = lockOwnerId; // 기본값은 ID
                if (lockOwnerId != null) {
                    UserInfo ownerInfo = userManager.getUserInfoById(lockOwnerId);
                    if (ownerInfo != null) {
                        lockOwnerDisplay = ownerInfo.getNickname() + "#" + ownerInfo.getTag();
                    }
                }
                responsePayload.put("success", false);
                responsePayload.put("lockOwner", lockOwnerDisplay);
            }
            writeByUnfragedJson(responsePayload, ProtocolConstants.UF_LINE_LOCK_RESPONSE, out);
        }
    }

    private void handleFileContentRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String filePath = payload.getString("path");
        if (checkAllVerfication(requester, pID, userInfo, null, out)) {
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }

            FileSession session = fileManager.getOrCreateSession(owner, pID, filePath, out, userInfo.getNickname() + "#" + userInfo.getTag());

            // 권한 검사를 통과했으므로 캐시에 사용자 추가
            session.addUserToAuthorized(userInfo.getId());

            String content = session.getContent();
            String hash = "";

            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(content.getBytes());
                Formatter formatter = new Formatter();
                for (byte b : digest) {
                    formatter.format("%02x", b);
                }
                hash = formatter.toString();
                formatter.close();
            } catch (NoSuchAlgorithmException e) {
                System.err.println("SHA-256 알고리즘을 찾을 수 없습니다.");
                writeByUnfragedJson(
                    new JSONObject().put("error", "Failed to generate file hash."),
                    ProtocolConstants.UF_SERVER_ERROR, out);
                return;
            }

            // 전체 응답 페이로드를 JSON 객체로 생성 (해시를 먼저 추가)
            JSONObject fullResponsePayload = new JSONObject();
            fullResponsePayload.put("hash", hash);
            fullResponsePayload.put("version", session.getVersion()); // 현재 버전 정보 추가
            fullResponsePayload.put(PID, pID);
            fullResponsePayload.put("path", filePath);
            fullResponsePayload.put("content", content);

            // JSON 문자열을 바이트 배열로 변환
            byte[] fullPayloadBytes = fullResponsePayload.toString().getBytes();

            // sendData 헬퍼 메서드를 사용하여 자동으로 분할 전송 처리
            sendData(out, fullPayloadBytes, ProtocolConstants.PTYPE_JSON, ProtocolConstants.UF_FILE_CONTENT_RESPONSE,true);
        }
    }

    private JSONObject folderToJson(Path folderPath) throws IOException {
        JSONObject json = new JSONObject();
        json.put("name", folderPath.getFileName().toString());
        json.put("type", Files.isDirectory(folderPath) ? "folder" : "file");
        if (Files.isDirectory(folderPath)) {
            JSONArray children = new JSONArray();
            try (Stream<Path> paths = Files.list(folderPath)) {
                paths.forEach(p -> {
                    try {
                        children.put(folderToJson(p));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }json.put("children", children);
        }return json;
    }

    private void handleFileListRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String pID=new JSONObject(new String(parsedPacket.getPayload())).getString(PID);
        String requester=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        String owner=new JSONObject(new String(parsedPacket.getPayload())).getString("owner");
        if(checkAllVerfication(requester, pID, userInfo, null, out)){
            // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }

            Path rootPath = requester.equals(owner) ?
                Paths.get("D:/liveCode",userInfo.getId(),pID):
                Paths.get("D:/liveCode",owner,pID);
            JSONArray payload=new JSONArray();
            payload.put(new JSONObject().put(PID, pID));
            payload.put(folderToJson(rootPath));
            sendData(out, payload.toString().getBytes(), 
                ProtocolConstants.PTYPE_JSONARR,
                ProtocolConstants.UF_FILETREE_LIST_RESPONSE);
        }
    }

    private void handleSharedListRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        String pID=new JSONObject(new String(parsedPacket.getPayload())).getString(PID);
        if(userVerification(requester, out)&&userManager.checkVerification(pID, userInfo, null)){
            JSONObject payload=userManager.getSharedList(pID,userInfo);
            out.write(socketProtocol.toBytes(
                payload.toString().getBytes(),
                ProtocolConstants.UNFRAGED,
                ProtocolConstants.PTYPE_JSON,
                ProtocolConstants.UF_SHARED_LIST_RESPONSE));
            out.flush();
        }
    }

    private void handleDeleteShareRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        String pID=new JSONObject(new String(parsedPacket.getPayload())).getString(PID);
        String targetName=new JSONObject(new String(parsedPacket.getPayload())).getString("target_name");
        String targetTag=new JSONObject(new String(parsedPacket.getPayload())).getString("target_tag");
        if(userVerification(requester, out)&&userManager.checkVerification(pID, userInfo, null)){
            if(userManager.deleteShare(targetName, targetTag, pID)){
                out.write(socketProtocol.toBytes(
                    (new JSONObject().put("project_id", pID).put("result", true)).toString().getBytes(),
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_BOOLEAN,
                    ProtocolConstants.UF_DELETE_SHARE_RESPONSE));
                out.flush();
            }else{
                out.write(socketProtocol.toBytes(
                    (new JSONObject().put("project_id", pID).put("result", false)).toString().getBytes(),
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_BOOLEAN,
                    ProtocolConstants.UF_DELETE_SHARE_RESPONSE));
                out.flush();
            }
        }
    }

    private void handleAddShareRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        String pID=new JSONObject(new String(parsedPacket.getPayload())).getString(PID);
        String targetName=new JSONObject(new String(parsedPacket.getPayload())).getString("target_name");
        String targetTag=new JSONObject(new String(parsedPacket.getPayload())).getString("target_tag");
        if(userVerification(requester, out)&&userManager.checkVerification(pID, userInfo, null)){
            // 자기 자신에게 공유하는 것을 막는 로직을 먼저 처리
            if (userInfo.getNickname().equals(targetName) && userInfo.getTag().equals(targetTag)) {
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("project_id", pID);
                errorResponse.put("result", false);
                errorResponse.put("message", "Cannot share a project with yourself.");
                writeByUnfragedJson(errorResponse, ProtocolConstants.UF_ADD_SHARE_RESPONSE, out);
                return;
            }

            boolean shareSuccess = userManager.addShare(targetName, targetTag, pID);
            if(shareSuccess){
                out.write(socketProtocol.toBytes(
                    (new JSONObject().put("project_id", pID).put("result", true)).toString().getBytes(),
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_JSON,
                    ProtocolConstants.UF_ADD_SHARE_RESPONSE));
                out.flush();
            }else{
                // userManager.addShare가 false를 반환한 경우 (예: 대상 사용자 없음, 이미 공유됨 등)
                JSONObject errorResponse = new JSONObject();
                errorResponse.put("project_id", pID);
                errorResponse.put("result", false);
                errorResponse.put("message", "Failed to add share. Target user not found or project already shared.");
                writeByUnfragedJson(errorResponse, ProtocolConstants.UF_ADD_SHARE_RESPONSE, out);
                return;
            }
        }
    }
    private boolean checkAllVerfication(String requester, String pID, UserInfo user ,String id, OutputStream out)throws IOException, SQLException{
        if(userVerification(requester, out)&&(userManager.checkShareVerification(pID, user, null)||userManager.checkVerification(pID, user, null))) return true;
        return false;
    }

    private void handleDelDir(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=getStringFromJson(parsedPacket,REQUESTER);
        String pID=getStringFromJson(parsedPacket,PID);
        String targetPath=getStringFromJson(parsedPacket,"path");
        String owner=getStringFromJson(parsedPacket, "owner");
        if(!checkAllVerfication(requester, pID, userInfo,null, out)) return;
        // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
        if (!userManager.verifyProjectOwner(pID, owner)) {
            JSONObject context = new JSONObject();
            context.put("projectId", pID);
            context.put("claimedOwner", owner);
            sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
            System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
            return;
        }

        Path path=Paths.get("D:","liveCode",owner,pID,targetPath);
        if (Files.exists(path)) {
            try(Stream<Path> paths=Files.walk(path)){
                paths.sorted((p1, p2) -> p2.compareTo(p1)) // 하위부터 먼저 삭제
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                out.write(socketProtocol.toBytes(
                    new JSONObject().put(PID,pID).put("result", true).toString().getBytes(),
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_JSON,
                    ProtocolConstants.UF_DELETE_FILE_RESPONSE));
            }catch(RuntimeException e){
                out.write(socketProtocol.toBytes(
                    new JSONObject().put(PID,pID).put("result", false).toString().getBytes(),
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_JSON,
                    ProtocolConstants.UF_DELETE_FILE_RESPONSE));
            }
        }
    }
    private void writeByUnfragedJson(JSONObject json, int arg, OutputStream out) throws IOException{
        sendData(out, json.toString().getBytes(), ProtocolConstants.PTYPE_JSON, arg);
    }
    private String getStringFromJson(ParsedPacket parsedPacket, String value){
        return new JSONObject(new String(parsedPacket.getPayload())).getString(value);
    }
    private void handleMkdir(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=getStringFromJson(parsedPacket,REQUESTER);
        String pID=getStringFromJson(parsedPacket,PID);
        String targetPath=getStringFromJson(parsedPacket,"path");
        String owner=getStringFromJson(parsedPacket, "owner");
        if(!checkAllVerfication(requester, pID, userInfo,null, out)) return;
        // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
        if (!userManager.verifyProjectOwner(pID, owner)) {
            JSONObject context = new JSONObject();
            context.put("projectId", pID);
            context.put("claimedOwner", owner);
            sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
            System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
            return;
        }

        Path path=Paths.get("D:","liveCode",owner,pID,targetPath);
        try{
            Files.createDirectories(path);
            writeByUnfragedJson(new JSONObject().put(PID,pID).put("result", true),
                ProtocolConstants.UF_ADD_FOLDER_RESPONSE,out);
        }
        catch(IOException e){
            writeByUnfragedJson(new JSONObject().put(PID,pID).put("result", false),
                ProtocolConstants.UF_ADD_FOLDER_RESPONSE,out);
        }
    }

    public void handleMkFileRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=getStringFromJson(parsedPacket,REQUESTER);
        String pID=getStringFromJson(parsedPacket,PID);
        String targetPath=getStringFromJson(parsedPacket,"path");
        String owner=getStringFromJson(parsedPacket, "owner");
        if(!checkAllVerfication(requester, pID, userInfo,null, out)) return;
        // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
        if (!userManager.verifyProjectOwner(pID, owner)) {
            JSONObject context = new JSONObject();
            context.put("projectId", pID);
            context.put("claimedOwner", owner);
            sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
            System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
            return;
        }

        Path path=Paths.get("D:","liveCode",owner,pID,targetPath);
        if(!Files.exists(path)) {
            Files.createFile(path);
            writeByUnfragedJson(
                new JSONObject().put(PID,pID).put("result", true),
                ProtocolConstants.UF_ADD_FILE_RESPONSE,
                out);
        } else {
            writeByUnfragedJson(
                new JSONObject().put(PID,pID).put("result", false),
                ProtocolConstants.UF_ADD_FILE_RESPONSE,
                out);
        }
    }

    private void handleDelFileRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String requester=getStringFromJson(parsedPacket,REQUESTER);
        String pID=getStringFromJson(parsedPacket,PID);
        String targetPath=getStringFromJson(parsedPacket,"path");
        String owner=getStringFromJson(parsedPacket, "owner");
        if(!checkAllVerfication(requester, pID, userInfo,null, out)) return;
        // 2. 추가 보안 검증: 클라이언트가 보낸 owner가 실제 프로젝트의 소유자인지 확인
        if (!userManager.verifyProjectOwner(pID, owner)) {
            JSONObject context = new JSONObject();
            context.put("projectId", pID);
            context.put("claimedOwner", owner);
            sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
            System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
            return;
        }

        Path path=Paths.get("D:","liveCode",owner,pID,targetPath);
        if(Files.deleteIfExists(path)){
            writeByUnfragedJson(
                new JSONObject().put(PID,pID).put("result", true),
                ProtocolConstants.UF_DELETE_FILE_RESPONSE,
                out);
        }else{
            writeByUnfragedJson(
                new JSONObject().put(PID,pID).put("result", false),
                ProtocolConstants.UF_DELETE_FILE_RESPONSE,
                out);
        }
    }

    private void handleDeleteProjectRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String pID=new JSONObject(new String(parsedPacket.getPayload())).getString(PID);
        String userID=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        if(userVerification(userID,out)){
            if(userManager.deleteProject(pID, userID)){ //todo
                Path folderPath = Paths.get("D:","liveCode",userInfo.getId(),pID);
                if (Files.exists(folderPath)) {
                    try(Stream<Path> paths=Files.walk(folderPath)){
                        paths.sorted((p1, p2) -> p2.compareTo(p1)) // 하위부터 먼저 삭제
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                    }catch(RuntimeException e){
                        out.write(socketProtocol.toBytes(
                            new byte[]{0},
                            ProtocolConstants.UNFRAGED,
                            ProtocolConstants.PTYPE_BOOLEAN,
                            ProtocolConstants.UF_DELETE_PROJECT_RSPONSE));
                        out.flush();
                    }
                }
                out.write(socketProtocol.toBytes(
                    new byte[]{1},
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_BOOLEAN,
                    ProtocolConstants.UF_DELETE_PROJECT_RSPONSE));
                out.flush();
            }else{
                out.write(socketProtocol.toBytes(
                    new byte[]{0},
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_BOOLEAN,
                    ProtocolConstants.UF_DELETE_PROJECT_RSPONSE));
                out.flush();
            }
        }
    }

    private void handleCreateProjectRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException{
        String id=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        String name=new JSONObject(new String(parsedPacket.getPayload())).getString("name");
        if(userVerification(id,out)){
            if(userManager.createNewProject(name, id)){
                Path folderPath = Paths.get("D:","liveCode",userInfo.getId(),userManager.getProjectINFO(userInfo.getId(), name,"").getString("project_id"));
                if (!Files.exists(folderPath)) Files.createDirectories(folderPath);
                out.write(socketProtocol.toBytes(
                    new byte[]{1},
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_BOOLEAN,
                    ProtocolConstants.UF_CREATE_PROJECT_RSPONSE));
                out.flush();
            }else{
                out.write(socketProtocol.toBytes(
                    new byte[]{0},
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_BOOLEAN,
                    ProtocolConstants.UF_CREATE_PROJECT_RSPONSE));
                out.flush();
            }
        }
    }

    private void handleRegisterRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        try {
            JSONObject registerPayload = new JSONObject(new String(parsedPacket.getPayload()));
            String id = registerPayload.getString("id");
            String nickname = registerPayload.getString("nickname");
            String tag = registerPayload.getString("tag");
            String password = registerPayload.getString("password");

            String signupResult = this.userManager.signup(id, nickname, tag, password);
            byte[] responsePacket;
            int responseCode;

            if ("SUCCESS".equals(signupResult)){
                responseCode = 0; // 성공
                Path folderPath = Paths.get("D:/liveCode/"+id);
                if (!Files.exists(folderPath)) Files.createDirectories(folderPath);
            }else if ("USERNAME_TAG_EXISTS".equals(signupResult)) responseCode = 1; // 닉네임-태그 조합 중복
            else if ("ID_EXISTS".equals(signupResult)) responseCode = 2; // ID 중복
            else responseCode = 3; // 기타 실패
            responsePacket = this.socketProtocol.toBytes(ByteBuffer.allocate(4).putInt(responseCode).array(), ProtocolConstants.UNFRAGED, ProtocolConstants.PTYPE_INT, ProtocolConstants.UF_REGISTER_RESPONSE);
            out.write(responsePacket);
            out.flush();
        } catch (Exception e) {
            System.err.println("등록 요청 처리 중 오류: " + e.getMessage());
            byte[] errorResponse = this.socketProtocol.toBytes("serverError".getBytes("UTF-8"), ProtocolConstants.UNFRAGED, ProtocolConstants.PTYPE_STRING, ProtocolConstants.UF_SERVER_ERROR);
            out.write(errorResponse);
            out.flush();
        }
    }

    private void handleLoginRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        try {
            JSONObject loginPayload = new JSONObject(new String(parsedPacket.getPayload()));
            String id = loginPayload.getString("id");
            String password = loginPayload.getString("password");

            boolean loginSuccess = this.userManager.login(id, password);
            byte[] responsePacket;

            if (loginSuccess){
                responsePacket = this.socketProtocol.toBytes(
                                                            ByteBuffer.allocate(4).putInt(1).array(),
                                                            ProtocolConstants.UNFRAGED,
                                                            ProtocolConstants.PTYPE_BOOLEAN,
                                                            ProtocolConstants.UF_LOGIN_RESPONSE);
                userInfo=userManager.getUserInfoById(id);
                out.write(responsePacket);
                out.write(this.socketProtocol.toBytes(
                    String.format("{\"id\":\"%s\", \"nickname\":\"%s\", \"tag\":\"%s\"}", userInfo.getId(), userInfo.getNickname(),userInfo.getTag()).getBytes(),
                    ProtocolConstants.UNFRAGED,
                    ProtocolConstants.PTYPE_JSON,
                    ProtocolConstants.UF_USER_INFO
                ));
                out.flush();
            }
            else{
                responsePacket = this.socketProtocol.toBytes(
                                                            ByteBuffer.allocate(4).putInt(0).array(),
                                                            ProtocolConstants.UNFRAGED,
                                                            ProtocolConstants.PTYPE_BOOLEAN,
                                                            ProtocolConstants.UF_LOGIN_RESPONSE);
                out.write(responsePacket);
                out.flush();
            }
            
            
        } catch (Exception e) {
            System.err.println("로그인 요청 처리 중 오류: " + e.getMessage());
            byte[] errorResponse = this.socketProtocol.toBytes("serverError".getBytes("UTF-8"), ProtocolConstants.UNFRAGED, ProtocolConstants.PTYPE_STRING, ProtocolConstants.UF_SERVER_ERROR);
            out.write(errorResponse);
            out.flush();
        }
    }

    private void handleProjectRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        String id=new JSONObject(new String(parsedPacket.getPayload())).getString(REQUESTER);
        if(userVerification(id, out)){
            JSONArray payload=userManager.getProjectList(id);
            out.write(socketProtocol.toBytes(
                payload.toString().getBytes(),
                ProtocolConstants.UNFRAGED,
                ProtocolConstants.PTYPE_JSONARR,
                ProtocolConstants.UF_PROJECT_LIST_RESPONSE));
            out.flush();
        }
    }
    
    private boolean userVerification(String id, OutputStream out) throws IOException{
        // 1. userInfo가 null인지 먼저 확인 (로그인 여부 확인)
        if (userInfo == null) {
            out.write(socketProtocol.toBytes(
                "User not logged in".getBytes(),
                ProtocolConstants.UNFRAGED,
                ProtocolConstants.PTYPE_STRING,
                ProtocolConstants.UF_CLIENT_ERROR));
            out.flush();
            return false;
        }
        // 2. 요청자와 로그인된 사용자가 일치하는지 확인
        if(!id.equals(userInfo.getId())){
            out.write(socketProtocol.toBytes(
                "USER Verification Fail".getBytes(),
                ProtocolConstants.UNFRAGED,
                ProtocolConstants.PTYPE_STRING,
                ProtocolConstants.UF_CLIENT_ERROR));
            out.flush();
            return false;
        }
        return true;
    }

    private void handleUnknownRequest(ParsedPacket parsedPacket, OutputStream out, String clientAddress) throws IOException {
        System.err.println("알 수 없는 UserField: " + parsedPacket.getUserField());
        byte[] unknownCommandResponse = this.socketProtocol.toBytes("Unknown command".getBytes("UTF-8"), ProtocolConstants.UNFRAGED, ProtocolConstants.PTYPE_STRING, ProtocolConstants.UF_CLIENT_ERROR);
        out.write(unknownCommandResponse);
        out.flush();
    }

    private void handleFileCloseRequest(ParsedPacket parsedPacket, OutputStream out) throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String filePath = payload.getString("path");
        if (checkAllVerfication(requester, pID, userInfo, null, out)) {

            if (!userManager.verifyProjectOwner(pID, owner)) {
                JSONObject context = new JSONObject();
                context.put("projectId", pID);
                context.put("claimedOwner", owner);
                sendStandardClientError(out, ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED, "Project owner verification failed.", context);
                System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
                return;
            }
            // FileManager를 통해 특정 파일 세션에서 클라이언트 제거
            fileManager.removeClientFromFileSession(owner, pID, filePath, out);
            // 클라이언트에게 성공 응답 전송
            JSONObject responsePayload = new JSONObject();
            responsePayload.put(PID, pID);
            responsePayload.put("path", filePath);
            responsePayload.put("success", true);
            writeByUnfragedJson(responsePayload, ProtocolConstants.UF_FILE_CLOSE_RESPONSE, out);
        }

    }

    /**
    * 표준 형식에 따라 클라이언트 오류를 전송하는 헬퍼 메서드.
    * @param out OutputStream
    * @param errorCode 보낼 정수 에러 코드
    * @param errorMessage 사람이 읽을 수 있는 에러 메시지
    * @param context 에러에 대한 추가 정보를 담은 JSONObject (선택 사항)
    * @throws IOException
    */

    private void sendStandardClientError(OutputStream out, int errorCode, String errorMessage, JSONObject context) throws IOException {
        JSONObject errorPayload = new JSONObject();
        errorPayload.put("errorCode", errorCode);
        errorPayload.put("errorMessage", errorMessage);

        if (context != null) errorPayload.put("context", context);
        else errorPayload.put("context",new JSONObject());
        writeByUnfragedJson(errorPayload, ProtocolConstants.UF_CLIENT_ERROR, out);
    }
    private void sendData(OutputStream out, byte[] payload, byte payloadType, int userField) throws IOException {
        // 기본적으로 텍스트 정규화를 수행함 (기존 동작 유지)
        sendData(out, payload, payloadType, userField, true);
    }

    /**
        * 페이로드 크기에 따라 자동으로 분할 전송을 처리하는 헬퍼 메서드
        * @param out OutputStream
        * @param payload 전송할 전체 바이트 배열
        * @param payloadType 페이로드 타입 (PTYPE_JSON 등)
        * @param userField 사용자 정의 커맨드 (UF_... 등)
        * @param applyNomilzation 페이로드 설정 true시 텍스트 모드 false시 바이너리 전송모드
        * @throws IOException
        */
    private void sendData(OutputStream out, byte[] payload, byte payloadType, int userField, boolean applyNormalization) throws IOException {
        final int maxChunkSize = 8183;

        byte[] processedPayload = payload;

        // [수정됨] 플래그가 true이고, 텍스트 기반 프로토콜일 때만 정규화 수행
        if (applyNormalization && 
        (payloadType == ProtocolConstants.PTYPE_JSON || 
            payloadType == ProtocolConstants.PTYPE_JSONARR || 
            payloadType == ProtocolConstants.PTYPE_STRING)) {
                
            String contentString = new String(payload, StandardCharsets.UTF_8);
            // 윈도우 스타일 줄바꿈을 리눅스 스타일로 통일 (OT 알고리즘용)
            String normalizedContent = contentString.replaceAll("\\r\\n|\\r", "\n");
            processedPayload = normalizedContent.getBytes(StandardCharsets.UTF_8);
        }

        // 이하는 기존 분할 전송 로직과 동일
        if (processedPayload.length <= maxChunkSize) {
            out.write(socketProtocol.toBytes(processedPayload, ProtocolConstants.UNFRAGED, payloadType, userField));
        } else {
            int offset = 0;
            while (offset < processedPayload.length) {
                int length = Math.min(maxChunkSize, processedPayload.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(processedPayload, offset, chunk, 0, length);

                offset += length;
                boolean isLast = (offset >= processedPayload.length);
                byte fragFlag = isLast ? ProtocolConstants.UNFRAGED : ProtocolConstants.FRAGED;
                out.write(socketProtocol.toBytes(chunk, fragFlag, payloadType, userField));
            }
        }
        out.flush();
    }

    private void handleChangeFileNameRequest(ParsedPacket parsedPacket, OutputStream out)
        throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String path = payload.getString("path");
        String newName = payload.getString("newName");
        boolean isDirectory = payload.optBoolean("isDirectory", false);

        if (!checkAllVerfication(requester, pID, userInfo, null, out)) {
            return;
        }

        if (!userManager.verifyProjectOwner(pID, owner)) {
            JSONObject context = new JSONObject();
            context.put("projectId", pID);
            context.put("claimedOwner", owner);
            sendStandardClientError(out,
                    ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED,
                    "Project owner verification failed.",
                    context);
            System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
            return;
        }

        Path oldRelative = Paths.get(path);
        Path parent = oldRelative.getParent();
        String newPath;
        if (parent == null) {
            newPath = newName;
        } else {
            newPath = parent.resolve(newName).toString();
        }

        handleMovePath(requester, pID, owner, path, newPath,
                isDirectory, ProtocolConstants.UF_CHANG_FILE_NAME_RESPONSE, out);
    }

    private void handleChangeFileLocationRequest(ParsedPacket parsedPacket, OutputStream out)
            throws IOException, SQLException {
        JSONObject payload = new JSONObject(new String(parsedPacket.getPayload()));
        String requester = payload.getString(REQUESTER);
        String pID = payload.getString(PID);
        String owner = payload.getString("owner");
        String path = payload.getString("path");
        String newParent = payload.getString("newParent");
        boolean isDirectory = payload.optBoolean("isDirectory", false);

        if (!checkAllVerfication(requester, pID, userInfo, null, out)) {
            return;
        }

        if (!userManager.verifyProjectOwner(pID, owner)) {
            JSONObject context = new JSONObject();
            context.put("projectId", pID);
            context.put("claimedOwner", owner);
            sendStandardClientError(out,
                    ProtocolConstants.ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED,
                    "Project owner verification failed.",
                    context);
            System.err.println("경로 조작 시도 감지: 요청자 " + requester + ", 프로젝트 " + pID + ", 주장된 소유자 " + owner);
            return;
        }

        Path fileRel = Paths.get(path);
        Path fileName = fileRel.getFileName();
        Path newParentPath;
        if (newParent == null || newParent.isEmpty() || ".".equals(newParent)) {
            newParentPath = Paths.get("");
        } else {
            newParentPath = Paths.get(newParent);
        }
        String newPath = newParentPath.resolve(fileName).toString();

        handleMovePath(requester, pID, owner, path, newPath,
                isDirectory, ProtocolConstants.UF_CHANG_FILE_LOC_RESPONSE, out);
    }

    /**
     * 파일/폴더 경로 이동 또는 이름 변경을 공통으로 처리하는 내부 메서드입니다.
     * - 프로젝트 루트 밖으로 나가는 경로를 차단하고,
     * - 대상 또는 하위 파일에 열린 세션이 있으면 거부하며,
     * - 경로 충돌(이미 존재하는 경우)을 에러로 처리합니다.
     */
    private void handleMovePath(String requester,
                                String projectId,
                                String owner,
                                String oldRelativePath,
                                String newRelativePath,
                                boolean isDirectory,
                                int responseUserField,
                                OutputStream out) throws IOException {
        Path projectRoot = Paths.get("D:", "liveCode", owner, projectId).normalize();
        Path oldAbs = projectRoot.resolve(oldRelativePath).normalize();
        Path newAbs = projectRoot.resolve(newRelativePath).normalize();

        JSONObject context = new JSONObject();
        context.put("projectId", projectId);
        context.put("owner", owner);
        context.put("oldPath", oldRelativePath);
        context.put("newPath", newRelativePath);

        // 1) 프로젝트 루트 밖으로 나가는 경로 차단
        if (!oldAbs.startsWith(projectRoot) || !newAbs.startsWith(projectRoot)) {
            sendStandardClientError(out,
                    ProtocolConstants.ERROR_CODE_PATH_TRAVERSAL_ATTEMPT,
                    "Path traversal attempt detected.",
                    context);
            System.err.println("경로 이탈 시도 감지: 요청자 " + requester + ", 프로젝트 " + projectId
                    + ", old=" + oldAbs + ", new=" + newAbs);
            return;
        }

        // 2) 소스 경로 존재 여부 / 타입 검사
        if (!Files.exists(oldAbs)) {
            JSONObject response = new JSONObject();
            response.put(PID, projectId);
            response.put("oldPath", oldRelativePath);
            response.put("newPath", newRelativePath);
            response.put("success", false);
            response.put("errorMessage", "Source path does not exist.");
            writeByUnfragedJson(response, responseUserField, out);
            return;
        }

        if (isDirectory && !Files.isDirectory(oldAbs)) {
            JSONObject response = new JSONObject();
            response.put(PID, projectId);
            response.put("oldPath", oldRelativePath);
            response.put("newPath", newRelativePath);
            response.put("success", false);
            response.put("errorMessage", "Requested directory but source is a file.");
            writeByUnfragedJson(response, responseUserField, out);
            return;
        }

        if (!isDirectory && Files.isDirectory(oldAbs)) {
            JSONObject response = new JSONObject();
            response.put(PID, projectId);
            response.put("oldPath", oldRelativePath);
            response.put("newPath", newRelativePath);
            response.put("success", false);
            response.put("errorMessage", "Requested file but source is a directory.");
            writeByUnfragedJson(response, responseUserField, out);
            return;
        }

        // 3) 폴더/파일 하위에 열린 세션이 있는지 검사
        List<String> blocked = fileManager.findActiveSessionsUnder(owner, projectId, oldRelativePath);
        if (!blocked.isEmpty()) {
            context.put("blockedFiles", new JSONArray(blocked));
            sendStandardClientError(out,
                    ProtocolConstants.ERROR_CODE_FILE_OR_FOLDER_IN_USE,
                    "File or folder is currently in use.",
                    context);
            return;
        }

        // 4) 목적지 경로 충돌 검사
        if (Files.exists(newAbs)) {
            context.put("conflictPath", newRelativePath);
            sendStandardClientError(out,
                    ProtocolConstants.ERROR_CODE_PATH_CONFLICT,
                    "Target path already exists.",
                    context);
            return;
        }

        // 5) 실제 이동
        try {
            Path parent = newAbs.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.move(oldAbs, newAbs);
        } catch (IOException e) {
            System.err.println("파일/폴더 이동 중 오류: " + e.getMessage());
            JSONObject response = new JSONObject();
            response.put(PID, projectId);
            response.put("oldPath", oldRelativePath);
            response.put("newPath", newRelativePath);
            response.put("success", false);
            response.put("errorMessage", "Failed to move: " + e.getMessage());
            writeByUnfragedJson(response, responseUserField, out);
            return;
        }

        // 6) 성공 응답
        JSONObject response = new JSONObject();
        response.put(PID, projectId);
        response.put("oldPath", oldRelativePath);
        response.put("newPath", newRelativePath);
        response.put("success", true);
        writeByUnfragedJson(response, responseUserField, out);
    }
}

    
