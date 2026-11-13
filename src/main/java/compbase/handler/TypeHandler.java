package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.repository.TypeRepository;
import compbase.util.Json;

import java.util.List;
import java.util.Map;

/**
 * /api/types 与 /api/subtypes
 * <pre>
 * GET    /api/types                      类型列表（内嵌 subtypes）
 * POST   /api/types                      新增类型
 * GET    /api/types/{id}                 单个类型（内嵌 subtypes）
 * PUT    /api/types/{id}                 修改类型
 * DELETE /api/types/{id}                 删除类型（连同子类型，被引用时 409）
 * GET    /api/types/{id}/subtypes        某类型下的子类型
 * POST   /api/types/{id}/subtypes        新增子类型
 * GET    /api/subtypes?type_id=          子类型列表
 * GET    /api/subtypes/{id}              单个子类型
 * PUT    /api/subtypes/{id}              修改子类型
 * DELETE /api/subtypes/{id}              删除子类型（被引用时 409）
 * </pre>
 */
public class TypeHandler extends BaseHandler {
    private final TypeRepository repository;

    public TypeHandler(TypeRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() < 2) {
            notFound(exchange);
            return;
        }
        String resource = segments.get(1);
        String method = method(exchange);

        if ("types".equals(resource)) {
            handleTypes(exchange, segments, method);
        } else if ("subtypes".equals(resource)) {
            handleSubtypes(exchange, segments, method);
        } else {
            notFound(exchange);
        }
    }

    private void handleTypes(HttpExchange exchange, List<String> segments, String method) throws Exception {
        if (segments.size() == 2) {
            switch (method) {
                case "GET":
                    sendJson(exchange, 200, repository.listTypes());
                    return;
                case "POST": {
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "type_name", "类型名称");
                    long id = repository.createType(name, Json.getString(body, "description"));
                    sendJson(exchange, 201, repository.findType(id));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        long id = pathId(segments.get(2));
        if (segments.size() == 3) {
            switch (method) {
                case "GET": {
                    Map<String, Object> type = repository.findType(id);
                    if (type == null) {
                        notFound(exchange);
                        return;
                    }
                    type.put("subtypes", repository.listSubtypes(id));
                    sendJson(exchange, 200, type);
                    return;
                }
                case "PUT": {
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "type_name", "类型名称");
                    if (!repository.updateType(id, name, Json.getString(body, "description"))) {
                        notFound(exchange);
                    } else {
                        Map<String, Object> type = repository.findType(id);
                        type.put("subtypes", repository.listSubtypes(id));
                        sendJson(exchange, 200, type);
                    }
                    return;
                }
                case "DELETE":
                    if (!repository.deleteType(id)) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, data("deleted", true, "type_id", id));
                    }
                    return;
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        if (segments.size() == 4 && "subtypes".equals(segments.get(3))) {
            switch (method) {
                case "GET":
                    sendJson(exchange, 200, repository.listSubtypes(id));
                    return;
                case "POST": {
                    if (repository.findType(id) == null) {
                        sendError(exchange, 404, "类型不存在: " + id);
                        return;
                    }
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "subtype_name", "子类型名称");
                    long subtypeId = repository.createSubtype(id, name, Json.getString(body, "description"));
                    sendJson(exchange, 201, repository.findSubtype(subtypeId));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        notFound(exchange);
    }

    private void handleSubtypes(HttpExchange exchange, List<String> segments, String method) throws Exception {
        if (segments.size() == 2) {
            if (!"GET".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            Long typeId = Json.getLong(query(exchange), "type_id");
            sendJson(exchange, 200, repository.listSubtypes(typeId));
            return;
        }

        long id = pathId(segments.get(2));
        if (segments.size() != 3) {
            notFound(exchange);
            return;
        }
        switch (method) {
            case "GET": {
                Map<String, Object> subtype = repository.findSubtype(id);
                if (subtype == null) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, subtype);
                }
                return;
            }
            case "PUT": {
                Map<String, Object> body = jsonBody(exchange);
                Map<String, Object> existing = repository.findSubtype(id);
                if (existing == null) {
                    notFound(exchange);
                    return;
                }
                String name = Json.getString(body, "subtype_name");
                if (name == null) name = String.valueOf(existing.get("subtype_name"));
                Long typeId = Json.getLong(body, "type_id");
                if (typeId == null) typeId = Json.getLong(existing, "type_id");
                repository.updateSubtype(id, typeId, name, Json.getString(body, "description"));
                sendJson(exchange, 200, repository.findSubtype(id));
                return;
            }
            case "DELETE":
                if (!repository.deleteSubtype(id)) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, data("deleted", true, "subtype_id", id));
                }
                return;
            default:
                methodNotAllowed(exchange);
        }
    }
}
