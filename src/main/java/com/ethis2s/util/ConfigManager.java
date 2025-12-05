package com.ethis2s.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConfigManager {

    private static final String CONFIG_FILE_PATH = "plugins/config/config.json";
    private static final ConfigManager instance = new ConfigManager();
    
    private List<Map<String, Object>> configData;

    private ConfigManager() {
        loadConfig();
    }

    public static ConfigManager getInstance() {
        return instance;
    }

    private static Path resolveBaseDir() {
        try {
            Path codeSourcePath = Paths.get(
                ConfigManager.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            );

            Path baseDir;
            if (Files.isDirectory(codeSourcePath)) {
                // 개발 환경: target/classes 기준으로 프로젝트 루트로 이동
                baseDir = codeSourcePath.getParent() != null
                    ? codeSourcePath.getParent().getParent()
                    : codeSourcePath.toAbsolutePath();
            } else {
                // 배포 환경: JAR 파일이 있는 디렉터리 기준
                baseDir = codeSourcePath.getParent();
            }

            return baseDir;
        } catch (Exception e) {
            // 실패 시 현재 작업 디렉터리 기준 사용
            return Paths.get("").toAbsolutePath();
        }
    }

    public static Path getBaseDir() {
        return resolveBaseDir();
    }

    private static Path resolveConfigPath() {
        return getBaseDir().resolve(CONFIG_FILE_PATH).normalize();
    }

    public void loadConfig() {
        Path configPath = resolveConfigPath();
        try (FileReader reader = new FileReader(configPath.toFile())) {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            configData = new Gson().fromJson(reader, type);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveConfig() {
        Path configPath = resolveConfigPath();
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            gson.toJson(configData, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- [핵심] 모든 Getter를 대체할 통합 Get 메소드 ---
    /**
     * 지정된 키에 해당하는 설정 값을 요청된 타입으로 반환합니다.
     * 값이 없거나 타입 변환에 실패하면 제공된 기본값을 반환합니다.
     * @param featureKey 기능의 영문 키 (예: "editor")
     * @param settingKey 설정의 영문 키 (예: "fontSize")
     * @param type       반환받고 싶은 클래스 타입 (예: Integer.class)
     * @param defaultValue 기본값
     * @param <T>        반환 타입
     * @return 설정 값 또는 기본값
     */
    public <T> T get(String featureKey, String settingKey, Class<T> type, T defaultValue) {
        if (configData == null) return defaultValue;

        Optional<Object> valueOpt = configData.stream()
            .filter(featureMap -> featureKey.equals(featureMap.get("featureKey")))
            .findFirst()
            .flatMap(featureMap -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> settings = (List<Map<String, Object>>) featureMap.get("settings");
                if (settings == null) return Optional.empty();
                
                return settings.stream()
                        .filter(settingMap -> settingKey.equals(settingMap.get("key")))
                        .findFirst()
                        .map(settingMap -> settingMap.get("value"));
            });

        if (valueOpt.isEmpty()) {
            return defaultValue;
        }

        Object value = valueOpt.get();
        
        // 타입 변환 시도
        try {
            // 숫자 타입(Integer, Double 등)에 대한 특별 처리
            if (value instanceof Number && (type == Integer.class || type == int.class)) {
                return type.cast(((Number) value).intValue());
            }
            if (value instanceof Number && (type == Double.class || type == double.class)) {
                return type.cast(((Number) value).doubleValue());
            }
            if (type == Map.class && value instanceof Map) {
                return type.cast(value);
            }
            // 그 외 타입은 직접 캐스팅
            return type.cast(value);
        } catch (ClassCastException e) {
            // 타입 변환 실패 시 기본값 반환
            System.err.println("ConfigManager: Type cast failed for " + featureKey + "/" + settingKey + ". Expected " + type.getSimpleName() + ", got " + value.getClass().getSimpleName() + ". Returning default value.");
            return defaultValue;
        }
    }
    
    // [핵심] URL 경로를 가져오는 전용 메소드는 유지 (편의성)
    public String getThemePath(String featureKey, String settingKey) {
        String path = get(featureKey, settingKey, String.class, null);
        if (path == null) return null;
        try {
            Path themePath = getBaseDir().resolve(path).normalize();
            return themePath.toUri().toURL().toExternalForm();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // [수정] 설정 '값'을 설정하는 메소드 (이름 변경으로 명확성 증대)
    public void set(String featureKey, String settingKey, Object value) {
        if (configData == null) return;

        configData.stream()
                .filter(featureMap -> featureKey.equals(featureMap.get("featureKey")))
                .findFirst()
                .ifPresent(featureMap -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> settings = (List<Map<String, Object>>) featureMap.get("settings");
                    if (settings == null) return;

                    settings.stream()
                            .filter(settingMap -> settingKey.equals(settingMap.get("key")))
                            .findFirst()
                            .ifPresent(settingMap -> settingMap.put("value", value));
                });
    }

    // [유지] SettingsView에서 전체 데이터를 읽기 위해 필요
    public List<Map<String, Object>> getConfigData() {
        return configData;
    }
}
