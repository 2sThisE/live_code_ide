package com.ethis2s.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ConfigManager {

    private static final String CONFIG_PATH = "plugins/config/config.json";
    private static final int DEFAULT_TAB_SIZE = 4;

    public static final int TAB_SIZE;

    static {
        TAB_SIZE = loadTabSize();
    }

    private static int loadTabSize() {
        try {
            String content = new String(Files.readAllBytes(Paths.get(CONFIG_PATH)));
            JSONArray configArray = new JSONArray(content);

            for (int i = 0; i < configArray.length(); i++) {
                JSONObject configObject = configArray.getJSONObject(i);
                if ("editor".equals(configObject.optString("feature"))) {
                    JSONObject settings = configObject.optJSONObject("settings");
                    if (settings != null) {
                        return settings.optInt("tabSize", DEFAULT_TAB_SIZE);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Could not read or parse config.json, using default tab size. Error: " + e.getMessage());
        }
        return DEFAULT_TAB_SIZE;
    }
}