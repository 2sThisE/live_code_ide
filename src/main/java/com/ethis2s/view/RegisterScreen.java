package com.ethis2s.view;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.util.ProtocolConstants;
import java.nio.charset.StandardCharsets;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

public class RegisterScreen {

    private Text actiontarget;
    private String passwordStrength = "약함";

    public GridPane createRegisterView(ClientSocketManager socketManager, MainController mainController) {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(25, 25, 25, 25));

        Text scenetitle = new Text("회원가입");
        scenetitle.setId("welcome-text");
        grid.add(scenetitle, 0, 0, 2, 1);

        // --- ID Field ---
        Label userName = new Label("아이디:");
        grid.add(userName, 0, 1);
        TextField userTextField = new TextField();
        userTextField.textProperty().addListener((obs, oldText, newText) -> {
            if (!newText.matches("[a-zA-Z0-9]*")) {
                userTextField.setText(oldText);
            }
        });
        grid.add(userTextField, 1, 1);

        // --- Nickname Field ---
        Label nicknameLabel = new Label("닉네임:");
        grid.add(nicknameLabel, 0, 2);
        TextField nicknameField = new TextField();
        nicknameField.textProperty().addListener((obs, oldText, newText) -> {
            if (!newText.matches("[a-zA-Z0-9_-]*")) {
                nicknameField.setText(oldText);
            }
        });
        grid.add(nicknameField, 1, 2);

        // --- Tag Field ---
        Label tagLabel = new Label("태그 (4-6자리 숫자):");
        grid.add(tagLabel, 0, 3);
        TextField tagField = new TextField();
        tagField.textProperty().addListener((obs, oldText, newText) -> {
            if (!newText.matches("\\d*")) { // Only allow digits
                tagField.setText(oldText);
            }
        });
        grid.add(tagField, 1, 3);

        // --- Password Field ---
        Label pw = new Label("비밀번호:");
        grid.add(pw, 0, 4);
        PasswordField pwBox = new PasswordField();
        grid.add(pwBox, 1, 4);

        Label strengthLabel = new Label("복잡도: 약함");
        strengthLabel.setVisible(false);
        HBox strengthBox = new HBox(strengthLabel);
        // strengthBox.setAlignment(Pos.CENTER_RIGHT);
        grid.add(strengthBox, 2, 4);

        pwBox.textProperty().addListener((obs, oldText, newText) -> {
            strengthLabel.setVisible(true);
            passwordStrength = checkPasswordStrength(newText);
            strengthLabel.setText("복잡도: " + passwordStrength);
            switch (passwordStrength) {
                case "강함":
                    strengthLabel.setStyle("-fx-text-fill: #55ff55;");
                    break;
                case "중간":
                    strengthLabel.setStyle("-fx-text-fill: #ffff55;");
                    break;
                default:
                    strengthLabel.setStyle("-fx-text-fill: #ff5555;");
                    break;
            }
        });

        // --- Password Confirm Field ---
        Label pwConfirm = new Label("비밀번호 확인:");
        grid.add(pwConfirm, 0, 5);
        PasswordField pwConfirmBox = new PasswordField();
        grid.add(pwConfirmBox, 1, 5);

        // --- Buttons and Action Target ---
        Button registerBtn = new Button("회원가입");
        Button backBtn = new Button("뒤로가기");

        HBox buttonBox = new HBox(10); // 10px spacing
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().addAll(backBtn, registerBtn);
        grid.add(buttonBox, 0, 7, 2, 1);

        actiontarget = new Text();
        grid.add(actiontarget, 0, 8, 2, 1);
        actiontarget.setStyle("-fx-text-fill: #ff5555;");

        registerBtn.setOnAction(e -> {
            String username = userTextField.getText();
            String nickname = nicknameField.getText();
            String tag = tagField.getText();
            String password = pwBox.getText();
            String confirmPassword = pwConfirmBox.getText();

            if (username.isEmpty() || nickname.isEmpty() || tag.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                actiontarget.setText("모든 필드를 입력해주세요.");
                return;
            }
            if (!password.equals(confirmPassword)) {
                actiontarget.setText("비밀번호가 일치하지 않습니다.");
                return;
            }
            if (!tag.matches("^\\d{4,6}$")) {
                actiontarget.setText("태그는 4-6자리 숫자여야 합니다.");
                return;
            }
            if ("약함".equals(passwordStrength)) {
                actiontarget.setText("비밀번호의 복잡도가 너무 낮습니다.");
                return;
            }

            actiontarget.setText("회원가입 중...");
            String registerPayload = String.format("{\"id\":\"%s\", \"nickname\":\"%s\", \"tag\":\"%s\", \"password\":\"%s\"}", username, nickname, tag, password);
            byte[] registerPayloadBytes = registerPayload.getBytes(StandardCharsets.UTF_8);

            new Thread(() -> {
                try {
                    socketManager.sendPacket(
                            registerPayloadBytes,
                            ProtocolConstants.UNFRAGED,
                            ProtocolConstants.UF_REGISTER_REQUEST,
                            ProtocolConstants.PTYPE_JSON
                    );
                } catch (Exception ex) {
                    Platform.runLater(() -> actiontarget.setText("회원가입 실패: " + ex.getMessage()));
                }
            }).start();
        });
        
        backBtn.setOnAction(e -> mainController.showLoginView());

        return grid;
    }

    public void updateRegistrationStatus(int responseCode, MainController mainController) {
        Platform.runLater(() -> {
            switch (responseCode) {
                case 0:
                    actiontarget.setText("회원가입 성공! 로그인 화면으로 돌아갑니다.");
                    actiontarget.setStyle("-fx-text-fill: #55ff55;");
                    new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                            Platform.runLater(mainController::showLoginView);
                        } catch (InterruptedException e) {}
                    }).start();
                    break;
                case 1:
                    actiontarget.setText("실패: 닉네임과 태그 조합이 이미 존재합니다.");
                    actiontarget.setStyle("-fx-text-fill: #ff5555;");
                    break;
                case 2:
                    actiontarget.setText("실패: 아이디가 이미 존재합니다.");
                    actiontarget.setStyle("-fx-text-fill: #ff5555;");
                    break;
                default:
                    actiontarget.setText("실패: 기타 서버 오류.");
                    actiontarget.setStyle("-fx-text-fill: #ff5555;");
                    break;
            }
        });
    }

    private String checkPasswordStrength(String password) {
        int score = 0;
        if (password.length() >= 8) score++;
        if (password.matches(".*[a-z].*")) score++;
        if (password.matches(".*[A-Z].*")) score++;
        if (password.matches(".*[0-9].*")) score++;
        if (password.matches(".*[^a-zA-Z0-9].*")) score++;
        
        if (score >= 5) return "강함";
        if (score >= 3) return "중간";
        return "약함";
    }
}