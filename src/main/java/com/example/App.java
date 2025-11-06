package com.example;

import com.example.controller.MainController;
import com.example.service.ClientSocketManager;

import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.application.Platform;

public class App extends Application {

    private ClientSocketManager socketManager;
    private MainController mainController;

    @Override
    public void start(Stage primaryStage) {
        
        // Remove default window decorations
        primaryStage.initStyle(StageStyle.TRANSPARENT);

        mainController = new MainController(primaryStage);
        
        // Pass the controller as the callback for socket events
        socketManager = new ClientSocketManager(mainController);
        
        // Inject the socket manager into the controller
        mainController.setSocketManager(socketManager);

        // Initialize the main UI
        mainController.initMainScreen();
        
        // Start the socket connection in a new thread to avoid blocking the UI
        new Thread(() -> {
            try {
                socketManager.connect();
            } catch (Exception e) {
                // Handle connection error, e.g., show an error message
                e.printStackTrace();
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
        System.out.println("JavaFX Version: " + System.getProperty("javafx.version"));
        launch(args);
    }
}
