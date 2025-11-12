package com.ethis2s.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.scene.Scene;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConfigManager {

    private static final String CONFIG_FILE_PATH = "plugins/config/config.json";
    private static final String THEMES_BASE_PATH = "plugins/config/";

    private static final ConfigManager instance = new ConfigManager();
    private List<Map<String, Object>> config;

    private ConfigManager() {
        loadConfig();
    }

    public static ConfigManager getInstance() {
        return instance;
    }

    private void loadConfig() {
        try (FileReader reader = new FileReader(CONFIG_FILE_PATH)) {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            config = new Gson().fromJson(reader, type);
        } catch (IOException e) {
            e.printStackTrace();
            // 기본 설정을 사용하거나 오류 처리를 할 수 있습니다.
        }
    }

    public void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE_PATH)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(config, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Optional<Object> getSetting(String feature, String key) {
        if (config == null) {
            return Optional.empty();
        }
        return config.stream()
                .filter(m -> feature.equals(m.get("feature")))
                .findFirst()
                .map(m -> ((Map<String, Object>) m.get("settings")).get(key));
    }

    public void setSetting(String feature, String key, Object value) {
        if (config == null) {
            return;
        }
        config.stream()
                .filter(m -> feature.equals(m.get("feature")))
                .findFirst()
                .ifPresent(m -> ((Map<String, Object>) m.get("settings")).put(key, value));
    }
    
    public String getCurrentTheme() {
        return getSetting("editor", "theme").orElse("light-theme").toString();
    }

    public int getTabSize() {
        return ((Number) getSetting("editor", "tabSize").orElse(4)).intValue();
    }

    public String getSyntaxThemePath() {
        String themeName = getCurrentTheme();
        return toUrl(Paths.get(THEMES_BASE_PATH, themeName + ".css").toString());
    }

    public String getMainThemePath() {
        return toUrl(Paths.get(THEMES_BASE_PATH, "main-theme.css").toString());
    }

    public String getTreeViewThemePath() {
        return toUrl(Paths.get(THEMES_BASE_PATH, "tree-view-theme.css").toString());
    }

    public String getTopTabsThemePath() {
        return toUrl(Paths.get(THEMES_BASE_PATH, "top-tabs-theme.css").toString());
    }

    public String getBottomTabsThemePath() {
        return toUrl(Paths.get(THEMES_BASE_PATH, "bottom-tabs-theme.css").toString());
    }

    private String toUrl(String path) {
        try {
            return Paths.get(path).toUri().toURL().toExternalForm();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
