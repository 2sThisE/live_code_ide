package com.ethis2s.service; // 또는 적절한 패키지

import javafx.application.Platform;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.ethis2s.model.LogType;

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
    public void execute(String command, String workingDirectoryPath, BiConsumer<LogType, String> onMessage) {
        // 이미 실행 중인 프로세스가 있다면 중지시킵니다.
        stopCurrentProcess();

        // UI가 멈추지 않도록 모든 작업을 백그라운드 스레드에서 처리합니다.
        new Thread(() -> {
            try {
                // 1. 작업 디렉토리 유효성 검사
                File workingDirectory = new File(workingDirectoryPath);
                if (!workingDirectory.exists() || !workingDirectory.isDirectory()) {
                    Platform.runLater(() -> onMessage.accept(LogType.STDERR, "Error: Working directory does not exist: " + workingDirectoryPath));
                    return;
                }

                // 2. 시스템 쉘을 통해 실행할 명령어 목록 생성
                // [수정] 변수명 일치 (commandToExecute -> command)
                List<String> commandList = buildShellCommand(command);
                
                // [수정] onOutput -> onMessage(STDOUT)
                Platform.runLater(() -> onMessage.accept(LogType.STDOUT, "Executing in " + workingDirectoryPath + ":\n$ " + command + "\n"));

                ProcessBuilder processBuilder = new ProcessBuilder(commandList);
                processBuilder.directory(workingDirectory);
                
                // 3. 프로세스 시작 및 스트림 연결
                currentProcess = processBuilder.start();
                Charset consoleCharset = getConsoleCharset();
                processInputWriter = new BufferedWriter(
                    new OutputStreamWriter(currentProcess.getOutputStream(), consoleCharset)
                );

                // [핵심 수정] 표준 출력 읽기 스레드 -> LogType.STDOUT 태그 부착
                outputReaderThread = new Thread(() -> readStream(
                    new InputStreamReader(currentProcess.getInputStream(), consoleCharset ), 
                    text -> onMessage.accept(LogType.STDOUT, text), // 성공 시 STDOUT
                    error -> onMessage.accept(LogType.STDERR, "[Stream Error] " + error) // 읽기 에러 시 STDERR
                ));
                outputReaderThread.start();
                
                // [핵심 수정] 표준 에러 읽기 스레드 -> LogType.STDERR 태그 부착
                errorReaderThread = new Thread(() -> readStream(
                    new InputStreamReader(currentProcess.getErrorStream(), consoleCharset ), 
                    text -> onMessage.accept(LogType.STDERR, text), // 성공 시 STDERR
                    error -> onMessage.accept(LogType.STDERR, "[Stream Error] " + error) // 읽기 에러 시 STDERR
                ));
                errorReaderThread.start();

                // 4. 프로세스 종료 대기
                int exitCode = currentProcess.waitFor();
                closeProcessResources(); 
                
                // 모든 출력이 처리될 시간을 잠시 기다림 (Join)
                if (outputReaderThread != null) outputReaderThread.join();
                if (errorReaderThread != null) errorReaderThread.join();
                
                closeProcessResources();
                
                // [수정] 종료 코드 출력 -> onMessage(STDOUT)
                Platform.runLater(() -> onMessage.accept(LogType.STDOUT, "\nProcess finished with exit code " + exitCode));

            } catch (Exception e) {
                closeProcessResources();
                // [수정] 예외 발생 -> onMessage(STDERR)
                Platform.runLater(() -> onMessage.accept(LogType.STDERR, "\nExecution failed: " + e.getMessage()));
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
                    String cleanInput = input.replace("\n", "").replace("\r", "");
                    processInputWriter.write(cleanInput);
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

    private void readStream(InputStreamReader streamReader, Consumer<String> onText, Consumer<String> onError) {
        try (BufferedReader reader = new BufferedReader(streamReader)) {
            char[] buffer = new char[1024]; // 1KB 단위로 읽음
            int readCount;
            
            // -1(EOF)이 아닐 때까지 계속 읽음
            while ((readCount = reader.read(buffer)) != -1 && !Thread.currentThread().isInterrupted()) {
                // 읽은 만큼만 문자열로 변환
                final String textChunk = new String(buffer, 0, readCount);
                
                Platform.runLater(() -> onText.accept(textChunk));
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

        if (currentProcess != null) {
            try {
                currentProcess.getInputStream().close();
            } catch (IOException e) { /* 무시 */ }
            
            try {
                currentProcess.getErrorStream().close();
            } catch (IOException e) { /* 무시 */ }
        }
        
        currentProcess = null;
        processInputWriter = null;
        outputReaderThread = null;
        errorReaderThread = null;
    }

    private Charset getConsoleCharset() {
        try {
            // 1. JVM이 부팅될 때 감지한 'OS 시스템 인코딩'을 가져옵니다.
            // (file.encoding이 UTF-8로 강제되어 있어도, 이 값은 실제 OS 환경을 따라갑니다)
            String systemEncoding = System.getProperty("sun.jnu.encoding");
            
            if (systemEncoding != null) {
                return Charset.forName(systemEncoding);
            }
        } catch (Exception e) {
            // 2. 만약 실패하면 자바 기본 인코딩을 따릅니다.
        }
        return Charset.defaultCharset();
    }
}