package compbase.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class EnvLoader {
    public static Config load(String path) throws IOException {
        Map<String, String> map = new HashMap<>();
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            return new Config(map);
        }
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf('=');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            map.put(key, value);
        }
        return new Config(map);
    }
}