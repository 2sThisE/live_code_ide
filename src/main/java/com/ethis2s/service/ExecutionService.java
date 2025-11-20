package com.ethis2s.service; // 또는 적절한 패키지

import javafx.application.Platform;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * [최종본] 외부 프로세스를 실행하고, 표준 입/출력/에러를 중계하는 서비스입니다.
 * 이 서비스는 완전히 해석된 명령어와 작업 디렉토리만 받아서 실행하는 역할에 집중합니다.
 */
public class ExecutionService {

    private Process currentProcess;
    private BufferedWriter processInputWriter;
    private Thread outputReaderThread;
    private Thread errorReaderThread;

    /**
     * [수정] 주어진 명령어를 지정된 작업 디렉토리에서 비동기적으로 실행합니다.
     * @param commandToExecute 완전히 해석된, 시스템 쉘에서 실행할 최종 명령어 문자열
     * @param workingDirectoryPath 명령을 실행할 디렉토리의 절대 경로
     * @param onOutput 표준 출력을 라인 단위로 받는 Consumer
     * @param onError 표준 에러를 라인 단위로 받는 Consumer
     */
    public void execute(String commandToExecute, String workingDirectoryPath, Consumer<String> onOutput, Consumer<String> onError) {
        // 이미 실행 중인 프로세스가 있다면 중지시킵니다.
        stopCurrentProcess();

        // UI가 멈추지 않도록 모든 작업을 백그라운드 스레드에서 처리합니다.
        new Thread(() -> {
            try {
                // 1. 작업 디렉토리 유효성 검사
                File workingDirectory = new File(workingDirectoryPath);
                if (!workingDirectory.exists() || !workingDirectory.isDirectory()) {
                    Platform.runLater(() -> onError.accept("Error: Working directory does not exist or is not a directory: " + workingDirectoryPath));
                    return;
                }

                // 2. 시스템 쉘을 통해 실행할 명령어 목록 생성
                List<String> commandList = buildShellCommand(commandToExecute);
                
                Platform.runLater(() -> onOutput.accept("Executing in " + workingDirectoryPath + ":\n$ " + commandToExecute + "\n"));

                ProcessBuilder processBuilder = new ProcessBuilder(commandList);
                processBuilder.directory(workingDirectory);
                
                // 3. 프로세스 시작 및 스트림 연결
                currentProcess = processBuilder.start();
                processInputWriter = new BufferedWriter(new OutputStreamWriter(currentProcess.getOutputStream(), StandardCharsets.UTF_8));

                // 표준 출력 읽기 시작 (UTF-8로 인코딩 명시)
                outputReaderThread = new Thread(() -> readStream(new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8), onOutput, onError));
                outputReaderThread.start();
                
                // 표준 에러 읽기 시작 (UTF-8로 인코딩 명시)
                errorReaderThread = new Thread(() -> readStream(new InputStreamReader(currentProcess.getErrorStream(), StandardCharsets.UTF_8), onError, onError));
                errorReaderThread.start();

                // 4. 프로세스 종료 대기
                int exitCode = currentProcess.waitFor();
                
                // 모든 출력이 처리될 시간을 잠시 기다림 (Join)
                if (outputReaderThread != null) outputReaderThread.join();
                if (errorReaderThread != null) errorReaderThread.join();
                
                closeProcessResources();
                Platform.runLater(() -> onOutput.accept("\nProcess finished with exit code " + exitCode));

            } catch (Exception e) {
                closeProcessResources();
                Platform.runLater(() -> onError.accept("\nExecution failed: " + e.getMessage()));
            }
        }).start();
    }
    
    // --- 이하 모든 다른 메서드 (sendInputToProcess, stopCurrentProcess 등)는 변경할 필요가 없습니다. ---
    
    /**
     * 실행 중인 프로세스에 사용자 입력을 전송합니다.
     * @param input 전송할 문자열
     */
    public void sendInputToProcess(String input) {
        if (processInputWriter != null) {
            new Thread(() -> {
                try {
                    processInputWriter.write(input);
                    processInputWriter.newLine();
                    processInputWriter.flush();
                } catch (IOException e) {
                    System.err.println("Failed to send input to process (it may have terminated): " + e.getMessage());
                }
            }).start();
        }
    }

    /**
     * 현재 실행 중인 프로세스를 강제로 종료합니다.
     */
    public void stopCurrentProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
        }
        if(outputReaderThread != null) outputReaderThread.interrupt();
        if(errorReaderThread != null) errorReaderThread.interrupt();
        
        // closeProcessResources()는 waitFor() 이후 또는 에러 발생 시 호출되므로,
        // 여기서는 프로세스 종료만 담당하는 것이 더 안전할 수 있습니다.
        // 하지만 중복 호출되어도 문제는 없습니다.
        closeProcessResources();
        Platform.runLater(() -> System.out.println("Process stopped by user."));
    }

    private void readStream(InputStreamReader streamReader, Consumer<String> onLine, Consumer<String> onError) {
        try (BufferedReader reader = new BufferedReader(streamReader)) {
            String line;
            while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                final String finalLine = line;
                Platform.runLater(() -> onLine.accept(finalLine));
            }
        } catch (IOException e) {
            if (!e.getMessage().contains("Stream closed")) {
                 Platform.runLater(() -> onError.accept("Error reading stream: " + e.getMessage()));
            }
        }
    }

    private List<String> buildShellCommand(String command) {
        List<String> commandList = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            commandList.add("cmd.exe");
            commandList.add("/c");
        } else {
            commandList.add("/bin/sh");
            commandList.add("-c");
        }
        commandList.add(command);
        return commandList;
    }

    private void closeProcessResources() {
        try {
            if (processInputWriter != null) {
                processInputWriter.close();
            }
        } catch (IOException e) { /* 무시 */ }
        
        currentProcess = null;
        processInputWriter = null;
        outputReaderThread = null;
        errorReaderThread = null;
    }
}