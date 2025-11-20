package com.ethis2s.view;
import com.ethis2s.util.MacosNativeUtil;
import com.ethis2s.util.WindowsNativeUtil;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import org.json.JSONObject;

public class CustomAlert {

    private final Stage owner;
    private String title;
    private final String header;
    private String context;
    private final Runnable onOk;
    private Runnable onCancel;

    private double xOffset = 0;
    private double yOffset = 0;

    public CustomAlert(Stage owner, String title, String header, String context, Runnable onOk) {
        this.owner = owner;
        this.title = title;
        this.header = header;
        this.context = context;
        this.onOk = onOk;
        this.onCancel = null;
    }

    public CustomAlert(Stage owner, String title, String header, String context, Runnable onConfirm, Runnable onCancel) {
        this.owner = owner;
        this.title = title;
        this.header = header;
        this.context = context;
        this.onOk = onConfirm;
        this.onCancel = onCancel;
    }

    public void setTitle(String title){this.title=title;}
    public void setContext(String context){this.context=context;}

    private void initScreen(boolean opt){
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.initOwner(owner);
            stage.initModality(Modality.APPLICATION_MODAL);
            
            final String OS = System.getProperty("os.name").toLowerCase();
            if (OS.contains("mac")) stage.initStyle(StageStyle.UNIFIED);
            else if (OS.contains("win")) stage.initStyle(StageStyle.TRANSPARENT);
            else stage.initStyle(StageStyle.UNDECORATED);

            stage.setResizable(false);

            List<Node> nonDraggableNodes = new ArrayList<>();

            // --- Custom Title Bar ---
            Label titleLabel = new Label("  " + title);
            titleLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 14px;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            
            HBox titleBar = new HBox(titleLabel, spacer);
            titleBar.setAlignment(Pos.CENTER_LEFT);
            titleBar.setPadding(new Insets(8));
            titleBar.setStyle("-fx-background-color: #2a2a2a;");

            // --- Draggable & Double-click prevention Logic ---
            titleBar.setOnMousePressed(event -> {
                if (event.getClickCount() == 2) {
                    event.consume();
                    return;
                }
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            titleBar.setOnMouseDragged(event -> {
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            });
            titleBar.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    event.consume();
                }
            });

            Runnable closeAction = () -> {
                if (onCancel != null) {
                    onCancel.run();
                }
                stage.close();
            };

            if (OS.contains("win")) {
                Button closeButton = new Button("✕");
                closeButton.setStyle(
                    "-fx-background-color: transparent; -fx-text-fill: #e0e0e0; -fx-font-size: 14px; -fx-padding: 0 10;"
                );
                closeButton.setOnMouseEntered(e -> closeButton.setStyle("-fx-background-color: #c04040; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 0 10;"));
                closeButton.setOnMouseExited(e -> closeButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #e0e0e0; -fx-font-size: 14px; -fx-padding: 0 10;"));
                closeButton.setOnAction(e -> closeAction.run());
                titleBar.getChildren().add(closeButton);
                nonDraggableNodes.add(closeButton);
            }
            
            stage.setOnCloseRequest(e -> {
                e.consume();
                closeAction.run();
            });


            // --- Content Area ---
            VBox contentPane = new VBox(15);
            contentPane.setPadding(new Insets(20));
            contentPane.setStyle("-fx-background-color: #1e1e1e;");

            Label headerLabel = new Label(header);
            headerLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px; -fx-font-weight: bold;");
            headerLabel.setWrapText(true);

            Label contextLabel = new Label(context);
            contextLabel.setStyle("-fx-text-fill: #d4d4d4; -fx-font-size: 14px;");
            contextLabel.setWrapText(true);

            // --- Buttons ---
            HBox buttonBox = new HBox(10);
            buttonBox.setAlignment(Pos.CENTER_RIGHT);

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
            nonDraggableNodes.add(okButton);

            if (onCancel != null) {
                Button cancelButton = new Button("취소");
                cancelButton.setStyle(
                        "-fx-background-color: #333333; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-background-radius: 5;"
                );
                cancelButton.setOnAction(e -> closeAction.run());
                buttonBox.getChildren().add(cancelButton);
                nonDraggableNodes.add(cancelButton);
            }
            buttonBox.getChildren().add(okButton);

            contentPane.getChildren().addAll(headerLabel, contextLabel, buttonBox);

            // --- Root Layout ---
            VBox root = new VBox(titleBar, contentPane);
            root.setStyle("-fx-border-color: #444444; -fx-border-width: 1; -fx-background-radius: 10; -fx-border-radius: 10;");

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);

            stage.setOnShown(e -> {
                if (OS.contains("mac")) {
                    MacosNativeUtil.applyUnifiedTitleBarStyle(stage);
                } else if (OS.contains("win")) {
                    WindowsNativeUtil.applyCustomAlertWindowStyle(stage, titleBar, nonDraggableNodes);
                }
            });

            if(opt) stage.show();
            else stage.showAndWait();
        });
    }
    public void show() {initScreen(true);}
    public void showAndWait() {initScreen(false);}
}
