package com.example;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import socketprotocol.SocketProtocol;

/**
 * 서버에서 열린 모든 파일 세션을 관리하는 중앙 매니저 클래스입니다.
 * 각 파일은 고유한 경로 문자열로 식별되며, FileSession 객체를 통해 관리됩니다.
 */
public class FileManager {

    private final ConcurrentHashMap<String, FileSession> activeSessions = new ConcurrentHashMap<>();
    private final String rootDirectory = "D:/liveCode"; // 프로젝트 파일 루트 디렉터리

    private final SocketProtocol socketProtocol;

    public FileManager(SocketProtocol socketProtocol) {
        this.socketProtocol = socketProtocol;
    }

    /**
     * 지정된 경로에 대한 FileSession을 가져오거나 새로 생성합니다.
     *
     * @param owner                프로젝트 소유자 ID
     * @param projectId            프로젝트 ID
     * @param filePath             프로젝트 내 상대 파일 경로
     * @param clientStream         클라이언트 OutputStream (새로 연 세션인 경우 편집자로 추가하기 위함)
     * @param userDisplayIdentifier 사용자 표시용 식별자 (닉네임#태그)
     * @return 해당 파일의 FileSession 객체
     */
    public FileSession getOrCreateSession(String owner,
                                          String projectId,
                                          String filePath,
                                          OutputStream clientStream,
                                          String userDisplayIdentifier) {
        String fileIdentifier = createFileIdentifier(owner, projectId, filePath);

        FileSession session = activeSessions.computeIfAbsent(fileIdentifier, id -> {
            Path fullPath = Path.of(rootDirectory, owner, projectId, filePath);
            return new FileSession(owner, projectId, fullPath, filePath, socketProtocol);
        });

        session.addEditor(clientStream, userDisplayIdentifier);
        return session;
    }

    /**
     * 파일 고유 식별자를 생성합니다.
     *
     * @param owner     프로젝트 소유자 ID
     * @param projectId 프로젝트 ID
     * @param filePath  프로젝트 내 상대 파일 경로
     * @return 고유 파일 식별자
     */
    public String createFileIdentifier(String owner, String projectId, String filePath) {
        return String.join("/", owner, projectId, filePath);
    }

    /**
     * 클라이언트 연결이 끊어졌을 때, 해당 클라이언트를 모든 세션에서 제거하고
     * 해당 사용자가 가지고 있던 모든 라인 락을 해제합니다.
     *
     * @param clientStream 연결이 끊어진 클라이언트의 OutputStream
     * @param userId       사용자 ID (null일 수 있음)
     */
    public void removeClientFromAllSessions(OutputStream clientStream, String userId) {
        activeSessions.values().forEach(session -> {
            session.removeEditor(clientStream);
            if (userId != null) {
                session.removeAndGetLocksByUserId(userId);
            }
        });
    }

    /**
     * 특정 파일 세션에서 클라이언트를 제거합니다.
     * 편집자와 처리할 연산이 모두 없으면 세션 자체를 제거합니다.
     *
     * @param owner        프로젝트 소유자 ID
     * @param projectId    프로젝트 ID
     * @param filePath     프로젝트 내 상대 파일 경로
     * @param clientStream 제거할 클라이언트의 OutputStream
     */
    public void removeClientFromFileSession(String owner,
                                            String projectId,
                                            String filePath,
                                            OutputStream clientStream) {
        String fileIdentifier = createFileIdentifier(owner, projectId, filePath);
        FileSession session = activeSessions.get(fileIdentifier);
        if (session != null) {
            session.removeEditor(clientStream);
            if (session.getEditorCount() == 0 && session.getOperationQueueSize() == 0) {
                activeSessions.remove(fileIdentifier);
                System.out.println("파일 세션 제거(편집자/대기 연산 없음): " + fileIdentifier);
            }
        }
    }

    /**
     * 파일 식별자로 FileSession을 조회합니다.
     *
     * @param fileIdentifier 조회할 파일 식별자
     * @return FileSession 객체, 없으면 null
     */
    public FileSession getSession(String fileIdentifier) {
        return activeSessions.get(fileIdentifier);
    }

    /**
     * 특정 파일 세션에서 클라이언트를 정리합니다.
     * (편집자 목록에서 제거하고 해당 사용자의 모든 락을 해제)
     * 편집자와 처리할 연산이 모두 없으면 세션을 제거합니다.
     *
     * @param fileIdentifier 파일 식별자
     * @param clientStream   제거할 클라이언트의 OutputStream
     * @param userId         사용자 ID
     */
    public void cleanupClientFromFileSession(String fileIdentifier,
                                             OutputStream clientStream,
                                             String userId) {
        FileSession session = getSession(fileIdentifier);
        if (session != null) {
            System.out.println("사용자 " + userId + "가 파일 세션에서 정리됨: " + fileIdentifier);
            session.removeEditor(clientStream);
            session.removeAndGetLocksByUserId(userId);

            if (session.getEditorCount() == 0 && session.getOperationQueueSize() == 0) {
                activeSessions.remove(fileIdentifier);
                System.out.println("파일 세션 제거(편집자/대기 연산 없음): " + fileIdentifier);
            }
        }
    }

    /**
     * 특정 파일 또는 폴더(하위 포함)에 대해 활성화된 편집 세션이 있는지 확인합니다.
     *
     * @param owner        프로젝트 소유자 ID
     * @param projectId    프로젝트 ID
     * @param relativePath 프로젝트 루트 기준 상대 경로 (파일 또는 폴더)
     * @return 열린 세션이 있는 파일들의 상대 경로 목록
     */
    public List<String> findActiveSessionsUnder(String owner,
                                                String projectId,
                                                String relativePath) {
        List<String> result = new ArrayList<>();
        if (relativePath == null) {
            return result;
        }

        String baseIdentifier = createFileIdentifier(owner, projectId, relativePath);

        activeSessions.forEach((key, session) -> {
            if (key.equals(baseIdentifier) || key.startsWith(baseIdentifier + "/")) {
                String[] parts = key.split("/", 3);
                if (parts.length == 3) {
                    result.add(parts[2]);
                }
            }
        });

        return result;
    }
}

