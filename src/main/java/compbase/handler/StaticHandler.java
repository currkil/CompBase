package compbase.handler;

import com.sun.net.httpserver.HttpExchange;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 前端静态资源：从 classpath 的 static/ 目录读取。
 * getResourceAsStream 只读打包进 jar 的副本，所以改前端后需要重新构建。
 */
public class StaticHandler extends BaseHandler {
    private static final Map<String, String> CONTENT_TYPES = new LinkedHashMap<>();

    static {
        CONTENT_TYPES.put("html", "text/html");
        CONTENT_TYPES.put("css", "text/css");
        CONTENT_TYPES.put("js", "application/javascript");
        CONTENT_TYPES.put("mjs", "application/javascript");
        CONTENT_TYPES.put("json", "application/json");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("webp", "image/webp");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("md", "text/markdown");
        CONTENT_TYPES.put("txt", "text/plain");
        CONTENT_TYPES.put("woff2", "font/woff2");
        CONTENT_TYPES.put("glb", "model/gltf-binary");
        CONTENT_TYPES.put("gltf", "model/gltf+json");
        CONTENT_TYPES.put("obj", "text/plain");
        CONTENT_TYPES.put("mtl", "text/plain");
        CONTENT_TYPES.put("bin", "application/octet-stream");
        CONTENT_TYPES.put("wasm", "application/wasm");
    }

    private final String basePath;

    public StaticHandler(String basePath) {
        this.basePath = basePath;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        String method = method(exchange);
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            methodNotAllowed(exchange);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/")) path = path + "index.html";
        if (path.contains("..") || path.contains("\\") || path.contains("//")) {
            throw new IllegalArgumentException("非法的资源路径");
        }
        // 未注册的接口不应该回退到单页，否则前端会拿到一份 HTML 而不是 404
        if (path.startsWith("/api/") || path.equals("/api")) {
            sendError(exchange, 404, "接口不存在: " + path);
            return;
        }

        String resource = "/" + basePath + path;
        byte[] bytes = readResource(resource);
        if (bytes == null && !path.contains(".")) {
            // 无扩展名且未命中时回退到单页入口
            bytes = readResource("/" + basePath + "/index.html");
        }
        if (bytes == null) {
            sendText(exchange, 404, "text/plain", "页面不存在: " + path);
            return;
        }

        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        sendBytes(exchange, 200, contentType(path), bytes);
    }

    private byte[] readResource(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
