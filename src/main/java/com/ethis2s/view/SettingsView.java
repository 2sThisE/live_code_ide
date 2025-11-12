package com.ethis2s.view;

import com.ethis2s.util.ConfigManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class SettingsView extends Stage {

    private final ConfigManager configManager;
    private final Scene parentScene;

    public SettingsView(Stage owner) {
        this.configManager = ConfigManager.getInstance();
        this.parentScene = owner.getScene();

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("설정");

        VBox root = new VBox(20);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER_LEFT);

        // Theme selection
        Label themeLabel = new Label("에디터 테마");
        ComboBox<String> themeComboBox = new ComboBox<>();
        // TODO: Dynamically load theme names from the config folder
        themeComboBox.getItems().addAll("light-theme", "dark-theme"); // Placeholder
        themeComboBox.setValue(configManager.getCurrentTheme());
        
        HBox themeBox = new HBox(10, themeLabel, themeComboBox);
        themeBox.setAlignment(Pos.CENTER_LEFT);

        // Buttons
        Button saveButton = new Button("저장");
        saveButton.setOnAction(e -> {
            String selectedTheme = themeComboBox.getSelectionModel().getSelectedItem();
            configManager.setSetting("editor", "theme", selectedTheme);
            configManager.saveConfig();
            
            // Reload the main scene's stylesheets
            parentScene.getStylesheets().clear();
            String mainThemePath = configManager.getMainThemePath();
            String syntaxThemePath = configManager.getSyntaxThemePath();
            if (mainThemePath != null) {
                parentScene.getStylesheets().add(mainThemePath);
            }
            if (syntaxThemePath != null) {
                parentScene.getStylesheets().add(syntaxThemePath);
            }

            close();
        });

        Button cancelButton = new Button("취소");
        cancelButton.setOnAction(e -> close());

        HBox buttonBox = new HBox(10, saveButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(themeBox, buttonBox);

        Scene scene = new Scene(root, 350, 150);
        setScene(scene);
    }
}
