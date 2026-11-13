package compbase.util;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 3D 模型目录探测：列出 static/models/ 下可用的模型文件。
 * 同时兼容「目录形态的 classpath」（gradle run / IDE）与「jar 形态」（installDist）。
 */
public final class ModelCatalog {
    private static final List<String> ALLOWED_FORMATS =
            Arrays.asList("glb", "gltf", "obj", "mtl");

    private ModelCatalog() {
    }

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> models = new ArrayList<>();
        URL url = ModelCatalog.class.getClassLoader().getResource("static/models");
        if (url == null) return models;
        try {
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                Path dir = Path.of(url.toURI());
                if (!Files.isDirectory(dir)) return models;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path file : stream) {
                        if (!Files.isRegularFile(file)) continue;
                        String name = file.getFileName().toString();
                        if (!isModel(name)) continue;
                        models.add(entry(name, Files.size(file)));
                    }
                }
            } else if ("jar".equals(protocol)) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                try (JarFile jar = connection.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("static/models/")) continue;
                        String fileName = name.substring("static/models/".length());
                        if (fileName.isEmpty() || fileName.contains("/") || !isModel(fileName)) continue;
                        models.add(entry(fileName, entry.getSize()));
                    }
                }
            }
        } catch (Exception e) {
            return models;
        }
        models.sort(Comparator.comparing(model -> String.valueOf(model.get("name"))));
        return models;
    }

    public static String format(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private static boolean isModel(String fileName) {
        return ALLOWED_FORMATS.contains(format(fileName).toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> entry(String name, long size) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("size", size);
        map.put("url", "/models/" + name);
        map.put("format", format(name));
        Map<String, Object> suggestion = PackageNameParser.suggest(name);
        map.put("package_name", suggestion.get("package_name"));
        map.put("full_name", suggestion.get("full_name"));
        map.put("pin_count", suggestion.get("pin_count"));
        map.put("pitch", suggestion.get("pitch"));
        map.put("auto_filled", suggestion.get("auto_filled"));
        return map;
    }
}
