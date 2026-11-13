package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.repository.LocationRepository;
import compbase.util.Json;
import compbase.util.Values;

import java.util.List;
import java.util.Map;

/**
 * /api/zones 与 /api/locations
 * <pre>
 * GET    /api/zones                         位置区列表（内嵌 locations）
 * POST   /api/zones                         新增位置区
 * PUT    /api/zones/{id}                    修改位置区
 * DELETE /api/zones/{id}                    删除位置区（连同其位置，被引用时 409）
 * GET    /api/zones/{id}/locations          某位置区下的位置
 * POST   /api/zones/{id}/locations          新增位置
 * GET    /api/locations?zone_id=            位置列表（带 label）
 * GET    /api/locations/{id}                单个位置
 * PUT    /api/locations/{id}                修改位置
 * DELETE /api/locations/{id}                删除位置（被引用时 409）
 * </pre>
 */
public class LocationHandler extends BaseHandler {
    private final LocationRepository repository;

    public LocationHandler(LocationRepository repository) {
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

        if ("zones".equals(resource)) {
            handleZones(exchange, segments, method);
        } else if ("locations".equals(resource)) {
            handleLocations(exchange, segments, method);
        } else {
            notFound(exchange);
        }
    }

    private void handleZones(HttpExchange exchange, List<String> segments, String method) throws Exception {
        if (segments.size() == 2) {
            switch (method) {
                case "GET":
                    sendJson(exchange, 200, repository.listZones());
                    return;
                case "POST": {
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "zone_name", "位置区名称");
                    long id = repository.createZone(name, Json.getString(body, "description"));
                    sendJson(exchange, 201, repository.findZone(id));
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
                    Map<String, Object> zone = repository.findZone(id);
                    if (zone == null) {
                        notFound(exchange);
                    } else {
                        zone.put("locations", repository.listLocations(id));
                        sendJson(exchange, 200, zone);
                    }
                    return;
                }
                case "PUT": {
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "zone_name", "位置区名称");
                    if (!repository.updateZone(id, name, Json.getString(body, "description"))) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, repository.findZone(id));
                    }
                    return;
                }
                case "DELETE":
                    if (!repository.deleteZone(id)) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, data("deleted", true, "zone_id", id));
                    }
                    return;
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        if (segments.size() == 4 && "locations".equals(segments.get(3))) {
            switch (method) {
                case "GET":
                    sendJson(exchange, 200, repository.listLocations(id));
                    return;
                case "POST": {
                    if (repository.findZone(id) == null) {
                        sendError(exchange, 404, "位置区不存在: " + id);
                        return;
                    }
                    Map<String, Object> body = jsonBody(exchange);
                    String code = required(body, "location_code", "区内编号");
                    long locationId = repository.createLocation(id, code,
                            Json.getString(body, "description"));
                    sendJson(exchange, 201, repository.findLocation(locationId));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        notFound(exchange);
    }

    private void handleLocations(HttpExchange exchange, List<String> segments, String method) throws Exception {
        if (segments.size() == 2) {
            switch (method) {
                case "GET": {
                    Long zoneId = Values.toLong(query(exchange).get("zone_id"));
                    sendJson(exchange, 200, repository.listLocations(zoneId));
                    return;
                }
                case "POST": {
                    Map<String, Object> body = jsonBody(exchange);
                    Long zoneId = Json.getLong(body, "zone_id");
                    if (zoneId == null) throw new IllegalArgumentException("位置区不能为空");
                    String code = required(body, "location_code", "区内编号");
                    long id = repository.createLocation(zoneId, code,
                            Json.getString(body, "description"));
                    sendJson(exchange, 201, repository.findLocation(id));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        long id = pathId(segments.get(2));
        if (segments.size() != 3) {
            notFound(exchange);
            return;
        }
        switch (method) {
            case "GET": {
                Map<String, Object> location = repository.findLocation(id);
                if (location == null) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, location);
                }
                return;
            }
            case "PUT": {
                Map<String, Object> body = jsonBody(exchange);
                Map<String, Object> existing = repository.findLocation(id);
                if (existing == null) {
                    notFound(exchange);
                    return;
                }
                Long zoneId = Json.getLong(body, "zone_id");
                if (zoneId == null) zoneId = Values.toLong(existing.get("zone_id"));
                String code = Json.getString(body, "location_code");
                if (code == null) code = Values.toStr(existing.get("location_code"));
                repository.updateLocation(id, zoneId, code, Json.getString(body, "description"));
                sendJson(exchange, 200, repository.findLocation(id));
                return;
            }
            case "DELETE":
                if (!repository.deleteLocation(id)) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, data("deleted", true, "location_id", id));
                }
                return;
            default:
                methodNotAllowed(exchange);
        }
    }
}
