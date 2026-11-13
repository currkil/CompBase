package compbase.config;

import com.moandjiezana.toml.Toml;
import java.io.File;

public class TomlLoader {
    public static TomlConfig load(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalStateException("config.toml not found: " + path);
        }
        Toml toml = new Toml().read(file);
        return new TomlConfig(toml);
    }
}