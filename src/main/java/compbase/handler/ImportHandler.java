package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.service.BaseDataImportService;
import compbase.util.Values;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/import
 * <pre>
 * POST /api/import/types           导入类型
 * POST /api/import/subtypes        导入子类型
 * POST /api/import/package-types   导入封装类型
 * POST /api/import/packages        导入封装
 * POST /api/import/zones           导入位置区
 * POST /api/import/locations       导入位置
 * </pre>
 * 查询参数：file_name、strategy=SKIP|UPDATE|INSERT_ALL、preview=true|false，
 * 以及人工指定列号的 *_col 参数（name_col / description_col / parent_col / code_col /
 * full_name_col / pin_count_col / pitch_col / model_file_col / model_format_col）。
 */
public class ImportHandler extends BaseHandler {
    private final BaseDataImportService service;

    public ImportHandler(BaseDataImportService service) {
        this.service = service;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() < 3 || !"import".equals(segments.get(1))) {
            notFound(exchange);
            return;
        }
        if (!"POST".equals(method(exchange))) {
            methodNotAllowed(exchange);
            return;
        }
        String importType = mapType(segments.get(2));
        if (importType == null) {
            notFound(exchange);
            return;
        }

        Map<String, String> params = query(exchange);
        String fileName = Values.trimToNull(params.get("file_name"));
        String strategy = Values.trimToNull(params.get("strategy"));
        if (strategy == null) strategy = Values.trimToNull(params.get("duplicateStrategy"));
        boolean preview = "true".equalsIgnoreCase(params.get("preview"))
                || "1".equals(params.get("preview"));

        Map<String, Integer> columns = new LinkedHashMap<>();
        for (String key : new String[]{"name_col", "description_col", "parent_col", "code_col",
                "full_name_col", "pin_count_col", "pitch_col", "model_file_col",
                "model_format_col"}) {
            String raw = Values.trimToNull(params.get(key));
            if (raw != null) {
                try {
                    columns.put(key, Integer.valueOf(raw));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + " 必须是数字: " + raw);
                }
            }
        }

        byte[] bytes = readBodyBytes(exchange);
        sendJson(exchange, 200,
                service.importData(importType, bytes, fileName, strategy, preview, columns));
    }

    private static String mapType(String segment) {
        switch (segment) {
            case "types":
                return BaseDataImportService.TYPE;
            case "subtypes":
                return BaseDataImportService.SUBTYPE;
            case "package-types":
                return BaseDataImportService.PACKAGE_TYPE;
            case "packages":
                return BaseDataImportService.PACKAGE;
            case "zones":
                return BaseDataImportService.ZONE;
            case "locations":
                return BaseDataImportService.LOCATION;
            default:
                return null;
        }
    }
}
