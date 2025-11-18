package com.ethis2s.model;

import org.json.JSONObject;

/**
 * 실시간 동시 편집에서 클라이언트로부터 수신되는 편집 연산을 나타내는 클래스입니다.
 * 현재는 간단한 텍스트 삽입/삭제 연산을 가정합니다.
 */
public class Operation {
    public enum Type {
        INSERT,
        DELETE
    }

    private final Type type;
    private final int position;
    private final String text; // 삽입 또는 삭제된 텍스트
    private final int length; // 연산에 의해 변경된 텍스트의 길이
    private final int cursorPosition; // 연산 후의 커서 위치
    private long version;
    private String uniqId;

    // --- Predictive Model Fields ---
    private long expectedVersion;
    private boolean sentToServer;

    // 삽입 연산용 생성자
    public Operation(Type type, int position, String text, int cursorPosition, long version, String uniqId) {
        if (type != Type.INSERT) {
            throw new IllegalArgumentException("Invalid type for insert operation constructor.");
        }
        this.type = type;
        this.position = position;
        this.text = text;
        this.length = text.length();
        this.cursorPosition = cursorPosition;
        this.version = version;
        this.uniqId = uniqId;
        this.sentToServer = false; // Initially not sent
    }

    // 삭제 연산용 생성자 (이제 text를 받음)
    public Operation(Type type, int position, String text, int length, int cursorPosition, long version, String uniqId) {
        if (type != Type.DELETE) {
            throw new IllegalArgumentException("Invalid type for delete operation constructor.");
        }
        this.type = type;
        this.position = position;
        this.text = text; // 삭제된 텍스트 저장
        this.length = length;
        this.cursorPosition = cursorPosition;
        this.version = version;
        this.uniqId = uniqId;
        this.sentToServer = false; // Initially not sent
    }

    /**
     * 이 연산의 '반대' 연산을 반환합니다. (Undo를 위해 사용)
     * @return 역 연산(Inverse Operation)
     */
    public Operation getInverse() {
        if (this.type == Type.INSERT) {
            // INSERT의 반대는 DELETE
            return new Operation(Type.DELETE, this.position, this.text, this.text.length(), -1, -1L, null);
        } else {
            // DELETE의 반대는 INSERT
            return new Operation(Type.INSERT, this.position, this.text, -1, -1L, null);
        }
    }

    public Type getType() {
        return type;
    }

    public int getPosition() {
        return position;
    }

    public String getText() {
        return text;
    }

    public int getLength() {
        return length;
    }

    public int getCursorPosition() {
        return cursorPosition;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getUniqId() {
        return uniqId;
    }

    public void setUniqId(String uniqId) {
        this.uniqId = uniqId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public boolean isSentToServer() {
        return sentToServer;
    }

    public void setSentToServer(boolean sentToServer) {
        this.sentToServer = sentToServer;
    }

    public static Operation fromJson(JSONObject json) {
        Type type = Type.valueOf(json.getString("type"));
        int position = json.getInt("position");
        long version = json.getLong("version");
        String uniqId = json.optString("uniqId", null);
        int cursorPosition = json.optInt("cursorPosition", -1);

        if (type == Type.INSERT) {
            String text = json.getString("text");
            return new Operation(type, position, text, cursorPosition, version, uniqId);
        } else { // DELETE
            String text = json.getString("text"); // 서버는 삭제된 텍스트를 보내줌
            int length = text.length(); // 클라이언트에서 길이를 계산
            return new Operation(type, position, text, length, cursorPosition, version, uniqId);
        }
    }

    @Override
    public String toString() {
        if (type == Type.INSERT) {
            return "Operation{type=INSERT, position=" + position + ", text='" + text + "', cursorPosition=" + cursorPosition + ", version=" + version + "}";
        } else {
            return "Operation{type=DELETE, position=" + position + ", length=" + length + ", text='" + text + "', cursorPosition=" + cursorPosition + ", version=" + version + "}";
        }
    }
}
