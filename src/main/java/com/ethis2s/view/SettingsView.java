package com.ethis2s.view;

import com.ethis2s.util.ConfigManager;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class SettingsView {

    private final Runnable onSave;
    private final Runnable onClose;
    private final ConfigManager configManager;

    private final Map<String, Node> settingsPanes = new HashMap<>();
    private final Map<String, Map<String, Node>> featureFields = new HashMap<>();
    private ListView<FeatureItem> leftMenu;
    
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
        this.leftMenu = new ListView<>();
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
            boolean success = saveSettings();
            if (success) {
                if (onSave != null) onSave.run();
                if (onClose != null) onClose.run();
            }
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
        VBox settingsContainer = new VBox(15);
        settingsContainer.getStyleClass().add("settings-pane");
        
        // 이 feature에 속한 UI 컨트롤들을 저장할 맵
        Map<String, Node> settingControls = new HashMap<>();
        featureFields.put(featureKey, settingControls);

        for (Map<String, Object> settingMap : settings) {
            Object value = settingMap.get("value");
            Node settingItem;
            
            // value의 타입에 따라 다른 UI를 생성합니다.
            if (value instanceof String) {
                settingItem = createStringSettingItem(settingMap, settingControls);
            } else if (value instanceof Map) {
                settingItem = createMapSettingItem(settingMap, settingControls);
            } else {
                // 지원하지 않는 타입은 건너뛰거나, toString()으로 표시
                settingItem = createStringSettingItem(settingMap, settingControls);
            }
            settingsContainer.getChildren().add(settingItem);
        }
        return settingsContainer;
    }
    // private Node createStringSettingItem(Map<String, Object> settingMap, Map<String, TextField> fieldsMap) {
    //     String key = (String) settingMap.get("key");
    //     String labelText = (String) settingMap.get("label");
    //     String descriptionText = (String) settingMap.get("description");
    //     String value = settingMap.get("value").toString();
    //     Label label = new Label(labelText);
    //     label.getStyleClass().add("setting-item-label");
    //     TextField textField = new TextField(value);
    //     textField.getStyleClass().add("setting-item-textfield");
    //     fieldsMap.put(key, textField);
    //     HBox controlBox = new HBox(label, textField);
    //     controlBox.getStyleClass().add("setting-item-control-box");
    //     Label description = new Label(descriptionText);
    //     description.getStyleClass().add("setting-item-description");
    //     description.setWrapText(true);
    //     VBox itemContainer = new VBox(5, controlBox, description);
    //     itemContainer.getStyleClass().add("setting-item");
    //     return itemContainer;
    // } 기존 코드
    

    private Node createStringSettingItem(Map<String, Object> settingMap, Map<String, Node> fieldsMap) {
        // 1. 설정 맵에서 모든 정보를 추출합니다.
        String key = (String) settingMap.get("key");
        String labelText = (String) settingMap.get("label");
        String descriptionText = (String) settingMap.get("description");
        // value가 다른 타입일 수 있으므로, toString()으로 안전하게 문자열로 변환합니다.
        String value = settingMap.get("value").toString(); 

        // 2. Label과 TextField를 생성하고 CSS 클래스를 적용합니다.
        Label label = new Label(labelText);
        label.getStyleClass().add("setting-item-label");
        
        TextField textField = new TextField(value);
        textField.getStyleClass().add("setting-item-textfield");
        
        // 3. 나중에 저장할 수 있도록 컨트롤을 맵에 저장합니다.
        fieldsMap.put(key, textField);

        // 4. [핵심] 기존 코드와 동일하게, Label과 TextField를 HBox로 묶습니다.
        HBox controlBox = new HBox(label, textField);
        controlBox.getStyleClass().add("setting-item-control-box");
        // HBox 내부 요소들의 정렬이나 간격이 필요하다면 여기서 설정합니다.
        // controlBox.setSpacing(10);
        // controlBox.setAlignment(Pos.CENTER_LEFT);

        // 5. 설명(Description) Label을 생성하고 CSS 클래스를 적용합니다.
        Label description = new Label(descriptionText);
        description.getStyleClass().add("setting-item-description");
        description.setWrapText(true); // 설명이 길 경우 자동 줄바꿈

        // 6. [핵심] 최종적으로 controlBox와 description을 VBox로 묶어 하나의 아이템을 완성합니다.
        VBox itemContainer = new VBox(5, controlBox, description); // 5는 VBox 내부 요소간의 수직 간격
        itemContainer.getStyleClass().add("setting-item");

        return itemContainer;
    }
    

    private Node createMapSettingItem(Map<String, Object> settingMap, Map<String, Node> fieldsMap) {
        // 1. 설정 맵에서 모든 정보를 추출합니다.
        String key = (String) settingMap.get("key");
        String labelText = (String) settingMap.get("label");
        String descriptionText = (String) settingMap.get("description");

        // 2. 제목(Label)을 생성하고 CSS 클래스를 적용합니다.
        Label label = new Label(labelText);
        label.getStyleClass().addAll("setting-item-label","run-config");

        // 3. Map 값을 예쁘게 포맷된 JSON 문자열로 변환합니다.
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String jsonValue = gson.toJson(settingMap.get("value"));
        
        // 4. JSON 문자열을 표시하고 편집할 TextArea를 생성하고 CSS 클래스를 적용합니다.
        TextArea textArea = new TextArea(jsonValue);
        textArea.getStyleClass().add("setting-item-textarea");
        textArea.setPrefRowCount(8); // 높이 조절
        
        // 5. 나중에 저장할 수 있도록 컨트롤(TextArea)을 맵에 저장합니다.
        fieldsMap.put(key, textArea);
        
        // 6. 설명(Description) Label을 생성합니다. (null일 수도 있으므로 확인)
        Label description = null;
        if (descriptionText != null && !descriptionText.isBlank()) {
            description = new Label(descriptionText);
            description.getStyleClass().addAll("setting-item-description","run-config");
            description.setWrapText(true);
        }
        
        // 7. [핵심] 최종적으로 모든 요소를 VBox로 묶습니다.
        //    레이아웃: Label -> TextArea -> Description (모두 수직으로 배열)
        VBox itemContainer = new VBox(5); // 5는 요소 간의 수직 간격
        itemContainer.getStyleClass().add("setting-item");
        
        itemContainer.getChildren().add(label);
        itemContainer.getChildren().add(textArea);
        if (description != null) {
            itemContainer.getChildren().add(description);
        }

        // createItemContainer 헬퍼 메서드를 사용한다면 이렇게 됩니다.
        // VBox itemContainer = createItemContainer(label, textArea, descriptionText);

        return itemContainer;
    }
    private VBox createItemContainer(Label label, Node control, String descriptionText) {
        HBox titleBox = new HBox(10); // 이름과 설명을 담을 HBox
        titleBox.getChildren().add(label);
        if (descriptionText != null && !descriptionText.isBlank()) {
            Label description = new Label(descriptionText);
            // ...
            titleBox.getChildren().add(description);
        }
        
        VBox container = new VBox(5);
        container.getChildren().add(titleBox); // 제목/설명 라인
        container.getChildren().add(control);  // 컨트롤
        return container;
    }



    private void setErrorStyle(Node control, boolean isError) {
        final String errorClass = "error-highlight"; // CSS에 정의할 클래스 이름
        if (isError) {
            if (!control.getStyleClass().contains(errorClass)) {
                control.getStyleClass().add(errorClass);
            }
        } else {
            control.getStyleClass().remove(errorClass);
        }
    }

    /**
     * 뷰에 있는 모든 컨트롤의 에러 스타일을 초기화합니다.
     */
    private void clearAllErrorStyles() {
        featureFields.values().stream()
            .flatMap(map -> map.values().stream())
            .forEach(node -> setErrorStyle(node, false));
    }


    // --- [핵심] saveSettings가 이제 boolean을 반환하고, 포커스 로직을 포함 ---
    private boolean saveSettings() {
        clearAllErrorStyles();
        
        boolean allSavedSuccessfully = true;
        final AtomicReference<String> firstErrorFeatureKeyRef = new AtomicReference<>(null);

        for (Map.Entry<String, Map<String, Node>> featureEntry : featureFields.entrySet()) {
            String featureKey = featureEntry.getKey();
            Map<String, Node> controls = featureEntry.getValue();

            for (Map.Entry<String, Node> controlEntry : controls.entrySet()) {
                String settingKey = controlEntry.getKey();
                Node control = controlEntry.getValue();
                
                Object newValue = null;

                if (control instanceof TextArea) {
                    try {
                        String jsonText = ((TextArea) control).getText();
                        Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        newValue = new Gson().fromJson(jsonText, type);
                        if (newValue == null && !jsonText.trim().isEmpty()) {
                            throw new JsonSyntaxException("Input is not a valid JSON object.");
                        }
                    } catch (JsonSyntaxException e) {
                        setErrorStyle(control, true);
                        allSavedSuccessfully = false;
                        
                        // [추가] 첫 번째 에러가 발생한 feature 키를 기록합니다.
                        if (firstErrorFeatureKeyRef.get() == null) {
                            firstErrorFeatureKeyRef.set(featureKey);
                        }
                        continue; // 이 설정은 저장하지 않고 다음으로 넘어감
                    }
                } else if (control instanceof TextArea) {
                    // 컨트롤이 TextArea일 경우 (이것이 '실행 구성' 부분):
                    try {
                        String jsonText = ((TextArea) control).getText();
                        // TextArea의 텍스트(JSON 문자열)를 다시 Map 객체로 변환합니다.
                        Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        newValue = new Gson().fromJson(jsonText, type);
                    } catch (Exception e) {
                        System.err.println("Invalid JSON format for '" + settingKey + "'. Skipping save for this key.");
                        e.printStackTrace();
                        continue; // JSON 형식이 틀렸으면 이 설정은 저장하지 않고 건너뜁니다.
                    }
                }

                // 4. 변환된 최종 값을 ConfigManager에 설정합니다.
                if (newValue != null) {
                    configManager.set(featureKey, settingKey, newValue);
                }
            }
        }
        
        if (allSavedSuccessfully) {
            configManager.saveConfig();
            System.out.println("Settings saved successfully.");
            return true; // 성공 반환
        } else {
            System.err.println("Settings could not be saved due to errors.");
            
            // [핵심] 에러가 발생한 첫 번째 feature 탭으로 자동 전환합니다.
            if (firstErrorFeatureKeyRef.get() != null) {
                // leftMenu의 아이템 목록에서 해당 featureKey를 가진 FeatureItem을 찾습니다.
                String errkey=firstErrorFeatureKeyRef.get();
                leftMenu.getItems().stream()
                    .filter(item -> errkey.equals(item.getKey()))
                    .findFirst()
                    .ifPresent(item -> leftMenu.getSelectionModel().select(item));
            }
            return false; // 실패 반환
        }
    }
}