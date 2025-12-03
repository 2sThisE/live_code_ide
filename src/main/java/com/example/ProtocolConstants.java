package com.example;

public final class ProtocolConstants {
    // 패킷 조각(프래그먼트) 플래그
    public static final byte FRAGED = 0x01;
    public static final byte UNFRAGED = 0x00;

    // 페이로드 타입
    public static final byte PTYPE_RAW_BYTES = 0x00; // 순수 바이트 배열
    public static final byte PTYPE_STRING = 0x01;    // 문자열(UTF-8)
    public static final byte PTYPE_JSON = 0x02;      // JSON 문자열
    public static final byte PTYPE_INT = 0x03;       // 정수
    public static final byte PTYPE_BOOLEAN = 0x04;   // 불리언
    public static final byte PTYPE_LONG = 0x05;      // long
    public static final byte PTYPE_FLOAT = 0x06;     // float
    public static final byte PTYPE_DOUBLE = 0x07;    // double
    public static final byte PTYPE_JSONARR = 0x08;   // JSON 배열

    // 유저 필드(명령 코드)
    public static final int UF_SERVER_ERROR = 0x000;              // 서버 에러
    public static final int UF_CLIENT_ERROR = 0x001;              // 클라이언트 잘못된 요청
    public static final int UF_LOGIN_REQUEST = 0x002;             // 로그인 요청
    public static final int UF_LOGIN_RESPONSE = 0x003;            // 로그인 응답
    public static final int UF_REGISTER_REQUEST = 0x004;          // 회원가입 요청
    public static final int UF_REGISTER_RESPONSE = 0x005;         // 회원가입 응답
    public static final int UF_USER_INFO = 0x006;                 // 사용자 정보
    public static final int UF_PROJECT_LIST_REQUEST = 0x007;      // 프로젝트 목록 요청
    public static final int UF_PROJECT_LIST_RESPONSE = 0x008;     // 프로젝트 목록 응답
    public static final int UF_FILETREE_LIST_REQUEST = 0x009;     // 파일 트리 요청
    public static final int UF_FILETREE_LIST_RESPONSE = 0x00A;    // 파일 트리 응답
    public static final int UF_CREATE_PROJECT_REQUEST = 0x00B;    // 프로젝트 생성 요청
    public static final int UF_CREATE_PROJECT_RSPONSE = 0x00C;    // 프로젝트 생성 응답
    public static final int UF_DELETE_PROJECT_REQUEST = 0x00D;    // 프로젝트 삭제 요청
    public static final int UF_DELETE_PROJECT_RSPONSE = 0x00E;    // 프로젝트 삭제 응답
    public static final int UF_ADD_SHARE_REQUEST = 0x00F;         // 공유 추가 요청
    public static final int UF_ADD_SHARE_RESPONSE = 0x010;        // 공유 추가 응답
    public static final int UF_DELETE_SHARE_REQUEST = 0x011;      // 공유 삭제 요청
    public static final int UF_DELETE_SHARE_RESPONSE = 0x012;     // 공유 삭제 응답
    public static final int UF_SHARED_LIST_REQUEST = 0x013;       // 공유 목록 요청
    public static final int UF_SHARED_LIST_RESPONSE = 0x014;      // 공유 목록 응답
    public static final int UF_ADD_FOLDER_REQUEST = 0x015;        // 폴더 생성 요청
    public static final int UF_ADD_FOLDER_RESPONSE = 0x016;       // 폴더 생성 응답
    public static final int UF_DELETE_FOLDER_REQUEST = 0x017;     // 폴더 삭제 요청
    public static final int UF_DELETE_FOLDER_RESPONSE = 0x018;    // 폴더 삭제 응답
    public static final int UF_ADD_FILE_REQUEST = 0x019;          // 파일 생성 요청
    public static final int UF_ADD_FILE_RESPONSE = 0x01A;         // 파일 생성 응답
    public static final int UF_DELETE_FILE_REQUEST = 0x01B;       // 파일 삭제 요청
    public static final int UF_DELETE_FILE_RESPONSE = 0x01C;      // 파일 삭제 응답
    public static final int UF_FILE_CONTENT_REQUEST = 0x01D;      // 파일 내용 요청
    public static final int UF_FILE_CONTENT_RESPONSE = 0x01E;     // 파일 내용 응답

    // 실시간 편집 / 락 관련
    public static final int UF_LINE_LOCK_REQUEST = 0x01F;         // 라인 락 요청
    public static final int UF_LINE_LOCK_RESPONSE = 0x020;        // 라인 락 응답
    public static final int UF_LINE_UNLOCK_REQUEST = 0x021;       // 라인 락 해제 요청
    public static final int UF_LINE_UNLOCK_RESPONSE = 0x022;      // 라인 락 해제 응답
    public static final int UF_FILE_EDIT_OPERATION = 0x023;       // 편집 연산 전송
    public static final int UF_FILE_EDIT_BROADCAST = 0x024;       // 편집 연산 브로드캐스트
    public static final int UF_CURSOR_MOVE = 0x025;               // 커서 이동 전송
    public static final int UF_CURSOR_MOVE_BROADCAST = 0x026;     // 커서 이동 브로드캐스트
    public static final int UF_FILE_CLOSE_REQUEST = 0x028;        // 파일 닫기 요청
    public static final int UF_FILE_CLOSE_RESPONSE = 0x029;       // 파일 닫기 응답
    public static final int UF_LINE_LOCK_BROADCAST = 0x02A;       // 라인 락 브로드캐스트
    public static final int UF_LINE_UNLOCK_BROADCAST = 0x02B;     // 라인 락 해제 브로드캐스트
    public static final int UF_HISTORY = 0x02C;                   // 편집 히스토리 전달
    public static final int UF_GET_PROJECT_FILE_REQUEST = 0x02D;  // 프로젝트 파일 목록/내용 요청
    public static final int UF_GET_PROJECT_FILE_RESPONSE = 0x02E; // 프로젝트 파일 목록/내용 응답
    public static final int UF_CHANG_FILE_LOC_RESPONSE = 0x02F;   // 파일/폴더 위치 변경 응답
    public static final int UF_CHANG_FILE_LOC_REQUEST = 0x030;    // 파일/폴더 위치 변경 요청
    public static final int UF_CHANG_FILE_NAME_RESPONSE = 0x031;  // 파일/폴더 이름 변경 응답
    public static final int UF_CHANG_FILE_NAME_REQUEST = 0x032;   // 파일/폴더 이름 변경 요청

    // --- 클라이언트에 노출되는 에러 코드 ---

    // 1000번대: 실시간 편집 관련 에러
    public static final int ERROR_CODE_LINE_LOCKED = 1001;                // 다른 사용자가 라인 락 보유
    public static final int ERROR_CODE_INVALID_POSITION_INSERT = 1002;    // INSERT 위치가 잘못됨
    public static final int ERROR_CODE_INVALID_POSITION_DELETE = 1003;    // DELETE 범위가 잘못됨
    public static final int ERROR_CODE_SYNC_ERROR = 1004;                 // 서버/클라이언트 내용 불일치
    public static final int ERROR_CODE_PATH_TRAVERSAL_ATTEMPT = 1005;     // 프로젝트 루트 밖으로 나가는 경로
    public static final int ERROR_CODE_FILE_OR_FOLDER_IN_USE = 1006;      // 열려 있는 파일/폴더 때문에 이동 불가
    public static final int ERROR_CODE_PATH_CONFLICT = 1007;              // 대상 경로에 이미 파일/폴더 존재

    // 2000번대: 권한 / 잘못된 요청 관련 에러
    public static final int ERROR_CODE_NOT_AUTHORIZED = 2001;             // 해당 파일 세션 권한 없음
    public static final int ERROR_CODE_OWNER_VERIFICATION_FAILED = 2002;  // 소유자 검증 실패
    public static final int ERROR_CODE_INVALID_OPERATION = 2003;          // 잘못된 연산/파라미터
    public static final int ERROR_CODE_PROJECT_OWNER_VERIFICATION_FAILED = 2004; // 프로젝트 소유자 검증 실패(DB)

    private ProtocolConstants() {
        // 인스턴스 생성 방지
    }
}

