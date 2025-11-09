package com.ethis2s;

import com.ethis2s.controller.MainController;
import com.ethis2s.service.ClientSocketManager;
import com.ethis2s.util.DebugRedirectStream;
import com.ethis2s.view.DebugView;
import java.io.PrintStream;
import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

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
        System.out.println("[PATH DEBUG] =========================================================");
        System.out.println("[PATH DEBUG] The application's Current Working Directory is:");
        System.out.println("[PATH DEBUG] " + System.getProperty("user.dir"));
        System.out.println("[PATH DEBUG] =========================================================");
        System.out.println("JavaFX Version: " + System.getProperty("javafx.version"));
        launch(args);
    }
}
