package com.example;

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
    private final String text; // 삽입할 텍스트 또는 삭제된 텍스트 (필요에 따라)
    private final int length; // 삭제 연산 시 삭제할 길이
    private final int cursorPosition; // 연산 후의 커서 위치
    private final long version;
    private final String uniqId;
    // 삽입 연산용 생성자
    public Operation(Type type, int position, String text, int cursorPosition, long version, String uniqId) {
        if (type != Type.INSERT) {
            throw new IllegalArgumentException("Invalid type for insert operation constructor.");
        }
        this.type = type;
        this.position = position;
        this.text = text;
        this.length = text.length(); // 삽입된 텍스트의 길이
        this.cursorPosition = cursorPosition;
        this.version=version;
        this.uniqId=uniqId;
    }

    // 삭제 연산용 생성자
    public Operation(Type type, int position, int length, int cursorPosition, long version,String uniqId) {
        if (type != Type.DELETE) {
            throw new IllegalArgumentException("Invalid type for delete operation constructor.");
        }
        this.type = type;
        this.position = position;
        this.text = null; // 삭제 연산에서는 텍스트가 필요 없음
        this.length = length;
        this.cursorPosition = cursorPosition;
        this.version=version;
        this.uniqId=uniqId;
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

    public long getVersion(){
        return version;
    }

    public String getUniqId(){
        return uniqId;
    }

    @Override
    public String toString() {
        if (type == Type.INSERT) {
            return "Operation{type=INSERT, position=" + position + ", text='" + text + "', cursorPosition=" + cursorPosition + "}";
        } else {
            return "Operation{type=DELETE, position=" + position + ", length=" + length + ", cursorPosition=" + cursorPosition + "}";
        }
    }
}
