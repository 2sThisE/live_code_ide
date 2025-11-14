/*
 * ## 실시간 동시 편집 서버 아키텍처 설계 ##
 *
 * 목표: 성능, 안정성, 자원 효율성의 균형을 맞춘 동시 편집 서버 구현
 * 핵심 모델: "스냅샷 + 저널링(연산 큐)" 하이브리드 모델
 *
 * --- 주요 동작 방식 ---
 *
 * 1. 평상시 동작 (실시간 편집 및 전파)
 *    - 클라이언트로부터 수정 연산(Operation/Delta)을 수신.
 *    - 수신된 연산을 메모리의 "연산 큐(Operation Queue)"에 저장.
 *    - 동시에, 해당 파일을 편집 중인 다른 모든 클라이언트에게 수신된 연산을 즉시 브로드캐스트하여 실시간 동기화 구현.
 *
 * 2. 스냅샷 생성 (주기적인 저장 및 안정성 확보)
 *    - 메모리의 "연산 큐"에 연산이 일정 개수(예: 100개) 쌓일 때마다 스냅샷 생성 프로세스 발동.
 *    - 현재 디스크에 저장된 최신 스냅샷(또는 원본 파일)을 읽어옴.
 *    - 지금까지 쌓인 100개의 연산을 스냅샷에 순서대로 적용.
 *    - 결과물을 새로운 스냅샷으로 디스크에 덮어��기하여 변경 사항을 영구 반영.
 *    - 작업 완료 후, 메모리의 "연산 큐"를 비워 리소스를 효율적으로 관리.
 *
 * 3. 새로운 사용자 접속 처리 (빠른 로딩)
 *    - 새로운 사용자가 파일 열기를 요청.
 *    - 서버는 디스크의 원본 파일이 아닌, 가장 최신의 "스냅샷"을 읽어옴.
 *    - 스냅샷 생성 이후, 현재 "연산 큐"에 남아있는 나머지 연산들(최대 99개)만 스냅샷에 추가로 적용.
 *    - 이렇게 완성된 최종 결과물을 새로운 사용자에게 전송.
 *
 * --- 기대 효과 ---
 *
 * - 빠른 접속 속도: 새로운 사용자는 최대 99개의 연산만 처리하면 되므로 지연 없이 파일을 열 수 있음.
 * - 데이터 안정성: 주기적인 스냅샷 저장으로 서버 다운 시 데이터 유실 범위를 최소화.
 * - 효율적인 메모리 관리: 연산 큐가 무한정 커지지 않고 주기적으로 비워져 서버 부하 감소.
 * - 실시간 동기화: 브로드캐스트를 통해 모든 사용자가 변경 사항을 즉시 확인 가능.
 */
package com.ethis2s;

import java.io.PrintStream;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.util.DebugRedirectStream;
import com.ethis2s.util.MacosNativeUtil;
import com.ethis2s.util.WindowsNativeUtil;
import com.ethis2s.view.DebugView;

import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class App extends Application {

    private ClientSocketManager socketManager;
    private MainController mainController;

    @Override
    public void start(Stage primaryStage) {
        
        // Remove default window decorations
        final String OS = System.getProperty("os.name").toLowerCase();
        if (OS.contains("mac")) primaryStage.initStyle(StageStyle.UNIFIED);
         else if (OS.contains("win")) primaryStage.initStyle(StageStyle.TRANSPARENT);
        
        

        mainController = new MainController(primaryStage);
        
        // Pass the controller as the callback for socket events
        socketManager = new ClientSocketManager(mainController);
        
        // Inject the socket manager into the controller
        mainController.setSocketManager(socketManager);

        // Initialize the main UI
        mainController.initMainScreen();

        // Redirect console output to the DebugView
        DebugView debugView = mainController.getDebugView();
        if (debugView != null) {
            PrintStream outStream = new DebugRedirectStream(System.out, debugView);
            PrintStream errStream = new DebugRedirectStream(System.err, debugView);
            System.setOut(outStream);
            System.setErr(errStream);
        }
        
        // Start the socket connection in a new thread to avoid blocking the UI
        new Thread(() -> {
            try {
                socketManager.connect();
            } catch (Exception e) {
                // Handle connection error by triggering the reconnection logic
                e.printStackTrace();
                socketManager.initiateReconnection();
            }
        }).start();
    }

    @Override
    public void stop() throws Exception {
        if (mainController != null) {
            mainController.shutdown();
        }
        if (socketManager != null) {
            socketManager.disconnect();
        }
        super.stop();
    }

    public static void main(String[] args) {
        System.out.println("[PATH DEBUG] =========================================================");
        System.out.println("[PATH DEBUG] The application's Current Working Directory is:");
        System.out.println("[PATH DEBUG] " + System.getProperty("user.dir"));
        System.out.println("[PATH DEBUG] =========================================================");
        System.out.println("JavaFX Version: " + System.getProperty("javafx.version"));
        launch(args);
    }
}