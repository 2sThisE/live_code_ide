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
import javafx.animation.PauseTransition;
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


    public OTManager(long initialVersion, EditorContext context, HybridManager hybridManager) {
        this.localVersion = initialVersion;
        this.hybridManager = hybridManager;
        
        // 컨텍스트 객체에서 필요한 정보를 꺼내 멤버 변수에 저장합니다.
        this.projectController = context.getProjectController();
        this.filePath = context.getFilePath();
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

            final Operation finalOpForCorrection = transformedServerOp;
            Platform.runLater(() -> {
                // 현재 애니메이션이 진행 중인 모든 시각적 커서들에 대해...
                for (Map.Entry<String, IntegerProperty> entry : userVisualCursors.entrySet()) {
                    String userId = entry.getKey();
                    IntegerProperty visualCursor = entry.getValue();

                    // 이 커서가 방금 연산을 보낸 사용자(requesterId)의 것이라면 보정할 필요가 없습니다.
                    // 왜냐하면 이 사용자의 커서는 어차피 곧 새로운 목표점으로 애니메이션이 '재시작'될 것이기 때문입니다.
                    if (userId.equals(requesterId)) {
                        continue;
                    }

                    int currentVisualPosition = visualCursor.get();

                    // 'transform_position' 헬퍼 함수를 사용하여 현재 시각적 위치를 보정합니다.
                    int newVisualPosition = transform_position(currentVisualPosition, finalOpForCorrection);
                    
                    // 위치가 실제로 변경되었다면, IntegerProperty의 값을 즉시 업데이트합니다.
                    // 이렇게 하면 현재 진행 중인 Timeline 애니메이션이 이 새로운 위치를 기준으로 자연스럽게 경로를 이어갑니다.
                    if (currentVisualPosition != newVisualPosition) {
                        visualCursor.set(newVisualPosition);
                    }
                }
            });
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

    private int transform_position(int position, Operation otherOp) {
        int myPos = position;
        int otherPos = otherOp.getPosition();
        
        // --- [수정] "앞의 애니메이션은 영향이 없다"는 규칙을 명시적으로 적용 ---
        // 연산이 발생한 위치보다 앞에 있는 커서는 보정할 필요가 없습니다.
        if (myPos < otherPos) {
            return myPos;
        }

        int otherLen = otherOp.getLength();

        if (otherOp.getType() == Operation.Type.INSERT) {
            // myPos >= otherPos 인 경우에만
            myPos += otherLen;
        } else { // DELETE
            // myPos >= otherPos 인 경우에만
            myPos -= Math.min(myPos - otherPos, otherLen);
        }
        return myPos;
    }


    private void applyOperationToCodeArea(Operation op, String requesterId) {
        if (op == null) return;
        Platform.runLater(() -> {
            if (op.getType() == Operation.Type.INSERT) {
                hybridManager.controlledReplaceText(op.getPosition(), op.getPosition(), op.getText(), ChangeInitiator.SERVER);
            } else { // DELETE
                hybridManager.controlledReplaceText(op.getPosition(), op.getPosition() + op.getLength(), "", ChangeInitiator.SERVER);
            }

            if (requesterId != null) {
                hybridManager.getCodeArea().layout();
                Platform.runLater(() -> animateCursorTo(requesterId, op));
            }
        });
    }

    public void requestCursorUpdate(String requesterId, int cursorPosition) {
        if (requesterId != null) animateCursorTo(requesterId, new Operation(Operation.Type.INSERT, 0, null, cursorPosition, 0, null));
    }

    private void animateCursorTo(String requesterId, Operation op) {
        Platform.runLater(() -> {
            // 1. [데이터 안전장치] 목표 위치를 보정합니다. (필수)
            int currentDocLength = hybridManager.getCodeArea().getLength();
            int safeTargetPosition = Math.min(op.getCursorPosition(), currentDocLength);

            // 2. IntegerProperty를 가져오거나 생성합니다.
            IntegerProperty visualCursor = userVisualCursors.computeIfAbsent(requesterId, id -> {
                SimpleIntegerProperty property = new SimpleIntegerProperty(safeTargetPosition);
                String tabId = "file-" + filePath;
                property.addListener((obs, oldVal, newVal) -> {
                    hybridManager.getStateManager().getCursorManager(tabId).ifPresent(cursorManager -> {
                        cursorManager.updateCursor(requesterId, requesterId, newVal.intValue());
                    });
                });
                return property;
            });

            // 3. [핵심] 연산 종류에 따라 애니메이션 시간을 동적으로 결정합니다.
            Duration animationDuration;
            if (op.getType() == Operation.Type.DELETE) {
                // 삭제는 짧고 빠르게 반응하여 안정성을 높입니다. (40-60ms 추천)
                animationDuration = Duration.millis(50);
            } else { // INSERT 또는 순수 커서 이동
                // 삽입은 부드러움을 위해 조금 더 길게 설정합니다. (100-150ms 추천)
                animationDuration = Duration.millis(120); 
            }

            // 4. Timeline을 가져오거나 생성합니다.
            Timeline timeline = userTimelines.computeIfAbsent(requesterId, id -> new Timeline());
            
            // 5. 결정된 시간과 안전한 목표로 애니메이션 KeyFrame을 생성합니다.
            KeyValue newKeyValue = new KeyValue(visualCursor, safeTargetPosition, Interpolator.EASE_OUT);
            KeyFrame newKeyFrame = new KeyFrame(animationDuration, newKeyValue);

            // 6. 애니메이션을 실행합니다. (취소 후 재시작 방식)
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