package com.ethis2s.model;

public final class ProtocolConstants {
    // Fragment flags
    public static final byte FRAGED = 0x01;
    public static final byte UNFRAGED = 0x00;

    // Payload Types (Data Format of the Payload)
    public static final byte PTYPE_RAW_BYTES = 0x00; // Raw byte array payload
    public static final byte PTYPE_STRING = 0x01;    // String payload (UTF-8 encoded)
    public static final byte PTYPE_JSON = 0x02;      // JSON string payload
    public static final byte PTYPE_INT = 0x03;       // Integer payload
    public static final byte PTYPE_BOOLEAN = 0x04;   // Boolean payload
    public static final byte PTYPE_LONG = 0x05;      // Long payload
    public static final byte PTYPE_FLOAT = 0x06;     // Float payload
    public static final byte PTYPE_DOUBLE = 0x07;    // Double payload
    public static final byte PTYPE_JSONARR = 0x08;


    // User Field Commands (Application-specific commands/requests)
    public static final int UF_SERVER_ERROR = 0x000; // server error
    public static final int UF_CLIENT_ERROR = 0x001; // client error
    public static final int UF_LOGIN_REQUEST = 0x002; // Login request
    public static final int UF_LOGIN_RESPONSE = 0x003; // Login response
    public static final int UF_REGISTER_REQUEST = 0x004; // Register response
    public static final int UF_REGISTER_RESPONSE = 0x005; // Register response
    public static final int UF_USER_INFO = 0x006; // user info
    public static final int UF_PROJECT_LIST_REQUEST=0x007;
    public static final int UF_PROJECT_LIST_RESPONSE=0x008;
    public static final int UF_FILETREE_LIST_REQUEST=0x009;
    public static final int UF_FILETREE_LIST_RESPONSE=0x00A;
    public static final int UF_CREATE_PROJECT_REQUEST=0x00B;
    public static final int UF_CREATE_PROJECT_RSPONSE=0x00C;
    public static final int UF_DELETE_PROJECT_REQUEST=0x00D;
    public static final int UF_DELETE_PROJECT_RSPONSE=0x00E;
    public static final int UF_ADD_SHARE_REQUEST=0x00F;
    public static final int UF_ADD_SHARE_RESPONSE=0x010;
    public static final int UF_DELETE_SHARE_REQUEST=0x011;
    public static final int UF_DELETE_SHARE_RESPONSE=0x012;
    public static final int UF_SHARED_LIST_REQUEST=0x013;
    public static final int UF_SHARED_LIST_RESPONSE=0x014;
    public static final int UF_ADD_FOLDER_REQUEST=0x015;
    public static final int UF_ADD_FOLDER_RESPONSE=0x016;
    public static final int UF_DELETE_FOLDER_REQUEST=0x017;
    public static final int UF_DELETE_FOLDER_RESPONSE=0x018;
    public static final int UF_ADD_FILE_REQUEST=0x019;
    public static final int UF_ADD_FILE_RESPONSE=0x01A;
    public static final int UF_DELETE_FILE_REQUEST=0x01B;
    public static final int UF_DELETE_FILE_RESPONSE=0x01C;
    public static final int UF_FILE_CONTENT_REQUEST=0x01D;
    public static final int UF_FILE_CONTENT_RESPONSE=0x01E;

    // Real-time Editing and Line Lock Commands
    public static final int UF_LINE_LOCK_REQUEST = 0x01F;
    public static final int UF_LINE_LOCK_RESPONSE = 0x020;
    public static final int UF_LINE_UNLOCK_REQUEST = 0x021;
    public static final int UF_LINE_UNLOCK_RESPONSE = 0x022;
    public static final int UF_FILE_EDIT_OPERATION = 0x023; // 클라이언트 -> 서버
    public static final int UF_FILE_EDIT_BROADCAST = 0x024; // 서버 -> 클라이언트
    public static final int UF_CURSOR_MOVE = 0x025; // 클라이언트 -> 서버 (단순 커서 이동)
    public static final int UF_CURSOR_MOVE_BROADCAST = 0x026; // 서버 -> 클라이언트 (커서 이동 브로드캐스트)
    public static final int UF_FILE_CLOSE_REQUEST = 0x028; // 파일 닫기 요청
    public static final int UF_FILE_CLOSE_RESPONSE = 0x029; // 파일 닫기 응답
    public static final int UF_LINE_LOCK_BROADCAST = 0x02A; // 라인 락 브로드캐스트
    public static final int UF_LINE_UNLOCK_BROADCAST = 0x02B; // 라인 락 해제 브로드캐스트
    public static final int UF_HISTORY = 0x02C; // 과거 클라이언트가 놓친 정보 업데이트

    // --- 클라이언트 에러 코드 (정수) ---

    // 1000번대: 실시간 편집 관련 에러
    public static final int ERROR_CODE_LINE_LOCKED = 1001; // 다른 사용자가 라인을 잠금
    public static final int ERROR_CODE_INVALID_POSITION_INSERT = 1002; // INSERT 연산의 position이 유효 범위를 벗어남
    public static final int ERROR_CODE_INVALID_POSITION_DELETE = 1003; // DELETE 연산의 position 또는 length가 유효 범위를 벗어남
    public static final int ERROR_CODE_SYNC_ERROR = 1004; // 서버의 파일 내용과 클라이언트의 연산 간 동기화 오류 (IndexOutOfBounds 등)

    // 2000번대: 권한 및 일반 요청 에러
    public static final int ERROR_CODE_NOT_AUTHORIZED = 2001; // 해당 파일 세션에 대한 권한이 없음 (캐시 확인 실패)
    public static final int ERROR_CODE_OWNER_VERIFICATION_FAILED = 2002; // 프로젝트 소유자 검증 실패 (경로 조작 시도 등)
    public static final int ERROR_CODE_INVALID_OPERATION = 2003; // 유효하지 않은 연산 타입 또는 필수 파라미터 누락
    public static final int ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED = 2004; // 프로젝트 소유자 검증 실패 (DB 조회 시점)

    private ProtocolConstants() {
        // Private constructor to prevent instantiation
    }
}
