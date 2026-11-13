package compbase.config;

import com.moandjiezana.toml.Toml;

public class TomlConfig {
    private final Toml toml;

    public TomlConfig(Toml toml) {
        this.toml = toml;
    }

    public String getString(String key) {
        return toml.getString(key);
    }

    public String getString(String key, String defaultValue) {
        String v = toml.getString(key);
        return v != null ? v : defaultValue;
    }
}