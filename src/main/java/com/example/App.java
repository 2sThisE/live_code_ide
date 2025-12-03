package com.example;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import socketprotocol.SocketProtocol;

/**
 * Live Code IDE 서버의 진입점으로,
 * SSL 서버 소켓을 열고 각 클라이언트 연결마다 ClientSessionHandler를 실행합니다.
 *
 * 공개용 샘플이므로 DB 접속 정보와 keystore 비밀번호는 더미 값으로 설정되어 있습니다.
 */
public class App {

    // 서버 포트 (예시 값)
    private static final int PORT = 8080;

    // 서버 인증서(keystore) 경로 및 비밀번호 (예시 값)
    // 실제 서비스에서는 환경 변수나 별도 설정 파일로 전달하는 것을 권장합니다.
    private static final String KEYSTORE_PATH = "../server.jks";
    private static final String KEYSTORE_PASSWORD = "CHANGE_ME";

    // MySQL 데이터베이스 연결 정보 (예시 값)
    // 공개 저장소에서는 실제 DB URL/계정/비밀번호를 절대 직접 넣지 마세요.
    private static final String DB_URL = "jdbc:mysql://localhost:3306/YOUR_DB_NAME";
    private static final String DB_USER = "YOUR_DB_USER";
    private static final String DB_PASSWORD = "CHANGE_ME";

    public static void main(String[] args) {
        System.out.println("Live Code IDE 서버 시작 중...");

        // SocketProtocol 및 FileManager 초기화
        SocketProtocol socketProtocol = new SocketProtocol();
        FileManager fileManager = new FileManager(socketProtocol);

        // SSLContext 초기화
        SSLContext sslContext;
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(KEYSTORE_PATH), KEYSTORE_PASSWORD.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException |
                 UnrecoverableKeyException | KeyManagementException |
                 java.security.cert.CertificateException e) {
            System.err.println("SSLContext 초기화 실패: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // SSLServerSocketFactory 생성
        SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();

        // 클라이언트 세션 처리를 위한 스레드 풀
        ExecutorService executorService = Executors.newCachedThreadPool();

        try (SSLServerSocket serverSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(PORT)) {
            System.out.println("SSL 서버가 " + PORT + " 포트에서 수신 대기 중...");

            while (true) {
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                System.out.println("클라이언트 연결됨: " + clientSocket.getInetAddress());

                executorService.submit(
                    new ClientSessionHandler(clientSocket, DB_URL, DB_USER, DB_PASSWORD, fileManager)
                );
            }
        } catch (IOException e) {
            System.err.println("서버 소켓 오류: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }
    }
}

