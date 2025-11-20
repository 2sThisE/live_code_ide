package com.ethis2s.model; // 모델 패키지에 위치

/**
 * UI(예: ComboBox)에 표시될 실행 템플릿을 나타내는 간단한 데이터 객체입니다.
 * 사용자가 UI에서 선택하면, 이 객체의 'key'를 사용하여 ConfigManager에서 실제 템플릿을 찾습니다.
 */
public class RunConfiguration {

    private final String key; // config.json에 정의된 템플릿의 키 (예: "java-app-run")
    private final String name; // UI ComboBox에 표시될 이름 (예: "Run Java App")

    public RunConfiguration(String key, String name) {
        this.key = key;
        this.name = name;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    // ComboBox가 이 객체를 UI에 표시할 때 이 메서드를 사용합니다.
    @Override
    public String toString() {
        return name;
    }
}