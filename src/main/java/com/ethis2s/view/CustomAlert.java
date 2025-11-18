package com.ethis2s.view;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Map;
import com.google.gson.Gson;
import org.json.JSONObject;

public class CustomAlert {

    private final Stage owner;
    private String title;
    private final String header;
    private String context;
    private final Runnable onOk;

    /**
     * 생성자
     *
     * @param owner     부모 Stage
     * @param title     Alert 제목
     * @param header    헤더 메시지
     * @param context   설명 
     * @param onOk      OK 버튼 클릭 시 실행할 Runnable
     */
    public CustomAlert(Stage owner, String title, String header, String context, Runnable onOk) {
        this.owner = owner;
        this.title = title;
        this.header = header;
        this.context = context;
        this.onOk = onOk;
    }

    public void setTitle(String title){this.title=title;}
    public void setContext(String context){this.context=context;}
    /**
     * Alert 표시
     */
    private void initScreen(boolean opt){
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.initOwner(owner);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setTitle(title);

            VBox root = new VBox(15);
            root.setPadding(new Insets(20));
            root.setStyle("-fx-background-color: #1e1e1e; -fx-background-radius: 10;");

            // 헤더
            Label headerLabel = new Label(header);
            headerLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px; -fx-font-weight: bold;");


            Label contentLabel = new Label(context);
            contentLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 14px;");

            // 확인 버튼
            Button okButton = new Button("확인");
            okButton.setStyle(
                    "-fx-background-color: #0078D4; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-size: 14px; " +
                    "-fx-background-radius: 5;"
            );
            okButton.setOnAction(e -> {
                if (onOk != null) onOk.run();
                stage.close();
            });

            HBox buttonBox = new HBox(okButton);
            buttonBox.setAlignment(Pos.CENTER_RIGHT);

            root.getChildren().addAll(headerLabel, contentLabel, buttonBox);

            Scene scene = new Scene(root);
            stage.setScene(scene);
            if(opt) stage.show();
            else stage.showAndWait();
        });
    }
    public void show() {initScreen(true);}
    public void showAndWait() {initScreen(false);}
}
