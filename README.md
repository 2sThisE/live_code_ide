# Live Code IDE Server (Public Sample)

이 폴더는 GitHub 공개용 샘플 서버 프로젝트입니다.  
운영 환경에서 사용 중인 코드에서 **민감한 정보(비밀번호, 인증서, 실제 DB 정보)** 를 제거하고,
대신 예시 값과 설명만 남겨 두었습니다.

## 포함 내용

- `src/main/java/com/example/App.java`  
  - SSL 서버 소켓을 열고 각 클라이언트 연결마다 `ClientSessionHandler` 를 실행하는 진입점입니다.
  - `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KEYSTORE_PASSWORD` 는 모두 예시 값 (`CHANGE_ME`, `YOUR_DB_*`) 으로 설정되어 있습니다.
- 기타 Java 소스 파일  
  - 실시간 협업 편집, 파일 세션, 사용자/프로젝트 관리 등 핵심 로직을 포함합니다.

## 사용 전 설정

1. **데이터베이스 설정 변경**

   `App.java` 의 다음 상수를 실제 환경에 맞게 수정하거나,
   환경 변수/외부 설정 파일에서 읽도록 변경하는 것을 권장합니다.

   ```java
   private static final String DB_URL = "jdbc:mysql://localhost:3306/YOUR_DB_NAME";
   private static final String DB_USER = "YOUR_DB_USER";
   private static final String DB_PASSWORD = "CHANGE_ME";
   ```

2. **SSL 인증서(keystore) 준비**

   - `KEYSTORE_PATH` 가 가리키는 위치에 JKS 형식의 keystore 를 준비하고,
   - `KEYSTORE_PASSWORD` 를 실제 비밀번호로 변경하세요.
   - keystore 파일(예: `server.jks`)은 `.gitignore` 에 포함되어 있으므로 **절대 GitHub 에 커밋하지 마세요.**

3. **빌드 및 실행**

   Maven 기반 프로젝트라면:

   ```bash
   mvn clean package
   java -jar target/<생성된-jar>.jar
   ```

## 보안 주의사항

- 이 리포지토리에는 실제 서비스용 비밀번호나 인증서가 포함되어 있지 않습니다.
- 운영 환경에서는 반드시:
  - 비밀번호/토큰을 환경 변수 또는 별도 비공개 설정 파일로 관리하고,
  - keystore, DB 계정 등의 비밀 정보를 Git 에 커밋하지 않도록 주의하세요.

