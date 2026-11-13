package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.repository.ImportLogRepository;
import compbase.util.Values;

import java.util.List;

/**
 * /api/import-log
 * <pre>
 * GET /api/import-log           全部日志
 * GET /api/import-log/list      全部日志（可加 ?type=BOM 等筛选）
 * GET /api/import-log/{id}      单条日志详情（errors 已解析成数组）
 * </pre>
 */
public class ImportLogHandler extends BaseHandler {
    private final ImportLogRepository repository;

    public ImportLogHandler(ImportLogRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() < 2 || !"import-log".equals(segments.get(1))) {
            notFound(exchange);
            return;
        }
        if (!"GET".equals(method(exchange))) {
            methodNotAllowed(exchange);
            return;
        }

        if (segments.size() == 2) {
            sendJson(exchange, 200, repository.list(null));
            return;
        }
        if ("list".equals(segments.get(2))) {
            String type = Values.trimToNull(query(exchange).get("type"));
            sendJson(exchange, 200, repository.list(type));
            return;
        }

        long id = pathId(segments.get(2));
        if (segments.size() != 3) {
            notFound(exchange);
            return;
        }
        if (repository.find(id) == null) {
            notFound(exchange);
        } else {
            sendJson(exchange, 200, repository.find(id));
        }
    }
}
