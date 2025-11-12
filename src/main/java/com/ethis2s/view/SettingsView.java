package com.ethis2s.view;

import com.ethis2s.util.ConfigManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettingsView {

    private final Runnable onSave;
    private final Runnable onClose;
    private final ConfigManager configManager;
    private final Map<String, Map<String, TextField>> featureFields = new HashMap<>();
    private final Map<String, Node> settingsPanes = new HashMap<>();
    private final Map<String, Button> featureButtons = new HashMap<>();

    public SettingsView(Runnable onSave, Runnable onClose) {
        this.onSave = onSave;
        this.onClose = onClose;
        this.configManager = ConfigManager.getInstance();
    }

    public Node createView() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("settings-view");

        VBox leftMenu = new VBox();
        leftMenu.getStyleClass().add("settings-left-menu");
        leftMenu.setSpacing(5);

        StackPane contentArea = new StackPane();
        contentArea.getStyleClass().add("settings-content-area");
        contentArea.setPadding(new Insets(20));

        List<Map<String, Object>> features = configManager.getAllFeatures();
        if (features != null) {
            for (Map<String, Object> featureMap : features) {
                String featureName = (String) featureMap.get("feature");
                @SuppressWarnings("unchecked")
                Map<String, Object> settings = (Map<String, Object>) featureMap.get("settings");

                if (featureName == null || settings == null) continue;

                GridPane settingsGrid = createSettingsGrid(featureName, settings);
                settingsPanes.put(featureName, settingsGrid);
                contentArea.getChildren().add(settingsGrid);

                Button featureButton = new Button(featureName);
                featureButton.getStyleClass().add("settings-menu-button");
                featureButton.setOnAction(e -> selectFeature(featureName));
                featureButtons.put(featureName, featureButton);
                leftMenu.getChildren().add(featureButton);
            }
        }

        Button saveButton = new Button("저장");
        saveButton.setOnAction(e -> {
            saveSettings();
            if (onSave != null) onSave.run();
            if (onClose != null) onClose.run();
        });

        Button cancelButton = new Button("취소");
        cancelButton.setOnAction(e -> {
            if (onClose != null) onClose.run();
        });

        HBox buttonBox = new HBox(10, saveButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getStyleClass().add("settings-button-bar");
        buttonBox.setPadding(new Insets(10));

        // --- Assemble the view ---
        BorderPane centerLayout = new BorderPane();
        centerLayout.setCenter(new ScrollPane(contentArea));
        centerLayout.setBottom(buttonBox);

        root.setLeft(leftMenu);
        root.setCenter(centerLayout);

        // --- Set initial state ---
        if (features != null && !features.isEmpty()) {
            selectFeature((String) features.get(0).get("feature"));
        }

        return root;
    }

    private void selectFeature(String featureName) {
        for (Map.Entry<String, Node> entry : settingsPanes.entrySet()) {
            entry.getValue().setVisible(entry.getKey().equals(featureName));
        }
        for (Map.Entry<String, Button> entry : featureButtons.entrySet()) {
            if (entry.getKey().equals(featureName)) {
                if (!entry.getValue().getStyleClass().contains("active")) {
                    entry.getValue().getStyleClass().add("active");
                }
            } else {
                entry.getValue().getStyleClass().remove("active");
            }
        }
    }

    private GridPane createSettingsGrid(String featureName, Map<String, Object> settings) {
        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(10);
        settingsGrid.setVgap(10);
        settingsGrid.setPadding(new Insets(10));

        Map<String, TextField> settingFields = new HashMap<>();
        featureFields.put(featureName, settingFields);

        int rowIndex = 0;
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().toString();

            Label label = new Label(key);
            TextField textField = new TextField(value);
            settingFields.put(key, textField);

            settingsGrid.add(label, 0, rowIndex);
            settingsGrid.add(textField, 1, rowIndex);
            rowIndex++;
        }
        return settingsGrid;
    }

    private void saveSettings() {
        for (Map.Entry<String, Map<String, TextField>> featureEntry : featureFields.entrySet()) {
            String featureName = featureEntry.getKey();
            Map<String, TextField> fields = featureEntry.getValue();

            for (Map.Entry<String, TextField> fieldEntry : fields.entrySet()) {
                String key = fieldEntry.getKey();
                String stringValue = fieldEntry.getValue().getText();
                
                try {
                    double numValue = Double.parseDouble(stringValue);
                    configManager.setSetting(featureName, key, numValue);
                } catch (NumberFormatException ex) {
                    configManager.setSetting(featureName, key, stringValue);
                }
            }
        }
        configManager.saveConfig();
        System.out.println("Settings saved to config.json!");
    }
}