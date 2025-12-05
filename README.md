Live Code IDE 배포용 빌드
==========================

이 폴더는 IDE를 바로 실행해 볼 수 있도록 준비한 **배포(dist) 전용 빌드**를 담고 있습니다.  
운영체제별로 `Windows` / `Mac` 폴더가 나뉘어 있으니, 자신의 환경에 맞는 폴더를 사용하면 됩니다.

구조
----
- `Windows/`
  - `LiveCodeIDE.jar` : Java 21 이상이 설치된 환경에서 실행 가능한 fat JAR
  - `plugins/`        : TM4E / ANTLR / 설정 파일이 들어 있는 플러그인 폴더
  - `LiveCodeIDE/`    : jpackage로 만든 Windows 전용 실행 이미지가 들어 있는 폴더 (JRE 포함, 설치 불필요)
- `Mac/`
  - `Live Code IDE.app` : macOS용 앱 번들 (Apple Silicon 기준, Intel Mac은 공식 지원하지 않음)
  - `LiveCodeIDE.jar`   : macOS에서 직접 실행 가능한 fat JAR
  - `plugins/`          : macOS용 플러그인 폴더

Windows에서 사용 방법
--------------------
**1) exe 패키지(권장)**  
- `Windows/LiveCodeIDE/` 폴더 전체를 원하는 위치로 복사합니다.
- 폴더 구조 예:
  ```text
  LiveCodeIDE/
    LiveCodeIDE.exe
    app/
    runtime/
  ```
- `LiveCodeIDE.exe` 를 더블 클릭하면, 별도의 JDK 설치 없이 IDE가 실행됩니다.

**2) JAR + plugins 방식 (Java 21 이상 설치 필요)**  
- `Windows/LiveCodeIDE.jar` 와 `Windows/plugins/` 폴더를 같은 디렉터리로 복사합니다.
- 콘솔에서:
  ```bash
  java -jar "LiveCodeIDE.jar"
  ```
- 또는 Jar 파일을 더블 클릭해서 실행할 수 있습니다.

macOS에서 사용 방법
------------------
**1) 앱 번들 사용**  
- `Mac/Live Code IDE.app` 을 응용 프로그램 폴더나 원하는 위치로 복사합니다.
- 더블 클릭해서 실행합니다.  
  - 현재 앱 번들은 **Apple Silicon(M1 이상) macOS** 환경을 기준으로 빌드되었으며,  
    Intel Mac 환경에서는 정상 동작을 보장하지 않습니다.

### macOS 보안 경고(손상된 앱) 관련 안내

GitHub 등에서 ZIP 파일로 다운로드한 뒤 `Live Code IDE.app` 을 처음 실행할 때,  
macOS에서 아래와 같은 경고가 뜰 수 있습니다.

> `'Live Code IDE.app'은(는) 손상되었기 때문에 열 수 없습니다. 해당 항목을 휴지통으로 이동해야 합니다.`

이 메시지는 실제로 파일이 깨졌다는 뜻이 아니라,  
이 앱이 **Apple 개발자 계정으로 서명/노타라이즈(Notarize)되지 않은 외부 앱**이기 때문에  
Gatekeeper가 기본적으로 실행을 막는 것입니다.

앱 자체는 정상이며, 아래 두 방법 중 하나로 한 번만 허용해 주면 이후부터는 정상 실행됩니다.

#### 방법 A) Finder에서 수동으로 열기

1. Finder에서 `Live Code IDE.app` 을 **우클릭**합니다.  
2. 메뉴에서 **“열기”** 를 선택합니다.  
3. 경고 창이 다시 뜨면 이번에는 **“열기” 버튼을 눌러** 실행을 허용합니다.  

한 번 허용 절차를 거치고 나면, 이후에는 Dock/Launchpad에서 바로 실행할 수 있습니다.

#### 방법 B) 터미널에서 quarantine 플래그 제거

1. `Mac` 폴더가 있는 위치로 터미널에서 이동합니다.  
2. 다음 명령어를 실행합니다.

   ```bash
   xattr -r -d com.apple.quarantine "Live Code IDE.app"
   ```

3. 이후에는 `Live Code IDE.app` 을 더블 클릭해서 바로 실행할 수 있습니다.

**2) JAR + plugins 방식**  
- `Mac/LiveCodeIDE.jar` 와 `Mac/plugins/` 폴더를 같은 디렉터리에 둡니다.
- 터미널에서:
  ```bash
  java -jar "LiveCodeIDE.jar"
  ```

공통 주의 사항
-------------
- 항상 `LiveCodeIDE.jar` 와 `plugins` 폴더가 **같은 기준 디렉터리**에서 인식되도록 배치해야 합니다. (실행파일은 해당없음) 
  플러그인 폴더를 찾지 못하면 구문 강조·자동완성·설정 로드 기능이 정상 동작하지 않을 수 있습니다.
- 이 dist 빌드는 실행/체험용 패키지이며, 소스 코드는 GitHub의 `main`(클라이언트) / `server`(서버) 브랜치에서 관리됩니다.
