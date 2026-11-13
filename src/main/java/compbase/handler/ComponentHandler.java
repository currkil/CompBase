package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.repository.ComponentRepository;
import compbase.util.Json;
import compbase.util.NoteStore;
import compbase.util.Values;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/components
 * <pre>
 * GET    /api/components              列表（支持 q / type_id / subtype_id / package_id / location_id / zone_id）
 * POST   /api/components              新增
 * GET    /api/components/{id}         单个
 * PUT    /api/components/{id}         整体更新
 * DELETE /api/components/{id}         删除（连同备注文件）
 * POST   /api/components/{id}/stock   数量增减 {delta} / {stock_delta, occupied_delta}
 * </pre>
 */
public class ComponentHandler extends BaseHandler {
    private final ComponentRepository repository;
    private final NoteStore noteStore;

    public ComponentHandler(ComponentRepository repository, NoteStore noteStore) {
        this.repository = repository;
        this.noteStore = noteStore;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() < 2 || !"components".equals(segments.get(1))) {
            notFound(exchange);
            return;
        }
        String method = method(exchange);

        if (segments.size() == 2) {
            switch (method) {
                case "GET": {
                    Map<String, String> filters = query(exchange);
                    // BOM 人工匹配复用主列表搜索接口，兼容 keyword 写法
                    if (Values.trimToNull(filters.get("q")) == null) {
                        String keyword = Values.trimToNull(filters.get("keyword"));
                        if (keyword != null) filters.put("q", keyword);
                    }
                    sendJson(exchange, 200, withNoteFlag(repository.findAll(filters)));
                    return;
                }
                case "POST": {
                    Map<String, Object> payload = payload(jsonBody(exchange));
                    long id = repository.create(payload);
                    sendJson(exchange, 201, withNoteFlag(repository.findById(id)));
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
                    Map<String, Object> row = repository.findById(id);
                    if (row == null) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, withNoteFlag(row));
                    }
                    return;
                }
                case "PUT": {
                    if (!repository.update(id, payload(jsonBody(exchange)))) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, withNoteFlag(repository.findById(id)));
                    }
                    return;
                }
                case "DELETE": {
                    if (!repository.delete(id)) {
                        notFound(exchange);
                        return;
                    }
                    noteStore.deleteNote(id);
                    noteStore.deleteImages(id);
                    sendJson(exchange, 200, data("deleted", true, "id", id));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        if (segments.size() == 4 && "stock".equals(segments.get(3)) && "POST".equals(method)) {
            Map<String, Object> body = jsonBody(exchange);
            Long delta = Json.getLong(body, "delta");
            Long stockDelta = delta != null ? delta : Json.getLong(body, "stock_delta");
            Long occupiedDelta = Json.getLong(body, "occupied_delta");
            if (stockDelta == null && occupiedDelta == null) {
                throw new IllegalArgumentException("请提供 delta / stock_delta / occupied_delta");
            }
            Map<String, Object> row = repository.adjustQty(id, stockDelta, occupiedDelta);
            if (row == null) {
                notFound(exchange);
            } else {
                sendJson(exchange, 200, withNoteFlag(row));
            }
            return;
        }

        notFound(exchange);
    }

    /** 给一行（或一批行）补上 note_exists，前端据此显示「备注 / 添加备注」。 */
    private Object withNoteFlag(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) value;
            Long id = Values.toLong(row.get("id"));
            row.put("note_exists", id != null && noteStore.noteExists(id));
            return row;
        }
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                withNoteFlag(item);
            }
        }
        return value;
    }

    /** 把请求体规范化为仓库需要的字段，并做基本的数值校验。 */
    private static Map<String, Object> payload(Map<String, Object> body) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type_id", Json.getLong(body, "type_id"));
        data.put("subtype_id", Json.getLong(body, "subtype_id"));
        data.put("value", Json.getString(body, "value"));
        data.put("package_id", Json.getLong(body, "package_id"));
        data.put("location_id", Json.getLong(body, "location_id"));

        Long stock = Json.getLong(body, "stock_qty");
        if (stock == null) stock = 0L;
        Long occupied = Json.getLong(body, "occupied_qty");
        if (occupied == null) occupied = 0L;
        if (stock < 0) throw new IllegalArgumentException("库存数量不能为负数");
        if (occupied < 0) throw new IllegalArgumentException("占用数量不能为负数");
        if (occupied > stock) throw new IllegalArgumentException("占用数量不能大于库存数量");
        data.put("stock_qty", stock);
        data.put("occupied_qty", occupied);

        Double price = Json.getDouble(body, "price");
        if (price != null && price < 0) throw new IllegalArgumentException("价格不能为负数");
        data.put("price", price);
        return data;
    }
}
