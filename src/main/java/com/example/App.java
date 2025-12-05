package com.example;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import socketprotocol.SocketProtocol;

/**
 * Live Code IDE 서버 엔트리 포인트 (공개용 샘플 버전).
 * 실제 서비스 환경에서는 DB 정보와 keystore 비밀번호를 반드시 직접 설정해야 합니다.
 */
public class App {

    // 서버 포트
    private static final int PORT = 8080;

    // 샘플 keystore 경로 (실제 환경에 맞게 수정)
    private static final String KEYSTORE_PATH = "../server.jks";
    // 공개 저장소용 더미 비밀번호 (실제 비밀번호는 배포 환경에서 설정)
    private static final String KEYSTORE_PASSWORD = "CHANGE_ME";

    // MySQL 접속 정보 (예시 값, 실제 환경에 맞게 수정)
    private static final String DB_URL = "jdbc:mysql://localhost:3306/YOUR_DB_NAME";
    private static final String DB_USER = "YOUR_DB_USER";
    private static final String DB_PASSWORD = "CHANGE_ME";

    public static void main(String[] args) {
        System.out.println("Live Code IDE 서버 시작 중 (public build)...");

        // 프로토콜 및 파일 세션 관리자 초기화
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
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException
                 | UnrecoverableKeyException | KeyManagementException
                 | java.security.cert.CertificateException e) {
            System.err.println("SSLContext 초기화 실패: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
        ExecutorService executorService = Executors.newCachedThreadPool();

        try (SSLServerSocket serverSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(PORT)) {
            System.out.println("SSL 서버가 포트 " + PORT + "에서 수신 대기 중 (public build)...");

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

