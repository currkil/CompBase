package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.repository.BomRepository;
import compbase.service.BomService;
import compbase.util.Json;
import compbase.util.Values;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/bom
 * <pre>
 * POST /api/bom/import                 上传 BOM CSV（原始请求体），返回 bom + 预览明细
 * GET  /api/bom/list                   所有 BOM 导入记录
 * GET  /api/bom/{id}                   BOM 详情 + 明细
 * GET  /api/bom/{id}/items             明细列表
 * POST /api/bom/item/{itemId}/match    人工指定元器件
 * POST /api/bom/{id}/confirm           确认占用（body {force:true} 表示强制确认）
 * POST /api/bom/{id}/revoke            撤销占用
 * GET  /api/bom/{id}/components        关联元器件 id 列表（列表页高亮用）
 * </pre>
 */
public class BomHandler extends BaseHandler {
    private final BomRepository repository;
    private final BomService service;

    public BomHandler(BomRepository repository, BomService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() < 2 || !"bom".equals(segments.get(1))) {
            notFound(exchange);
            return;
        }
        String method = method(exchange);

        if (segments.size() == 3 && "import".equals(segments.get(2))) {
            if (!"POST".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            Map<String, String> params = query(exchange);
            byte[] bytes = readBodyBytes(exchange);
            String fileName = Values.trimToNull(params.get("file_name"));
            String projectName = Values.trimToNull(params.get("project_name"));
            Integer valueCol = intParam(params.get("value_col"));
            Integer packageCol = intParam(params.get("package_col"));
            Integer quantityCol = intParam(params.get("quantity_col"));
            sendJson(exchange, 201, service.importFile(bytes, fileName, projectName,
                    valueCol, packageCol, quantityCol));
            return;
        }

        if (segments.size() == 3 && "list".equals(segments.get(2))) {
            if (!"GET".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            sendJson(exchange, 200, repository.listImports());
            return;
        }

        if (segments.size() < 3) {
            notFound(exchange);
            return;
        }

        // /api/bom/item/{itemId}/match
        if ("item".equals(segments.get(2))) {
            if (segments.size() != 5 || !"match".equals(segments.get(4)) || !"POST".equals(method)) {
                notFound(exchange);
                return;
            }
            long itemId = pathId(segments.get(3));
            Map<String, Object> body = jsonBody(exchange);
            Long componentId = Json.getLong(body, "component_id");
            if (componentId == null) throw new IllegalArgumentException("请指定 component_id");
            Map<String, Object> item = service.matchItem(itemId, componentId);
            if (item == null) {
                notFound(exchange);
            } else {
                sendJson(exchange, 200, item);
            }
            return;
        }

        long bomId = pathId(segments.get(2));
        if (segments.size() == 3) {
            if (!"GET".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            sendBomDetail(exchange, bomId);
            return;
        }

        if (segments.size() == 4) {
            String action = segments.get(3);
            if ("items".equals(action) && "GET".equals(method)) {
                Map<String, Object> bom = repository.findImport(bomId);
                if (bom == null) {
                    notFound(exchange);
                    return;
                }
                sendJson(exchange, 200, data("bom", bom, "items", repository.listItems(bomId)));
                return;
            }
            if ("components".equals(action) && "GET".equals(method)) {
                if (repository.findImport(bomId) == null) {
                    notFound(exchange);
                    return;
                }
                sendJson(exchange, 200, data("bom_id", bomId,
                        "component_ids", repository.componentIds(bomId)));
                return;
            }
            if ("confirm".equals(action) && "POST".equals(method)) {
                boolean force = Json.getBoolean(jsonBody(exchange), "force") == Boolean.TRUE
                        || "true".equalsIgnoreCase(query(exchange).get("force"));
                Map<String, Object> result = repository.confirm(bomId, force);
                if (result == null) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, result);
                }
                return;
            }
            if ("revoke".equals(action) && "POST".equals(method)) {
                Map<String, Object> result = repository.revoke(bomId);
                if (result == null) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, result);
                }
                return;
            }
        }

        notFound(exchange);
    }

    private void sendBomDetail(HttpExchange exchange, long bomId) throws Exception {
        Map<String, Object> bom = repository.findImport(bomId);
        if (bom == null) {
            notFound(exchange);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bom", bom);
        payload.put("items", repository.listItems(bomId));
        sendJson(exchange, 200, payload);
    }

    private static Integer intParam(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("列号必须是数字: " + value);
        }
    }
}
