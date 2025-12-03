package com.ethis2s;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Launcher {

    private static final String[] REQUIRED_OPENS = {
            "--add-opens=javafx.graphics/javafx.stage=ALL-UNNAMED",
            "--add-opens=javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
            "--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
            "--add-opens=javafx.stage/com.sun.javafx.stage=ALL-UNNAMED",
            "--add-opens=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED"
    };

    public static void main(String[] args) throws Exception {

        // 이미 옵션이 적용된 상태라면 그냥 App 실행
        if (Boolean.getBoolean("opens.applied")) {
            App.main(args);
            return;
        }

        // 아직이면 JVM을 옵션과 함께 재실행
        relaunchWithJvmOptions(args);
    }

    private static void relaunchWithJvmOptions(String[] args) throws Exception {
        String javaBin = System.getProperty("java.home")
                + File.separator + "bin" + File.separator + "java";

        // ★ 여기서 jar 파일을 올바른 OS 경로로 구함
        File jarFile = new File(
                Launcher.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()
        );

        // IDE에서 실행할 때는 디렉터리로 잡히니까 그냥 옵션만 세팅하고 App 실행
        if (jarFile.isDirectory()) {
            System.setProperty("opens.applied", "true");
            App.main(args);
            return;
        }

        // 윈도우면 예: F:\programing\... 이런 형태로 나옴 (앞에 / 안 붙음)
        String jarPath = jarFile.getAbsolutePath();

        List<String> command = new ArrayList<>();
        command.add(javaBin);

        // add-opens 옵션들
        for (String opt : REQUIRED_OPENS) {
            command.add(opt);
        }

        // 재실행 플래그
        command.add("-Dopens.applied=true");

        command.add("-jar");
        command.add(jarPath);

        // 기존 args 전달
        for (String a : args) {
            command.add(a);
        }

        new ProcessBuilder(command)
                .inheritIO()
                .start();

        System.exit(0);
    }
}
