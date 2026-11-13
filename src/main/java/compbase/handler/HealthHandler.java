package compbase.handler;

import com.sun.net.httpserver.HttpExchange;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** GET /api/health，用于确认服务活着（也可给监控脚本用）。 */
public class HealthHandler extends BaseHandler {
    private final String version;

    public HealthHandler(String version) {
        this.version = version;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        if (!"GET".equals(method(exchange))) {
            methodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, data(
                "status", "ok",
                "version", version,
                "time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
    }
}
