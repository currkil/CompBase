package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.util.ModelCatalog;

import java.util.List;

/** GET /api/models：列出 static/models/ 下可用的 3D 模型，供封装编辑页下拉选择。 */
public class ModelHandler extends BaseHandler {
    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() != 2 || !"models".equals(segments.get(1))) {
            notFound(exchange);
            return;
        }
        if (!"GET".equals(method(exchange))) {
            methodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, ModelCatalog.list());
    }
}
