package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import compbase.util.ConflictException;
import compbase.util.Json;
import compbase.util.MappingRequiredException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handler 基类：统一处理 CORS、OPTIONS、请求体读取、JSON 输出与异常映射。
 * 子类只需要实现 route() 并按路径分段分发。
 */
public abstract class BaseHandler implements HttpHandler {
    /** 请求体/上传文件大小上限，防止一次误操作把内存打满。 */
    protected static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                    "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equalsIgnoreCase(method(exchange))) {
                sendEmpty(exchange, 204);
                return;
            }
            route(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (ConflictException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (MappingRequiredException e) {
            sendJson(exchange, 400, data(
                    "error", e.getMessage(),
                    "code", "MAPPING_REQUIRED",
                    "import_type", e.importType(),
                    "headers", e.headers(),
                    "fields", e.fields(),
                    "values", e.values()));
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, messageOf(e));
        } finally {
            exchange.close();
        }
    }

    /** 具体的路由实现。 */
    protected abstract void route(HttpExchange exchange) throws Exception;

    // ==================== 请求信息 ====================

    protected static String method(HttpExchange exchange) {
        return exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    }

    /** 把路径按 / 切成段，忽略空段。例如 /api/components/3/stock -> [api, components, 3, stock]。 */
    protected static List<String> segments(HttpExchange exchange) {
        List<String> parts = new ArrayList<>();
        for (String part : exchange.getRequestURI().getPath().split("/")) {
            if (!part.isEmpty()) parts.add(part);
        }
        return parts;
    }

    protected static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return params;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int index = pair.indexOf('=');
            String key = index < 0 ? pair : pair.substring(0, index);
            String value = index < 0 ? "" : pair.substring(index + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    protected static long pathId(String segment) {
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法的 id: " + segment);
        }
    }

    protected static String readBody(HttpExchange exchange) throws IOException {
        return new String(readBodyBytes(exchange), StandardCharsets.UTF_8);
    }

    /** 读取原始请求体字节（图片上传用）。 */
    protected static byte[] readBodyBytes(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("请求体超过 " + (MAX_BODY_BYTES / 1024 / 1024) + "MB 上限");
                }
            }
            return buffer.toByteArray();
        }
    }

    /** 读取并解析 JSON 请求体；空请求体返回空 Map。 */
    protected static Map<String, Object> jsonBody(HttpExchange exchange) throws IOException {
        String raw = readBody(exchange).trim();
        if (raw.isEmpty()) return new LinkedHashMap<>();
        return Json.parseObject(raw);
    }

    // ==================== 响应 ====================

    protected static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendBytes(exchange, status, JSON_CONTENT_TYPE, Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    protected static void sendText(HttpExchange exchange, int status, String contentType, String text)
            throws IOException {
        sendBytes(exchange, status, contentType + "; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    protected static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // HEAD 只返回响应头，不能写响应体（否则 JDK HttpServer 会报 stream closed）
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** 无响应体（204/304 等）。 */
    protected static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    protected static void sendError(HttpExchange exchange, int status, String message) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", message == null ? "请求处理失败" : message);
            sendJson(exchange, status, body);
        } catch (IOException ignored) {
            // 响应已经开始写出，无法再补救
        }
    }

    protected static void notFound(HttpExchange exchange) {
        sendError(exchange, 404, "资源不存在");
    }

    protected static void methodNotAllowed(HttpExchange exchange) {
        sendError(exchange, 405, "不支持的请求方法: " + method(exchange));
    }

    // ==================== 便捷方法 ====================

    /** 构造有序 Map，便于写 JSON 响应。 */
    protected static Map<String, Object> data(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** 读取必填字符串字段，缺失或空白时抛出 400。 */
    protected static String required(Map<String, Object> body, String key, String label) {
        String value = Json.getString(body, key);
        if (value == null) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    private static String messageOf(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
