package com.ethis2s.util;

import com.ethis2s.controller.ProjectController;
import com.ethis2s.model.Operation;
import com.ethis2s.service.ChangeInitiator;
// RemoteCursorManager는 직접 사용하지 않으므로 주석 처리하거나 삭제 가능합니다.
// import com.ethis2s.service.RemoteCursorManager;

import org.json.JSONArray;
import org.json.JSONObject;

import javafx.animation.AnimationTimer; // --- [추가] AnimationTimer 임포트
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.util.Duration;

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

    // --- 애니메이션 ---
    private final Map<String, Timeline> userTimelines = new ConcurrentHashMap<>();
    private final Map<String, KeyValue> userKeyValues = new ConcurrentHashMap<>();
    private final Map<String, IntegerProperty> userVisualCursors = new ConcurrentHashMap<>();
    


    public OTManager(long initialVersion, ProjectController projectController, HybridManager hybridManager, String filePath) {
        this.localVersion = initialVersion;
        this.projectController = projectController;
        this.hybridManager = hybridManager;
        this.filePath = filePath;
        
        
    }

    /**
     * 이 OTManager 인스턴스와 관련된 리소스(AnimationTimer)를 정리합니다.
     * 에디터 탭이 닫히는 등의 시점에 반드시 호출해야 메모리 누수를 방지할 수 있습니다.
     */
    public void dispose() {
        Platform.runLater(() -> {
            userTimelines.values().forEach(Timeline::stop);
            userTimelines.clear();
            userKeyValues.clear();
            userVisualCursors.clear();
        });
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


    private void applyOperationToCodeArea(Operation op, String requesterId) {
        if (op == null) return;
        Platform.runLater(() -> {
            if (op.getType() == Operation.Type.INSERT) {
                hybridManager.controlledReplaceText(op.getPosition(), op.getPosition(), op.getText(), ChangeInitiator.SERVER);
            } else { // DELETE
                hybridManager.controlledReplaceText(op.getPosition(), op.getPosition() + op.getLength(), "", ChangeInitiator.SERVER);
            }

            Platform.runLater(() -> {
                hybridManager.getCodeArea().layout();
                if (requesterId != null) updateAndPlayAnimation(requesterId, op.getCursorPosition());
            });
        });
    }

     public void requestCursorUpdate(String requesterId, int cursorPosition) {
        if (requesterId != null) {
            updateAndPlayAnimation(requesterId, cursorPosition);
        }
    }


    private void updateAndPlayAnimation(String requesterId, int newTargetPosition) { // 메서드 이름을 바꿨습니다.
        Platform.runLater(() -> {
            // --- ▼▼▼ 데이터 안전성 확보 코드 ▼▼▼ ---
            // 1. 애니메이션을 시작하기 직전, CodeArea의 '현재' 길이를 가져옵니다.
            int currentDocLength = hybridManager.getCodeArea().getLength();
            
            // 2. 목표 위치가 현재 문서 길이를 초과하지 않도록 '보정(Clamp)'합니다.
            //    이것이 '사라진 위치'로 가려는 시도를 원천 차단합니다.
            int safeTargetPosition = Math.min(newTargetPosition, currentDocLength);
            // --- ▲▲▲ 데이터 안전성 확보 완료 ▲▲▲ ---

            // 이제 '안전한' 목표 위치를 가지고 애니메이션을 진행합니다.
            IntegerProperty visualCursor = userVisualCursors.computeIfAbsent(requesterId, id -> {
                // ... (이하 로직은 이전과 동일) ...
                // 초기 위치는 '안전한' 위치로 설정합니다.
                SimpleIntegerProperty property = new SimpleIntegerProperty(safeTargetPosition); 
                // ...
                return property;
            });

            KeyValue newKeyValue = new KeyValue(visualCursor, safeTargetPosition, Interpolator.EASE_OUT);
            KeyFrame newKeyFrame = new KeyFrame(Duration.millis(80), newKeyValue);
            
            Timeline timeline = userTimelines.computeIfAbsent(requesterId, id -> new Timeline());

            timeline.stop();
            timeline.getKeyFrames().setAll(newKeyFrame);
            timeline.playFromStart();
        });
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