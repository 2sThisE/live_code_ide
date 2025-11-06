package com.example.view;

import com.example.controller.MainController;
import java.util.Arrays;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

public class LoginScreen {

    private Label actiontarget;

    public GridPane createLoginView(MainController mainController) {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(25, 25, 25, 25));

        Label scenetitle = new Label("환영합니다");
        scenetitle.setId("welcome-text");
        grid.add(scenetitle, 0, 0, 2, 1);

        Label userName = new Label("아이디:");
        grid.add(userName, 0, 1);

        TextField userTextField = new TextField();
        grid.add(userTextField, 1, 1);

        Label pw = new Label("비밀번호:");
        grid.add(pw, 0, 2);

        PasswordField pwBox = new PasswordField();
        grid.add(pwBox, 1, 2);

        Button loginBtn = new Button("로그인");
        grid.add(loginBtn, 1, 3);

        pwBox.setOnAction(e -> loginBtn.fire());
        
        Button registerBtn = new Button("회원가입");
        grid.add(registerBtn, 0, 3);

        actiontarget = new Label();
        actiontarget.setId("LoginResult");
        actiontarget.setStyle("-fx-text-fill: #ff5555;");
        grid.add(actiontarget, 0, 4, 2, 1);

        loginBtn.setOnAction(e -> {
            String id = userTextField.getText();
            // Use getText() which returns a String, then convert to char[]
            char[] password = pwBox.getText().toCharArray();

            if (id.isEmpty() || password.length == 0) {
                actiontarget.setText("아이디와 비밀번호를 입력해주세요.");
                return;
            }

            actiontarget.setText("로그인 중...");
            mainController.performLogin(id, password);

            // Clear the char array after passing it
            Arrays.fill(password, '0');
        });

        registerBtn.setOnAction(e -> mainController.showRegisterView());

        return grid;
    }

    public void updateLoginStatus(int responseCode) {
        Platform.runLater(() -> {
            switch (responseCode) {
                case 1:
                    actiontarget.setText("로그인 성공! 사용자 정보 가져오는 중...");
                    break;
                case 0:
                    actiontarget.setText("로그인 실패: 잘못된 아이디 또는 비밀번호.");
                    break;
                default:
                    actiontarget.setText("로그인 실패: 서버와 통신할 수 없습니다.");
                    break;
            }
        });
    }
}
