package com.ethis2s.view;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

                // 1. TextArea인 경우 (JSON 객체/맵 처리)
                if (control instanceof TextArea) {
                    try {
                        String jsonText = ((TextArea) control).getText();
                        // JSON 문자열을 Map으로 파싱
                        Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        newValue = new Gson().fromJson(jsonText, type);
                        
                        if (newValue == null && !jsonText.trim().isEmpty()) {
                            throw new JsonSyntaxException("Input is not a valid JSON object.");
                        }
                    } catch (JsonSyntaxException e) {
                        // 파싱 실패 시 에러 표시 및 저장 실패 처리
                        setErrorStyle(control, true);
                        allSavedSuccessfully = false;
                        
                        if (firstErrorFeatureKeyRef.get() == null) {
                            firstErrorFeatureKeyRef.set(featureKey);
                        }
                        continue; // 다음 설정으로 넘어감
                    }
                } 
                // 2. TextField인 경우 (문자열, 숫자 처리)
                else if (control instanceof TextField) {
                    String textValue = ((TextField) control).getText();
                    
                    // 숫자인지 확인하여 적절한 타입으로 변환 (간단한 추론)
                    // ConfigManager가 get할 때 타입 변환을 하므로, 여기선 일단 String이나 숫자로 저장
                    if (textValue.matches("-?\\d+")) {
                        newValue = Integer.parseInt(textValue);
                    } else if (textValue.matches("-?\\d+(\\.\\d+)?")) {
                        newValue = Double.parseDouble(textValue);
                    } else {
                        newValue = textValue; // 기본 문자열
                    }
                }

                // 3. 변환된 값을 ConfigManager에 설정 (메모리 업데이트)
                if (newValue != null) {
                    // [중요] 여기서 ConfigManager.set은 메모리만 업데이트한다고 가정
                    // (saveConfig를 호출하지 않는 버전 사용 권장, 일괄 저장을 위해)
                    configManager.set(featureKey, settingKey, newValue);
                }
            }
        }
        
        if (allSavedSuccessfully) {
            // ★★★ [핵심] 모든 설정이 메모리에 정상 반영된 후, 파일에 한 번만 저장합니다. ★★★
            configManager.saveConfig(); 
            System.out.println("All settings saved successfully to file.");
            return true;
        } else {
            System.err.println("Settings could not be saved due to validation errors.");
            
            // 에러 탭으로 이동
            if (firstErrorFeatureKeyRef.get() != null) {
                String errKey = firstErrorFeatureKeyRef.get();
                leftMenu.getItems().stream()
                    .filter(item -> errKey.equals(item.getKey()))
                    .findFirst()
                    .ifPresent(item -> leftMenu.getSelectionModel().select(item));
            }
            return false;
        }
    }
}