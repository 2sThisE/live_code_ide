package com.ethis2s.view;

import com.ethis2s.util.ConfigManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SettingsView {

    private final Runnable onSave;
    private final Runnable onClose;
    private final ConfigManager configManager;

    // [수정] featureFields와 settingsPanes는 그대로 유지
    private final Map<String, Map<String, TextField>> featureFields = new HashMap<>();
    private final Map<String, Node> settingsPanes = new HashMap<>();

    // [내부 데이터 모델 클래스 추가]
    private static class FeatureItem {
        private final StringProperty key = new SimpleStringProperty();
        private final StringProperty label = new SimpleStringProperty();

        public FeatureItem(String key, String label) {
            this.key.set(key);
            this.label.set(label);
        }

        public String getKey() { return key.get(); }
        public String getLabel() { return label.get(); }

        @Override
        public String toString() {
            // ListView가 기본적으로 이 값을 셀에 표시하려고 함
            return getLabel();
        }
    }

    public SettingsView(Runnable onSave, Runnable onClose) {
        this.onSave = onSave;
        this.onClose = onClose;
        this.configManager = ConfigManager.getInstance();
    }

    public Node createView() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("settings-view");

        // --- [핵심 수정] VBox와 Button 대신 ListView 사용 ---
        ListView<FeatureItem> leftMenu = new ListView<>();
        leftMenu.getStyleClass().add("settings-menu-list");

        StackPane contentArea = new StackPane();
        contentArea.getStyleClass().add("settings-content-area");
        
        ObservableList<FeatureItem> featureItems = FXCollections.observableArrayList();

        List<Map<String, Object>> features = configManager.getConfigData();
        if (features != null) {
            for (Map<String, Object> featureMap : features) {
                String featureKey = (String) featureMap.get("featureKey");
                String featureLabel = (String) featureMap.get("featureLabel");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> settings = (List<Map<String, Object>>) featureMap.get("settings");

                if (featureKey == null || featureLabel == null || settings == null) continue;

                // ListView에 추가할 데이터 객체 생성
                featureItems.add(new FeatureItem(featureKey, featureLabel));
                
                Node settingsPane = createSettingsPane(featureKey, settings);
                settingsPanes.put(featureKey, settingsPane);
                contentArea.getChildren().add(settingsPane);
            }
        }
        
        leftMenu.setItems(featureItems);
        
        // ListView의 선택 변경 리스너 설정
        leftMenu.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                selectFeature(newSelection.getKey());
            }
        });
        // ---------------------------------------------------

        Button saveButton = new Button("저장");
        saveButton.setOnAction(e -> {
            saveSettings();
            if (onSave != null) onSave.run();
            if (onClose != null) onClose.run();
        });
        Button cancelButton = new Button("취소");
        cancelButton.setOnAction(e -> { if (onClose != null) onClose.run(); });
        HBox buttonBox = new HBox(10, saveButton, cancelButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getStyleClass().add("settings-button-bar");

        ScrollPane scrollableContent = new ScrollPane(contentArea);
        scrollableContent.setFitToWidth(true);

        BorderPane centerLayout = new BorderPane();
        centerLayout.setCenter(scrollableContent);
        centerLayout.setBottom(buttonBox);
        
        // [수정] root.setLeft에 ListView를 담은 컨테이너를 설정
        VBox leftMenuContainer = new VBox(leftMenu);
        leftMenuContainer.getStyleClass().add("settings-left-menu");
        VBox.setVgrow(leftMenu, javafx.scene.layout.Priority.ALWAYS); // ListView가 수직으로 꽉 차도록

        root.setLeft(leftMenuContainer);
        root.setCenter(centerLayout);

        // 초기 선택 설정
        if (!featureItems.isEmpty()) {
            leftMenu.getSelectionModel().selectFirst();
        }

        return root;
    }
    
    // [수정] selectFeature 메소드를 더 단순하게 변경
    private void selectFeature(String featureKey) {
        // 선택된 featureKey에 해당하는 Pane만 보이도록 함
        for (Map.Entry<String, Node> entry : settingsPanes.entrySet()) {
            entry.getValue().setVisible(entry.getKey().equals(featureKey));
        }
        // 버튼 active 클래스 제어 로직은 이제 ListView가 자동으로 처리하므로 필요 없음
    }

    // --- 나머지 메소드들은 기존과 동일하게 유지 ---
    private VBox createSettingsPane(String featureKey, List<Map<String, Object>> settings) {
        VBox settingsContainer = new VBox();
        settingsContainer.setSpacing(5);
        Map<String, TextField> settingFields = new HashMap<>();
        featureFields.put(featureKey, settingFields);
        for (Map<String, Object> settingMap : settings) {
            Node settingItem = createSettingItem(settingMap, settingFields);
            settingsContainer.getChildren().add(settingItem);
        }
        return settingsContainer;
    }
    private Node createSettingItem(Map<String, Object> settingMap, Map<String, TextField> fieldsMap) {
        String key = (String) settingMap.get("key");
        String labelText = (String) settingMap.get("label");
        String descriptionText = (String) settingMap.get("description");
        String value = settingMap.get("value").toString();
        Label label = new Label(labelText);
        label.getStyleClass().add("setting-item-label");
        TextField textField = new TextField(value);
        textField.getStyleClass().add("setting-item-textfield");
        fieldsMap.put(key, textField);
        HBox controlBox = new HBox(label, textField);
        controlBox.getStyleClass().add("setting-item-control-box");
        Label description = new Label(descriptionText);
        description.getStyleClass().add("setting-item-description");
        description.setWrapText(true);
        VBox itemContainer = new VBox(5, controlBox, description);
        itemContainer.getStyleClass().add("setting-item");
        return itemContainer;
    }
    private void saveSettings() {
        for (Map.Entry<String, Map<String, TextField>> featureEntry : featureFields.entrySet()) {
            String featureKey = featureEntry.getKey();
            Map<String, TextField> fields = featureEntry.getValue();
            for (Map.Entry<String, TextField> fieldEntry : fields.entrySet()) {
                String settingKey = fieldEntry.getKey();
                String stringValue = fieldEntry.getValue().getText();
                try {
                    Object originalValue = configManager.get(featureKey, settingKey, Object.class, stringValue);
                    if (originalValue instanceof Number) {
                        configManager.set(featureKey, settingKey, Double.parseDouble(stringValue));
                    } else {
                        configManager.set(featureKey, settingKey, stringValue);
                    }
                } catch (NumberFormatException ex) {
                    configManager.set(featureKey, settingKey, stringValue);
                }
            }
        }
        configManager.saveConfig();
    }
}