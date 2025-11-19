package com.ethis2s.util;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.Operation;
import com.ethis2s.service.ChangeInitiator;
// RemoteCursorManager는 직접 사용하지 않으므로 주석 처리하거나 삭제 가능합니다.
// import com.ethis2s.service.RemoteCursorManager;

import org.json.JSONArray;
import org.json.JSONObject;

import javafx.animation.AnimationTimer; // --- [추가] AnimationTimer 임포트
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map; // --- [추가] Map 임포트
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap; // --- [추가] ConcurrentHashMap 임포트

public class OTManager {

    private long localVersion = 0;
    private final Queue<Operation> unconfirmedOps = new LinkedList<>();
    private long uniqIdCounter = 0;

    // --- Concurrency Control ---
    private boolean isRebasing = false;
    private final Queue<Operation> pendingInputQueue = new LinkedList<>();

    private final ProjectController projectController;
    private final HybridManager hybridManager; // To apply changes
    private final String filePath;

    // --- [추가] UI 렌더링 최적화를 위한 멤버 변수들 ---
    /**
     * 레이아웃 및 커서 업데이트 작업을 화면 주사율에 맞춰 실행하는 타이머입니다.
     * 이를 통해 여러 연산이 짧은 시간 안에 들어와도 무거운 UI 작업은 한 프레임에 한 번만 실행되도록 제어합니다.
     */
    private final AnimationTimer layoutUpdater;
    /**
     * 다음 프레임에서 레이아웃 업데이트가 필요한지를 나타내는 플래그입니다.
     * 여러 스레드에서 접근할 수 있으므로 volatile로 선언합니다.
     */
    private volatile boolean layoutUpdateNeeded = false;
    /**
     * 한 프레임 동안 업데이트해야 할 모든 사용자의 커서 위치를 저장하는 맵입니다.
     * Key: 사용자 ID (requesterId), Value: 커서 위치 (cursorPosition)
     */
    private final Map<String, Integer> pendingCursorUpdates = new ConcurrentHashMap<>();


    public OTManager(long initialVersion, ProjectController projectController, HybridManager hybridManager, String filePath) {
        this.localVersion = initialVersion;
        this.projectController = projectController;
        this.hybridManager = hybridManager;
        this.filePath = filePath;
        
        this.layoutUpdater = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // '레이아웃 업데이트 필요' 플래그가 켜져 있을 때만 실행합니다.
                if (layoutUpdateNeeded) {
                    // 플래그를 즉시 내려서 다음 프레임에서 중복 실행되는 것을 방지합니다.
                    layoutUpdateNeeded = false;

                    // 1. 모든 텍스트 변경이 반영된 최종 상태에서 layout을 한 번만 실행합니다.
                    hybridManager.getCodeArea().layout();

                    // 2. 맵에 기록된 모든 사용자의 커서를 최신 레이아웃 기준으로 업데이트합니다.
                    String tabId = "file-" + filePath;
                    hybridManager.getStateManager().getCursorManager(tabId).ifPresent(cursorManager -> {
                        pendingCursorUpdates.forEach((requesterId, cursorPosition) -> {
                            cursorManager.updateCursor(requesterId, requesterId, cursorPosition);
                        });
                    });

                    // 3. 처리가 끝난 맵을 비워서 다음 프레임을 준비합니다.
                    pendingCursorUpdates.clear();
                }
            }
        };
        // OTManager가 생성될 때 타이머를 시작합니다.
        this.layoutUpdater.start();
    }

    /**
     * 이 OTManager 인스턴스와 관련된 리소스(AnimationTimer)를 정리합니다.
     * 에디터 탭이 닫히는 등의 시점에 반드시 호출해야 메모리 누수를 방지할 수 있습니다.
     */
    public void dispose() {
        this.layoutUpdater.stop();
    }

    // --- 기존 메서드들은 그대로 유지 ---
    public void handleBroadcast(long newVersion, String uniqId, String requesterId, Operation serverOp) {
        if (newVersion <= this.localVersion) {
            return; // Stale broadcast
        }

        Optional<String> currentUserOpt = projectController.getCurrentUserNicknameAndTag();
        if (currentUserOpt.isEmpty()) return;
        String myId = currentUserOpt.get();

        Operation pendingOp = unconfirmedOps.peek();

        if (pendingOp != null && pendingOp.isSentToServer() && myId.equals(requesterId)) {
            // This is a confirmation for an operation we sent.
            if (pendingOp.getUniqId().equals(uniqId) && pendingOp.getExpectedVersion() == newVersion) {
                // PREDICTION SUCCESS
                System.out.println("[OT_DEBUG] PREDICTION SUCCESS for op: " + pendingOp.getUniqId());
                unconfirmedOps.poll(); // Remove the confirmed operation
                this.localVersion = newVersion;
                sendNextPendingOperation(); // Send the next operation if any
            } else {
                // PREDICTION FAILURE: Confirmation mismatch (version or uniqId)
                System.err.println("[OT_DEBUG] PREDICTION FAILURE! Expected op: " + pendingOp + ", but received confirmation for: " + serverOp);
                abortUnconfirmedOperations(serverOp, requesterId);
            }
        } else {
            // This is an operation from another user.
            System.out.println("[OT_DEBUG] Received op from other user. Rebasing local operations against: " + serverOp);
            isRebasing = true;
            
            // Rebase our pending operations against the server's operation.
            Queue<Operation> rebasedOps = new LinkedList<>();
            for (Operation myOp : unconfirmedOps) {
                Operation rebasedOp = transform(myOp, serverOp);
                if (rebasedOp != null) {
                    rebasedOps.add(rebasedOp);
                }
            }
            
            // Apply the transformed server operation.
            Operation transformedServerOp = serverOp;
            for (Operation myOp : unconfirmedOps) {
                transformedServerOp = transform(transformedServerOp, myOp);
                if (transformedServerOp == null) break;
            }
            applyOperationToCodeArea(transformedServerOp, requesterId);

            // Update the queue with rebased operations and recalculate expected versions.
            this.unconfirmedOps.clear();
            long lastExpectedVersion = newVersion;
            for(Operation op : rebasedOps) {
                op.setExpectedVersion(++lastExpectedVersion);
                op.setSentToServer(false); 
                this.unconfirmedOps.add(op);
            }

            this.localVersion = newVersion;
            
            isRebasing = false;
            
            processPendingInputs();
            sendNextPendingOperation(); // After rebase, try sending the first pending op.
        }
    }

    public void handleCatchUp(JSONArray operations) {

        undoUnconfirmedOps();

        List<Operation> serverOps = new ArrayList<>();
        long lastVersion = -1;
        for (int i = 0; i < operations.length(); i++) {
            JSONObject opJson = operations.getJSONObject(i);
            Operation op = Operation.fromJson(opJson);
            serverOps.add(op);
            applyOperationToCodeArea(op, null); // Catch-up doesn't need a requester ID for cursor
            lastVersion = op.getVersion();
        }

        Queue<Operation> transformedOps = new LinkedList<>();
        for (Operation myOp : unconfirmedOps) {
            Operation transformedOp = myOp;
            for (Operation serverOp : serverOps) {
                if (transformedOp == null) break;
                transformedOp = transform(transformedOp, serverOp);
            }
            if (transformedOp != null) {
                applyOperationToCodeArea(transformedOp, null); // Local ops don't need cursor update
                transformedOps.add(transformedOp);
            }
        }
        this.unconfirmedOps.clear();
        this.unconfirmedOps.addAll(transformedOps);

        if (lastVersion != -1) {
            this.localVersion = lastVersion;
        }

        reapplyAndResendUnconfirmedOps();

    }
    
    // ... 나머지 기존 메서드들은 변경 없음 ...
    public void sendOperation(Operation op) {
        if (isRebasing) {
            pendingInputQueue.add(op);
            System.out.println("[OT_DEBUG] Rebasing in progress. Queuing operation temporarily: " + op);
            return;
        }

        projectController.getCurrentUserNicknameAndTag().ifPresent(userId -> {
            long lastVersion = unconfirmedOps.isEmpty() 
                ? this.localVersion 
                : unconfirmedOps.stream().mapToLong(Operation::getExpectedVersion).max().orElse(this.localVersion);
            
            op.setUniqId(userId + "-" + (uniqIdCounter++));
            op.setVersion(this.localVersion);
            op.setExpectedVersion(lastVersion + 1);
            op.setSentToServer(false);

            unconfirmedOps.add(op);
            System.out.println("[OT_DEBUG] Operation added to queue: " + op + " | Expected Version: " + op.getExpectedVersion());

            if (unconfirmedOps.stream().noneMatch(Operation::isSentToServer)) {
                sendNextPendingOperation();
            }
        });
    }

    private void abortUnconfirmedOperations(Operation serverOp, String requesterId) {
        System.err.println("Prediction failed! Aborting and rebasing unconfirmed operations.");
        isRebasing = true;
        
        Queue<Operation> rebasedOps = new LinkedList<>();
        for (Operation myOp : unconfirmedOps) {
            Operation rebasedOp = transform(myOp, serverOp);
            if (rebasedOp != null) {
                rebasedOps.add(rebasedOp);
            }
        }

        Operation transformedServerOp = serverOp;
        for (Operation myOp : unconfirmedOps) {
            transformedServerOp = transform(transformedServerOp, myOp);
            if (transformedServerOp == null) break;
        }
        applyOperationToCodeArea(transformedServerOp, requesterId);

        this.unconfirmedOps.clear();
        long lastExpectedVersion = serverOp.getVersion();
        for(Operation op : rebasedOps) {
            op.setExpectedVersion(++lastExpectedVersion);
            op.setSentToServer(false);
            this.unconfirmedOps.add(op);
        }

        this.localVersion = serverOp.getVersion();
        

        isRebasing = false;

        processPendingInputs();
        sendNextPendingOperation();
    }
    
    // --- [교체] applyOperationToCodeArea 메서드 ---
    /**
     * 수신된 Operation을 CodeArea에 적용합니다.
     * 이 메서드는 실제 텍스트 변경만 즉시 수행하고, 무거운 레이아웃 계산과 커서 업데이트는
     * AnimationTimer에게 다음 렌더링 프레임에 처리하도록 예약을 겁니다.
     *
     * @param op 적용할 Operation
     * @param requesterId 연산을 발생시킨 사용자의 ID (커서 업데이트에 사용됨)
     */
    private void applyOperationToCodeArea(Operation op, String requesterId) {
        if (op == null) return;

        Platform.runLater(() -> {
            // 1. 텍스트 변경은 즉시 실행하여 실시간성을 보장합니다. (이 작업은 비교적 가볍습니다)
            if (op.getType() == Operation.Type.INSERT) {
                hybridManager.controlledReplaceText(op.getPosition(), op.getPosition(), op.getText(), ChangeInitiator.SERVER);
            } else { // DELETE
                hybridManager.controlledReplaceText(op.getPosition(), op.getPosition() + op.getLength(), "", ChangeInitiator.SERVER);
            }

            // 2. 커서 업데이트가 필요한 경우, 'pendingCursorUpdates' 맵에 요청을 기록합니다.
            //    (requesterId가 null인 경우는 undo나 catch-up 상황이므로 커서를 그리지 않습니다)
            if (requesterId != null) {
                pendingCursorUpdates.put(requesterId, op.getCursorPosition());
            }

            // 3. '레이아웃 업데이트가 필요하다'고 플래그만 설정합니다.
            //    실제 무거운 작업은 AnimationTimer가 다음 프레임에 한 번만 수행할 것입니다.
            layoutUpdateNeeded = true;
        });
    }

    // --- 이하 나머지 메서드들은 변경 없음 ---
    private void processPendingInputs() {
        while (!pendingInputQueue.isEmpty()) {
            Operation pendingOp = pendingInputQueue.poll();
            System.out.println("[OT_DEBUG] Processing temporarily queued operation: " + pendingOp);
            sendOperation(pendingOp);
        }
    }

    private void sendNextPendingOperation() {
        if (unconfirmedOps.stream().anyMatch(Operation::isSentToServer)) {
            return;
        }

        unconfirmedOps.stream()
            .filter(o -> !o.isSentToServer())
            .findFirst()
            .ifPresent(opToSend -> {
                opToSend.setSentToServer(true);
                opToSend.setVersion(this.localVersion); 
                System.out.println("[OT_DEBUG] Sending operation to server: " + opToSend);
                projectController.fileEditOperationRequest(
                    this.filePath,
                    opToSend.getType().toString(),
                    opToSend.getPosition(),
                    opToSend.getText(),
                    opToSend.getLength(),
                    opToSend.getCursorPosition(),
                    opToSend.getVersion(),
                    opToSend.getUniqId()
                );
            });
    }

    private Operation transform(Operation myOp, Operation otherOp) {
        int myPos = myOp.getPosition();
        int otherPos = otherOp.getPosition();
        int myLen = myOp.getLength();
        int otherLen = otherOp.getLength();
        int myCursorPos = myOp.getCursorPosition();

        if (myOp.getType() == Operation.Type.INSERT && otherOp.getType() == Operation.Type.INSERT) {
            if (myPos < otherPos || (myPos == otherPos && myOp.getUniqId().compareTo(otherOp.getUniqId()) < 0)) {
                // No change to myPos
            } else {
                myPos += otherLen;
            }
            if (myCursorPos > otherPos) {
                myCursorPos += otherLen;
            }
        } else if (myOp.getType() == Operation.Type.INSERT && otherOp.getType() == Operation.Type.DELETE) {
            if (myPos > otherPos) {
                myPos -= Math.min(myPos - otherPos, otherLen);
            }
            if (myCursorPos > otherPos) {
                myCursorPos -= Math.min(myCursorPos - otherPos, otherLen);
            }
        } else if (myOp.getType() == Operation.Type.DELETE && otherOp.getType() == Operation.Type.INSERT) {
            if (myPos >= otherPos) {
                myPos += otherLen;
            }
            if (myCursorPos >= otherPos) {
                myCursorPos += otherLen;
            }
        } else if (myOp.getType() == Operation.Type.DELETE && otherOp.getType() == Operation.Type.DELETE) {
            int myEnd = myPos + myLen;
            int otherEnd = otherPos + otherLen;

            if (myEnd <= otherPos) {
                // No change
            } else if (myPos >= otherEnd) {
                myPos -= otherLen;
            } else { // Overlap
                int intersectionStart = Math.max(myPos, otherPos);
                int intersectionEnd = Math.min(myEnd, otherEnd);
                int intersectionLength = intersectionEnd - intersectionStart;

                if (intersectionLength > 0) {
                    myLen -= intersectionLength;
                }
                
                if (myPos > otherPos) {
                    myPos -= (Math.min(myPos, otherEnd) - otherPos);
                }
            }
            if (myCursorPos > otherPos) {
                myCursorPos -= Math.min(myCursorPos - otherPos, otherLen);
            }
            if (myLen <= 0) {
                return null;
            }
        }

        if (myOp.getType() == Operation.Type.INSERT) {
            return new Operation(myOp.getType(), myPos, myOp.getText(), myCursorPos, myOp.getVersion(), myOp.getUniqId());
        } else { // DELETE
            return new Operation(myOp.getType(), myPos, myOp.getText(), myLen, myCursorPos, myOp.getVersion(), myOp.getUniqId());
        }
    }

    private void undoUnconfirmedOps() {
        List<Operation> reversedOps = new ArrayList<>(unconfirmedOps);
        Collections.reverse(reversedOps);
        for (Operation op : reversedOps) {
            applyOperationToCodeArea(op.getInverse(), null); // No need to update cursor for undo
        }
    }

    private void reapplyAndResendUnconfirmedOps() {
        for (Operation op : unconfirmedOps) {
            op.setVersion(this.localVersion);
            projectController.fileEditOperationRequest(
                this.filePath,
                op.getType().toString(),
                op.getPosition(),
                op.getText(),
                op.getLength(),
                op.getCursorPosition(),
                op.getVersion(),
                op.getUniqId()
            );
        }
    }
}