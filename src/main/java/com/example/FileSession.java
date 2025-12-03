package com.example;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.TreeMap;

import org.json.JSONObject;
import socketprotocol.SocketProtocol;

/**
 * 특정 파일에 대한 실시간 편집 세션을 관리하는 클래스입니다.
 * 파일 내용, 편집자 목록, 연산 큐, 라인 락 정보를 포함합니다.
 * 이 클래스의 모든 공개 메서드는 스레드로부터 안전해야 합니다.
 */
public class FileSession {

    private static final int SNAPSHOT_INTERVAL = 100;                               // 연산 큐에 쌓일 연산의 최대 개수
    private static final int MAX_SAVE_OP=1000;                                      // 저장할 성공한 최대 연산의 수
    private final String owner;
    private final String projectId;
    private final Path filePath;
    private final String relativePath;
    private String content;                                                         // 파일의 현재 내용 (메모리 캐시)
    private final Map<OutputStream, String> editors = new ConcurrentHashMap<>();    // 이 파일을 편집 중인 클라이언트 목록
    private final List<Operation> operationQueue = new ArrayList<>();
    private final List<Operation> history=new ArrayList<>();
    
    private long version;

    // 라인 락 정보 (Key: 라인 번호, Value: 락을 소유한 사용자 ID)
    private final Map<Integer, String> lineLocks = new ConcurrentHashMap<>();

    // 파일 내용 및 편집자 목록에 대한 동시성 제어를 위한 Lock
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 이 세션에 접근 권한이 확인된 사용자 ID 목록 (메모리 캐시)
    private final Set<String> authorizedUsers = ConcurrentHashMap.newKeySet();

    // "목차" 역할을 할 자료구조
    private final TreeMap<Integer, Integer> lineStartOffsets = new TreeMap<>();

    private final SocketProtocol socketProtocol;

    // 스냅샷 작업 관리를 위한 상태 변수 및 스레드 풀
    private final AtomicBoolean isSnapshotting = new AtomicBoolean(false);
    private final ExecutorService snapshotExecutor = Executors.newSingleThreadExecutor();

    public FileSession(String owner, String projectId, Path filePath, String relativePath, SocketProtocol socketProtocol) {
        this.owner = owner;
        this.projectId = projectId;
        this.filePath = filePath;
        this.relativePath = relativePath;
        this.socketProtocol = socketProtocol;
        loadInitialContent();
    }

    /**
     * 권한 캐시에 사용자를 추가합니다.
     * @param userId 권한이 확인된 사용자 ID
     */
    public void addUserToAuthorized(String userId) {
        authorizedUsers.add(userId);
    }

    /**
     * 사용자가 권한 캐시에 있는지 확인합니다.
     * @param userId 확인할 사용자 ID
     * @return 권한이 있으면 true
     */
    public boolean isUserAuthorized(String userId) {
        return authorizedUsers.contains(userId);
    }

    /**
     * 권한 캐시에서 사용자를 제거합니다.
     * @param userId 제거할 사용자 ID
     */
    public void removeUserFromAuthorized(String userId) {
        authorizedUsers.remove(userId);
    }

    /**
     * 디스크에서 파일의 초기 내용을 읽어옵니다.
     * 원본 파일을 읽어옵니다     */
    private void loadInitialContent() {
        lock.writeLock().lock(); // 초기 로드 시에도 쓰기 락을 걸어 일관성 유지
        try {
            if (Files.exists(filePath)) {
                // 파일을 읽고 모든 종류의 줄바꿈 문자를 \n으로 정규화
                this.content = Files.readString(filePath).replaceAll("\\r\\n|\\r", "\n");
                System.out.println("원본 파일 로드 및 줄바꿈 정규화 완료: " + filePath);
            } else {
                this.content = ""; // 새 파일인 경우
                System.out.println("새 파일 생성: " + filePath);
            }
            buildLineOffsetsCache(); // ★★★ 목차 생성 호출 ★★★
        } catch (IOException e) {
            System.err.println("파일 초기 내용 로드 실패: " + filePath + " - " + e.getMessage());
            this.content = "Error loading file content.";
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 현재 content를 기반으로 라인 시작 위치 캐시("목차")를 만듭니다.
     * 이 메소드는 텍스트 전체를 스캔하므로, 꼭 필요할 때만 호출해야 합니다.
     */
    private void buildLineOffsetsCache() {
        lock.writeLock().lock(); // 캐시 빌드 시 쓰기 락
        try {
            lineStartOffsets.clear();
            lineStartOffsets.put(1, 0); // 1번 라인은 항상 0에서 시작
            int line = 2;
            int currentPos = 0;
            // String.indexOf()는 매우 최적화되어 있어 split()보다 훨씬 빠릅니다.
            while ((currentPos = content.indexOf('\n', currentPos)) != -1) {
                // 줄바꿈 문자 바로 다음 위치가 다음 라인의 시작점
                lineStartOffsets.put(line, currentPos + 1);
                line++;
                currentPos++; // 다음 검색을 위해 위치를 한 칸 이동
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 파일의 현재 내용을 반환합니다.
     * @return 파일 내용
     */
    public String getContent() {
        lock.readLock().lock();
        try {
            return content;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 파일의 현재 버전을 반환합니다.
     * @return 파일 버전
     */
    public long getVersion() {
        lock.readLock().lock();
        try {
            return version;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 새로운 편집자를 세션에 추가합니다.
     * @param editorStream 추가할 클라이언트의 OutputStream
     * @param userDisplayIdentifier 추가할 사용자의 '닉네임#태그'
     */
    public void addEditor(OutputStream editorStream, String userDisplayIdentifier) {
        lock.writeLock().lock();
        try {
            if (editorStream != null && userDisplayIdentifier != null) { // null이 아닐 때만 추가
                editors.put(editorStream, userDisplayIdentifier);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 세션에서 편집자를 제거합니다.
     * @param editorStream 제거할 클라이언트의 OutputStream
     */
    public void removeEditor(OutputStream editorStream) {
        lock.writeLock().lock();
        try {
            editors.remove(editorStream);
            // 편집자 목록이 비어있고, 저장할 연산이 남아있다면 즉시 저장
            if (editors.isEmpty() && !operationQueue.isEmpty()) {
                System.out.println("모든 편집자가 떠났습니다. 남은 연산을 디스크에 즉시 저장합니다: " + filePath);
                snapshotExecutor.submit(this::saveContentToDisk);
                version=0;
                history.clear(); // 버전이 리셋될 때 히스토리도 반드시 함께 초기화
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 파일 내용에 편집 연산을 적용하고, 필요한 경우 스냅샷 저장을 트리거합니다.
     * @param op 적용할 연산
     */
    public boolean applyOperation(Operation op) {
        lock.writeLock().lock();
        try {
            if (op.getVersion() == version) {
                int oldContentLength = content.length();
                
                // ★ 핵심 수정: DELETE라면 지우기 전에 '무엇이 지워질지' 미리 백업합니다.
                String deletedTextForCache = null;
                if (op.getType() == Operation.Type.DELETE) {
                    // 방어 코드: 범위 체크
                    int endPos = op.getPosition() + op.getLength();
                    if (endPos > content.length()) {
                        throw new StringIndexOutOfBoundsException("Delete range out of bounds");
                    }
                    deletedTextForCache = content.substring(op.getPosition(), endPos);
                }

                // 내용 변경 적용
                StringBuilder sb = new StringBuilder(content);
                applyOperationToContent(sb, op);
                content = sb.toString();

                // ★ 핵심 수정: 미리 백업해둔 텍스트를 넘겨줍니다.
                updateLineOffsetsIncrementally(op, deletedTextForCache, oldContentLength);
                
                operationQueue.add(op);
                applyHistroy(op);
                version++;
                triggerPeriodicSaveIfNeeded();
                return true;
            } else {
                return false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * history 리스트를 슬라이딩 윈도우 방식으로 관리합니다.
     * @param op 새로 추가할 연산
     */

    private void applyHistroy(Operation op){
        if(history.size()>=MAX_SAVE_OP) history.remove(0);
        history.add(op);
    }

    /**
     * StringBuilder에 연산을 적용하는 헬퍼 메서드
     */
    private void applyOperationToContent(StringBuilder sb, Operation op) {
        switch (op.getType()) {
            case INSERT:
                // 삽입될 텍스트의 모든 종류의 줄바꿈 문자를 \n으로 정규화
                String normalizedText = op.getText().replaceAll("\\r\\n|\\r", "\n");
                sb.insert(op.getPosition(), normalizedText);
                break;
            case DELETE:
                sb.delete(op.getPosition(), op.getPosition() + op.getLength());
                break;
        }
    }

    /**
     * 라인 캐시("목차")를 연산에 따라 효율적으로 증분 업데이트합니다.
     * @param op 적용된 연산
     * @param oldContentLength 연산 적용 전의 전체 텍스트 길이
     */
    private void updateLineOffsetsIncrementally(Operation op, String preCalculatedDeletedText, int oldContentLength) {
        int position = op.getPosition();
        int affectedLine = getLineFromPosition(position);

        if (op.getType() == Operation.Type.INSERT) {
            // INSERT 로직은 기존과 동일하지만 정규화만 주의
            String text = op.getText().replaceAll("\\r\\n|\\r", "\n");
            int lengthChange = text.length();
            int linesAdded = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') linesAdded++;
            }
            final int finalLinesAdded = linesAdded;

            if (lengthChange != 0) {
                List<Map.Entry<Integer, Integer>> entriesToUpdate = new ArrayList<>(lineStartOffsets.tailMap(affectedLine + 1).entrySet());
                entriesToUpdate.forEach(entry -> lineStartOffsets.remove(entry.getKey()));
                entriesToUpdate.forEach(entry -> {
                    lineStartOffsets.put(entry.getKey() + finalLinesAdded, entry.getValue() + lengthChange);
                });
            }
            if (linesAdded > 0) {
                int searchStartIndex = 0;
                int currentLine = affectedLine;
                int newlineIndexInText;
                while ((newlineIndexInText = text.indexOf('\n', searchStartIndex)) != -1) {
                    currentLine++;
                    int newLineOffset = position + newlineIndexInText + 1;
                    lineStartOffsets.put(currentLine, newLineOffset);
                    searchStartIndex = newlineIndexInText + 1;
                }
            }

        } else if (op.getType() == Operation.Type.DELETE) {
            // ★ 핵심 수정: content.substring 대신 아까 백업해둔 텍스트 사용
            String deletedText = preCalculatedDeletedText; 
            
            int lengthChange = -deletedText.length();
            int linesRemoved = 0;
            for (int i = 0; i < deletedText.length(); i++) {
                if (deletedText.charAt(i) == '\n') linesRemoved++;
            }

            final int finalLinesRemoved = linesRemoved;
            
            if (linesRemoved > 0) {
                for (int i = 1; i <= linesRemoved; i++) {
                    lineStartOffsets.remove(affectedLine + i);
                }
            }

            List<Map.Entry<Integer, Integer>> entriesToUpdate = new ArrayList<>(lineStartOffsets.tailMap(affectedLine + linesRemoved + 1).entrySet());
            entriesToUpdate.forEach(entry -> lineStartOffsets.remove(entry.getKey()));
            entriesToUpdate.forEach(entry -> {
                lineStartOffsets.put(entry.getKey() - finalLinesRemoved, entry.getValue() + lengthChange);
            });
        }
    }


    /**
     * 필요한 경우 주기적인 저장을 비동기적으로 트리거합니다.
     */
    private void triggerPeriodicSaveIfNeeded() {
        if (operationQueue.size() >= SNAPSHOT_INTERVAL && isSnapshotting.compareAndSet(false, true)) {
            snapshotExecutor.submit(this::saveContentToDisk);
        }
    }
    

    /**
     * 현재 파일 내용을 디스크에 저장하고 연산 큐를 정리합니다. (별도 스레드에서 실행)
     */
    public void saveContentToDisk() {
        String contentToSave;
        List<Operation> operationsToSave;
        boolean saveSucceeded = false;

        try {
            // --- 1. 락을 걸고 메모리 상태 복사 및 큐 비우기 ---
            lock.writeLock().lock();
            try {
                if (operationQueue.isEmpty()) return;
                contentToSave = this.content;
                operationsToSave = new ArrayList<>(this.operationQueue);
                this.operationQueue.clear();
            } finally {
                lock.writeLock().unlock();
            }

            // --- 2. 디스크에 저장 (락 없이 수행) ---
            try {
                Files.writeString(filePath, contentToSave.replaceAll("\\r\\n|\\r", "\n"));
                System.out.println("파일 저장 완료: " + filePath);
                saveSucceeded = true;
            } catch (IOException e) {
                System.err.println("파일 저장 실패, 연산을 큐로 복원합니다: " + filePath);
                e.printStackTrace();
            }

            // --- 3. 저장 실패 시 연산을 큐로 안전하게 복원 ---
            if (!saveSucceeded) {
                lock.writeLock().lock();
                try {
                    operationQueue.addAll(0, operationsToSave);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        } finally {
            // --- 4. 작업이 성공하든 실패하든, 스케줄 플래그를 리셋하여 다음 저장을 허용 ---
            isSnapshotting.set(false); 
        }
    }

    /**
     * 큐에 남아있는 모든 연산을 디스크에 즉시 동기적으로 저장합니다.
     * 주로 파일 다운로드와 같이 최신 상태의 파일이 반드시 필요할 때 호출됩니다.
     */
    public void flushOperationsToDisk() {
        // saveContentToDisk는 내부적으로 큐가 비어있는지 확인하므로, 여기서 바로 호출해도 안전합니다.
        // 이 메소드는 동기적으로 실행되어야 하므로 스레드 풀에 제출하지 않습니다.
        System.out.println("[FLUSH] " + filePath + "의 즉시 저장을 시도합니다...");
        saveContentToDisk();
        System.out.println("[FLUSH] " + filePath + "의 즉시 저장이 완료되었습니다.");
    }

    /**
     * 특정 클라이언트를 제외한 모든 편집자에게 편집 연산을 브로드캐스트합니다.
     * @param op 브로드캐스트할 연산
     * @param userDisplayIdentifier 연산을 발생시킨 사용자 ('닉네임'#'태그')
     * @param excludeStream 연산을 보낼 필요가 없는 클라이언트의 OutputStream (보통 연산을 보낸 클라이언트)
     */
    public void broadcastOperation(Operation op, String userDisplayIdentifier, OutputStream excludeStream) {
        lock.readLock().lock();
        try {
            JSONObject opJson = new JSONObject();
            opJson.put("project_id", this.projectId);
            opJson.put("owner", this.owner);
            opJson.put("path", this.relativePath);
            opJson.put("user", userDisplayIdentifier);
            opJson.put("type", op.getType().toString());
            opJson.put("position", op.getPosition());
            opJson.put("version",version);
            opJson.put("uniqId", op.getUniqId());
            if (op.getType() == Operation.Type.INSERT) {
                opJson.put("text", op.getText());
            } else if (op.getType() == Operation.Type.DELETE) {
                opJson.put("length", op.getLength());
            }
            // 커서 위치 정보가 있으면 추가
            if (op.getCursorPosition() != -1) {
                opJson.put("cursorPosition", op.getCursorPosition());
            }
            byte[] payloadBytes = opJson.toString().getBytes();
            broadcastToEditors(payloadBytes, ProtocolConstants.PTYPE_JSON, ProtocolConstants.UF_FILE_EDIT_BROADCAST, excludeStream, true);

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 클라이언트의 버전을 서버와 동기화
     * @param clientVerison 현재 클라이언트의 버전
     * @return 클라이언트의 버전부터 최신 연산까지의 리스트
     */
    public List<Operation> getHistoy(long clientVersion){
        if (history.isEmpty()) return new ArrayList<>();         //엣지 케이스 일어날 일은 없지만 만약을 대비
        long firstVer=history.get(0).getVersion();
        int startIndex = (int) ((clientVersion+1) - firstVer);
        if(clientVersion<firstVer-1) return null;                   //이미 지워진 연산 null로 강제 동기화 유도
        return history.subList(startIndex, history.size());
    }

    /**
     * 특정 클라이언트를 제외한 모든 편집자에게 커서 이동 정보를 브로드캐스트합니다.
     * @param userDisplayIdentifier 커서를 이동시킨 사용자 ID (또는 닉네임#태그)
     * @param cursorPosition 새로운 커서 위치
     * @param excludeStream 정보를 보낼 필요가 없는 클라이언트의 OutputStream
     */
    public void broadcastCursorMove(String userDisplayIdentifier, int cursorPosition, OutputStream excludeStream) {
        lock.readLock().lock();
        try {
            JSONObject payload = new JSONObject();
            payload.put("project_id", this.projectId);
            payload.put("owner", this.owner);
            payload.put("path", this.relativePath);
            payload.put("user", userDisplayIdentifier);
            payload.put("cursorPosition", cursorPosition);

            byte[] payloadBytes = payload.toString().getBytes();

            broadcastToEditors(payloadBytes, ProtocolConstants.PTYPE_JSON, ProtocolConstants.UF_CURSOR_MOVE_BROADCAST, excludeStream, false);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 특정 라인이 잠겼음을 모든 편집자에게 브로드캐스트합니다.
     * @param lineNumber 잠긴 라인 번호
     * @param lockOwnerDisplayIdentifier 락을 소유한 사용자 ('닉네임'#'태그')
     * @param excludeStream 브로드캐스트에서 제외할 클라이언트
     */
    public void broadcastLineLock(int lineNumber, String lockOwnerDisplayIdentifier, OutputStream excludeStream) {
        lock.readLock().lock();
        try {
            JSONObject payload = new JSONObject();
            payload.put("project_id", this.projectId);
            payload.put("owner", this.owner);
            payload.put("path", this.relativePath);
            payload.put("lineNumber", lineNumber);
            payload.put("lockOwner", lockOwnerDisplayIdentifier);

            byte[] payloadBytes = payload.toString().getBytes();

            broadcastToEditors(payloadBytes, ProtocolConstants.PTYPE_JSON, ProtocolConstants.UF_LINE_LOCK_BROADCAST, excludeStream, false);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 특정 라인의 잠금이 해제되었음을 모든 편집자에게 브로드캐스트합니다.
     * @param lineNumber 해제된 라인 번호
     * @param unlockerDisplayIdentifier 락을 해제한 사용자 ('닉네임'#'태그')
     * @param excludeStream 브로드캐스트에서 제외할 클라이언트
     */
    public void broadcastLineUnlock(int lineNumber, String unlockerDisplayIdentifier, OutputStream excludeStream) {
        lock.readLock().lock();
        try {
            JSONObject payload = new JSONObject();
            payload.put("project_id", this.projectId);
            payload.put("owner", this.owner);
            payload.put("path", this.relativePath);
            payload.put("lineNumber", lineNumber);
            payload.put("unlocker", unlockerDisplayIdentifier);

            byte[] payloadBytes = payload.toString().getBytes();

            broadcastToEditors(payloadBytes, ProtocolConstants.PTYPE_JSON, ProtocolConstants.UF_LINE_UNLOCK_BROADCAST, excludeStream, false);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 편집자 목록에 페이로드을 브로드캐스트하는 헬퍼 메서드
     * @param payloadBytes 보낼 페이로드 바이트 배열
     * @param payloadType 페이로드 타입
     * @param userField 사용자 필드
     * @param excludeStream 제외할 클라이언트
     */
    private void broadcastToEditors(byte[] payloadBytes, byte payloadType, int userField, OutputStream excludeStream, boolean includeSelf) {
        List<OutputStream> disconnectedEditors = new ArrayList<>();
        for (Map.Entry<OutputStream, String> entry : editors.entrySet()) {
            OutputStream editor = entry.getKey();
            if (includeSelf || editor != excludeStream) {
                try {
                    sendData(editor, payloadBytes, payloadType, userField);
                } catch (IOException e) {
                    System.err.println("브로드캐스트 중 오류 발생 (클라이언트 연결 끊김 추정): " + e.getMessage());
                    disconnectedEditors.add(editor);
                }
            }
        }
        // 연결이 끊긴 클라이언트 제거
        if (!disconnectedEditors.isEmpty()) {
            lock.readLock().unlock(); // readLock을 해제하고 writeLock을 얻어야 함
            lock.writeLock().lock();
            try {
                for (OutputStream disconnected : disconnectedEditors) {
                    editors.remove(disconnected);
                }
            } finally {
                lock.writeLock().unlock();
            }
            lock.readLock().lock(); // 다시 readLock을 얻음
        }
    }

    // --- 라인 락 관련 메서드 ---

    /**
     * 특정 라인에 락을 시도합니다.
     * @param lineNumber 락을 걸 라인 번호
     * @param userId     락을 요청하는 사용자 ID
     * @return 락 성공 시 true, 이미 다른 사용자가 락을 걸었다면 false
     */
    public boolean lockLine(int lineNumber, String userId) {
        // computeIfAbsent를 사용하여 원자적으로 락을 설정
        String existingLockOwner = lineLocks.putIfAbsent(lineNumber, userId);
        return existingLockOwner == null || existingLockOwner.equals(userId);
    }

    /**
     * 특정 라인의 락을 해제합니다.
     * @param lineNumber 락을 해제할 라인 번호
     * @param userId     락 해제를 요청하는 사용자 ID
     * @return 락을 성공적으로 해제했거나 원래 락 소유자가 아니어서 아무것도 하지 않은 경우 true,
     *         다른 사용자가 락을 소유하고 있어 해제할 수 없는 경우 false (이론적으로는 발생하면 안됨)
     */
    public boolean unlockLine(int lineNumber, String userId) {
        // 락 소유자인 경우에만 락을 해제
        boolean removed = lineLocks.remove(lineNumber, userId);
        return removed;
    }

    /**
     * 특정 라인이 현재 락 상태인지 확인합니다.
     * @param lineNumber 확인할 라인 번호
     * @return 락이 걸려있으면 true, 아니면 false
     */
    public boolean isLineLocked(int lineNumber) {
        return lineLocks.containsKey(lineNumber);
    }

    /**
     * 특정 라인을 누가 잠갔는지 확인합니다.
     * @param lineNumber 확인할 라인 번호
     * @return 락을 소유한 사용자 ID, 락이 없으면 null
     */
    public String getLineLockOwner(int lineNumber) {
        return lineLocks.get(lineNumber);
    }

    /**
     * 특정 사용자가 소유한 모든 라인 락을 해제하고, 해제된 라인 번호 목록을 반환합니다.
     * @param userId 락을 해제할 사용자 ID
     * @return 해제된 라인 번호 목록
     */
    public List<Integer> removeAndGetLocksByUserId(String userId) {
        List<Integer> removedLines = new ArrayList<>();
        lock.writeLock().lock();
        try {
            lineLocks.entrySet().removeIf(entry -> {
                if (entry.getValue().equals(userId)) {
                    removedLines.add(entry.getKey());
                    return true;
                }
                return false;
            });
        } finally {
            lock.writeLock().unlock();
        }
        return removedLines;
    }

    /**
     * 현재 편집자 수를 반환합니다.
     * @return 편집자 수
     */
    public int getEditorCount() {
        lock.readLock().lock();
        try {
            return editors.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 현재 연산 큐의 크기를 반환합니다.
     * @return 연산 큐 크기
     */
    public int getOperationQueueSize() {
        lock.readLock().lock();
        try {
            return operationQueue.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * "목차"를 사용하여 특정 위치(position)가 몇 번째 라인에 속하는지
     * 매우 빠르게 찾아내는 메소드입니다. (O(log L) 복잡도)
     * @param position 찾을 위치 (0부터 시작)
     * @return 해당 위치가 속한 라인 번호 (1부터 시작)
     */
    public int getLineFromPosition(int position) {
        lock.readLock().lock(); // 캐시 읽기 시점 보호
        try {
            if (position < 0) return 1; // 음수 방어

            // lineStartOffsets: key = 라인 번호, value = 해당 라인의 시작 offset
            // => "offset <= position"인 라인 중 가장 마지막 라인을 찾는다.
            int lastLine = 1;
            for (Map.Entry<Integer, Integer> entry : lineStartOffsets.entrySet()) {
                int lineNumber = entry.getKey();
                int lineOffset = entry.getValue();

                if (lineOffset <= position) {
                    lastLine = lineNumber;
                } else {
                    // 이후 라인들은 offset이 더 크므로 더 볼 필요 없음
                    break;
                }
            }
            return lastLine;
        } finally {
            lock.readLock().unlock();
        }
    }


    /**
     * 페이로드 크기에 따라 자동으로 분할 전송을 처리하는 헬퍼 메서드
     * @param out OutputStream
     * @param payload 전송할 전체 바이트 배열
     * @param payloadType 페이로드 타입 (PTYPE_JSON 등)
     * @param userField 사용자 정의 커맨드 (UF_... 등)
     * @throws IOException
     */
    private void sendData(OutputStream out, byte[] payload, byte payloadType, int userField) throws IOException {
        final int maxChunkSize = 8183; // SocketProtocol.MAX_PAYLOAD_SIZE

        if (payload.length <= maxChunkSize) {
            out.write(socketProtocol.toBytes(payload, ProtocolConstants.UNFRAGED, payloadType, userField));
        } else {
            int offset = 0;
            while (offset < payload.length) {
                int length = Math.min(maxChunkSize, payload.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(payload, offset, chunk, 0, length);

                offset += length;
                boolean isLast = (offset >= payload.length);
                byte fragFlag = isLast ? ProtocolConstants.UNFRAGED : ProtocolConstants.FRAGED;

                out.write(socketProtocol.toBytes(chunk, fragFlag, payloadType, userField));
            }
        }
        out.flush();
    }
}