package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.util.NoteStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * /notes/ 备注与图片。
 * <pre>
 * GET    /notes                             备注文件列表
 * GET    /notes/comp_1.md                   读取备注（text/markdown）
 * PUT    /notes/comp_1.md                   写入备注（请求体即 Markdown 文本）
 * DELETE /notes/comp_1.md                   删除备注
 * GET    /notes/images                      图片列表
 * GET    /notes/images/xxx.jpg              读取图片
 * PUT    /notes/images/xxx.jpg              上传图片（请求体即二进制，也可用 ?name= 指定文件名）
 * DELETE /notes/images/xxx.jpg              删除图片
 * </pre>
 */
public class NotesHandler extends BaseHandler {
    private static final Pattern NOTE_NAME = Pattern.compile("^[A-Za-z0-9_-]+\\.md$");
    private static final Pattern IMAGE_NAME =
            Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(jpg|jpeg|png|gif|webp)$",
                    Pattern.CASE_INSENSITIVE);

    private final NoteStore noteStore;

    public NotesHandler(NoteStore noteStore) {
        this.noteStore = noteStore;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.isEmpty() || !"notes".equals(segments.get(0))) {
            notFound(exchange);
            return;
        }
        String method = method(exchange);

        if (segments.size() == 1) {
            if (!"GET".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            sendJson(exchange, 200, listNotes());
            return;
        }

        if ("images".equals(segments.get(1))) {
            handleImages(exchange, segments, method);
            return;
        }

        if (segments.size() != 2) {
            notFound(exchange);
            return;
        }
        String name = segments.get(1);
        if (!NOTE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("备注文件名只能是字母、数字、下划线或短横线，并以 .md 结尾");
        }
        Path file = noteStore.notesDir().resolve(name).normalize();
        if (!file.startsWith(noteStore.notesDir())) {
            throw new IllegalArgumentException("非法的备注路径");
        }

        switch (method) {
            case "GET": {
                if (!Files.isRegularFile(file)) {
                    sendError(exchange, 404, "备注不存在: " + name);
                    return;
                }
                sendText(exchange, 200, "text/markdown", Files.readString(file));
                return;
            }
            case "PUT":
            case "POST": {
                Files.createDirectories(noteStore.notesDir());
                String content = readBody(exchange);
                Files.writeString(file, content);
                sendJson(exchange, 200, data("name", name, "size", Files.size(file)));
                return;
            }
            case "DELETE": {
                boolean deleted = Files.deleteIfExists(file);
                if (!deleted) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, data("deleted", true, "name", name));
                }
                return;
            }
            default:
                methodNotAllowed(exchange);
        }
    }

    private void handleImages(HttpExchange exchange, List<String> segments, String method) throws Exception {
        if (segments.size() == 2) {
            if (!"GET".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            sendJson(exchange, 200, listImages());
            return;
        }
        if (segments.size() != 3) {
            notFound(exchange);
            return;
        }

        String name = segments.get(2);
        String overrideName = query(exchange).get("name");
        if (overrideName != null && !overrideName.isBlank()) {
            name = overrideName.trim();
        }
        if (!IMAGE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("图片只支持 jpg/jpeg/png/gif/webp，文件名只能包含字母、数字、下划线、点或短横线");
        }
        Path file = noteStore.imagesDir().resolve(name).normalize();
        if (!file.startsWith(noteStore.imagesDir())) {
            throw new IllegalArgumentException("非法的图片路径");
        }

        switch (method) {
            case "GET": {
                if (!Files.isRegularFile(file)) {
                    sendError(exchange, 404, "图片不存在: " + name);
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
                sendBytes(exchange, 200, contentType(name), Files.readAllBytes(file));
                return;
            }
            case "PUT":
            case "POST": {
                byte[] bytes = readBodyBytes(exchange);
                if (bytes.length == 0) throw new IllegalArgumentException("上传内容为空");
                Files.createDirectories(noteStore.imagesDir());
                Files.write(file, bytes);
                sendJson(exchange, 201, data(
                        "name", name,
                        "size", bytes.length,
                        "url", "/notes/images/" + name));
                return;
            }
            case "DELETE": {
                boolean deleted = Files.deleteIfExists(file);
                if (!deleted) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, data("deleted", true, "name", name));
                }
                return;
            }
            default:
                methodNotAllowed(exchange);
        }
    }

    private List<Map<String, Object>> listNotes() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!Files.isDirectory(noteStore.notesDir())) return list;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(noteStore.notesDir(), "*.md")) {
            for (Path file : stream) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", file.getFileName().toString());
                row.put("size", Files.size(file));
                row.put("modified", Files.getLastModifiedTime(file).toMillis());
                list.add(row);
            }
        }
        list.sort(Comparator.comparing(row -> String.valueOf(row.get("name"))));
        return list;
    }

    private List<Map<String, Object>> listImages() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!Files.isDirectory(noteStore.imagesDir())) return list;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(noteStore.imagesDir())) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (!IMAGE_NAME.matcher(name).matches()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("size", Files.size(file));
                row.put("modified", Files.getLastModifiedTime(file).toMillis());
                row.put("url", "/notes/images/" + name);
                list.add(row);
            }
        }
        list.sort(Comparator.comparing(row -> String.valueOf(row.get("name"))));
        return list;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
