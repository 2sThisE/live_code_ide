package com.example;

import com.example.controller.MainController;
import com.example.service.ClientSocketManager;

import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.application.Platform;

public class App extends Application {

    private ClientSocketManager socketManager;

    @Override
    public void start(Stage primaryStage) {
        
        // Remove default window decorations
        primaryStage.initStyle(StageStyle.TRANSPARENT);

        MainController mainController = new MainController(primaryStage);
        
        // Pass the controller as the callback for socket events
        socketManager = new ClientSocketManager(mainController);
        
        // Inject the socket manager into the controller
        mainController.setSocketManager(socketManager);

        // Initialize the main UI
        mainController.initMainScreen();
        
        

        // Start the socket connection in a background thread
        new Thread(() -> {
            try {
                socketManager.connect();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    // Proper error handling, maybe show an alert
                    System.err.println("Failed to connect to server: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        }).start();
    }

    @Override
    public void stop() throws Exception {
        if (socketManager != null) {
            socketManager.disconnect();
        }
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
