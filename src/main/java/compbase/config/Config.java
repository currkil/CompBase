package compbase.config;

import java.util.Map;

public class Config {
    private final Map<String, String> values;

    public Config(Map<String, String> values) {
        this.values = values;
    }

    public String getString(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}