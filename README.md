# Live Code IDE Client

JavaFX 기반의 라이브 코드 IDE 클라이언트입니다.  
서버와 TLS 소켓으로 통신하며, 여러 사용자가 동시에 같은 프로젝트/파일을 편집하고 실행할 수 있도록 설계되어 있습니다.

- 프로그램 실행만 빠르게 해보고 싶다면, [**dist 브랜치**](https://github.com/2sThisE/live_code_ide/tree/dist)에서 배포용 JAR와 플러그인 패키지를 받아 사용할 수 있습니다.
- 서버 동작 방식과 프로토콜 구현이 궁금하다면, [**server 브랜치**](https://github.com/2sThisE/live_code_ide/tree/server)에서 서버 소스 코드를 확인할 수 있습니다.


## 핵심 기능

- **라이브 협업 편집**
  - `ClientSocketManager` 를 통해 TLS(SSL) 소켓으로 서버에 연결
  - `SocketProtocol` 바이너리 프로토콜을 사용하여 패킷 헤더 + CRC32 검증
  - `OTManager` + `HybridManager` 로 Operational Transform 기반 동시 편집 처리
  - `EditorInputManager` / `EditorListenerManager` 가 RichTextFX `CodeArea` 이벤트를 감지해
    로컬 수정 → Operation 으로 변환 → 서버로 전송 → 브로드캐스트 반영
  - `RemoteCursorManager` 를 통해 다른 사용자의 커서/선택 영역 시각화

- **프로젝트/파일 탐색 및 관리**
  - `MainScreen` 의 파일 트리에서 프로젝트/디렉터리/파일 탐색
  - 드래그 앤 드롭으로 파일 이동, 컨텍스트 메뉴를 통한 새 파일/폴더 생성, 삭제, 이름 변경
  - `ProjectController` / `UserProjectsInfo` 를 통해 현재 활성 프로젝트 관리 및 프로젝트 전환
  - 프로젝트 전환 시 열려 있는 탭/에디터를 현재 프로젝트 기준으로 정리

- **코드 편집기 기능**
  - `EditorTabView` 기반 멀티 탭 에디터
  - `Tm4eSyntaxHighlighter` + TM4E + RichTextFX로 TextMate 문법 하이라이트 지원
  - `AntlrLanguageService` / `AntlrCompletionService` 로 일부 언어에 대한 파싱/자동 완성
  - `EditorInputManager`:
    - 줄 잠금(Line Lock) 요청/해제
    - 괄호/따옴표 자동 완성, 자동 들여쓰기, 탭/시프트탭 처리
    - 서버에서 내려온 변경(Operation)을 caret 보정과 함께 안전하게 적용
  - `EditorSearchHandler`로 에디터 내 검색/다음/이전 이동, 대소문자 구분 옵션
  - `EditorStateManager`로 탭별 상태 및 변경 이력 관리

- **실행/디버깅 통합**
  - `ExecutionService` 를 통해 서버 측 빌드/실행 요청 전송
  - `FileExecutionSelectionView` 에서 실행할 파일/구성 선택
  - `RunView` / `OutputView` / `DebugView` 로 표준 출력, 에러, 디버그 메시지 표시
  - `DebugRedirectStream` 을 사용해 `System.out` / `System.err` 를 IDE 내 Debug Panel 로 리다이렉션

- **플러그인 기반 언어/하이라이트 확장**
  - `plugins/antlr` 디렉터리에 언어 플러그인 JAR + `antlr-config.json` 을 두어
    새로운 언어(예: Java, Python)의 파서/자동완성 로직을 독립적인 플러그인으로 로드
  - `AntlrLanguageService`, `AntlrCompletionService` 가 이 플러그인을 사용해
    구문 분석, 심볼 정보, 자동완성 후보 등을 제공
  - `plugins/tm4e` 의 `languages.properties` + `syntaxes/`(TextMate 문법) 를 통해
    TM4E 기반 문법 하이라이트를 언어별로 확장 가능

- **문제(에러) 뷰 / ANTLR 기반 분석**
  - `ProblemsView` 와 `MainController.updateProblems(...)` 를 통해
    ANTLR 기반 분석 결과(에러/경고)를 문제 목록으로 표시
  - 각 문제를 클릭하면 해당 위치로 커서 이동
  - `AntlrLanguageService` 가 백그라운드에서 소스 파싱 후 문제/하이라이트 정보 제공

- **UI/플랫폼 통합**
  - JavaFX 21 + MaterialFX + ControlsFX 를 활용한 커스텀 UI
  - `MainScreen` 의 커스텀 타이틀바, 창 이동/최소화/최대화/닫기 버튼 구현
  - `WindowsNativeUtil` / `MacosNativeUtil` 을 통해 OS 별 네이티브 타이틀바/창 스타일 일부 연동
  - `LoginScreen`, `RegisterScreen`, `SettingsView`, `SharedOptionScreen`, `ProjectPropertiesScreen` 등
    여러 화면이 `MainController` 에 의해 전환/관리됨

- **설정/환경 관리**
  - `ConfigManager` 로 사용자 설정/최근 프로젝트/서버 주소 등의 설정 파일 관리
  - `VariableResolver` 로 설정 내 변수 치환(`$HOME`, 프로젝트 루트 등)
  - `plugins/config/config.json` 을 통해 IDE 설정 항목을 선언적으로 정의:
    - `editor.fontFamily`, `fontSize`, `tabSize` 등 편집기 스타일
    - `design.mainTheme`, `topTabsTheme`, `bottomTabsTheme`, `treeViewTheme` 를 통해
      CSS 파일 경로를 바꿔 전체 테마/탭/트리뷰 스타일을 쉽게 교체
    - `runConfig.userCommands` 로 사용자 정의 실행 명령을 이름 → 실제 명령 문자열 형태로 등록
      (예: `Run Java`, `Run Selected File` 등)

---

## 플러그인 / 사용자 커스텀 기능

이 IDE는 “사용자 설정 파일 + 플러그인 디렉터리”를 중심으로 커스터마이즈 할 수 있습니다.

- **언어 플러그인 (`plugins/antlr`)**
  - `java-plugin.jar`, `python-plugin.jar` 와 같이 언어별 JAR 플러그인을 배치
  - `antlr-config.json` 에 어떤 언어/파일 확장자를 어떤 플러그인이 처리할지 매핑
  - IDE 코드를 건드리지 않고도 새로운 언어를 추가하거나 교체할 수 있음

- **문법 하이라이트 플러그인 (`plugins/tm4e`)**
  - `languages.properties` 에서 언어 ID ↔ 문법 파일 매핑
  - `syntaxes/*.json` 에 TextMate 형식 문법 정의를 넣어서 하이라이트를 확장

- **설정 플러그인 (`plugins/config/config.json`)**
  - JSON 구조로 기능별(featureKey) 설정 그룹을 정의:
    - `editor`: 폰트, 폰트 크기, 탭 크기 등 에디터 관련 설정
    - `design`: 메인/탑탭/바텀탭/트리뷰 테마 CSS 경로
    - `runConfig`: 실행 팩토리, 사용자 정의 실행 명령들
  - `SettingsView` 가 이 설정을 읽어 UI로 노출하고, 사용자가 직접 값을 변경할 수 있음
  - 사용자는 자신의 환경에 맞는 글꼴, 테마, 빌드/실행 명령을 IDE에서 바로 커스터마이즈 가능

## 아키텍처 개요

- `com.ethis2s.App`
  - JavaFX `Application` 진입점
  - 메인 스테이지 초기화, `MainController` 생성, `ClientSocketManager` 생성 및 연결 스레드 시작
  - 표준 출력/에러를 `DebugView` 로 리다이렉션

- `controller`
  - `MainController`: 전체 UI 흐름/상태를 관리하고 서버 콜백을 처리하는 중심 컨트롤러
  - `ProjectController`: 프로젝트 선택/파일 트리 동기화/공유 프로젝트 목록 관리

- `service`
  - `ClientSocketManager`: TLS 연결, 패킷 송수신, 재접속 로직
  - `EditorInputManager`, `RemoteCursorManager`, `CollaborativeUndoHelper`, `TabDragDropManager`, `ExecutionService` 등

- `view`
  - JavaFX UI 컴포넌트 (MainScreen, LoginScreen, RunView, ProblemsView, SettingsView 등)

- `util`
  - 편집기 관련 유틸(`EditorEnhancer`, `EditorListenerManager`, `EditorSearchHandler`, `EditorStateManager`)
  - OT/하이브리드 로직(`OTManager`, `HybridManager`)
  - 플랫폼 유틸(`WindowsNativeUtil`, `MacosNativeUtil`)

## 빌드 및 실행

필수:
- JDK 21
- Maven 3.x

빌드:

```bash
cd live_code_ide_cilent
mvn clean package
```

실행 (Maven exec 플러그인 사용):

```bash
mvn clean javafx:run
```

또는 IDE(IntelliJ 등)에서 `com.ethis2s.App` 을 JavaFX Application 으로 실행하면 됩니다.
