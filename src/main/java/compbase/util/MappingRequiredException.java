package compbase.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 表头无法自动识别时抛出，由 Handler 转成 400 + 可人工指定列的信息。
 * fields: [{key, label, required}]，前端据此生成下拉框，再以 *_col 参数重试。
 */
public class MappingRequiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String importType;
    private final List<String> headers;
    private final List<Map<String, Object>> fields;
    private final Map<String, String> values;

    public MappingRequiredException(String importType, List<String> headers,
                                    List<Map<String, Object>> fields, Map<String, String> values) {
        super("无法自动识别 CSV 列，请手动指定");
        this.importType = importType;
        this.headers = new ArrayList<>(headers);
        this.fields = new ArrayList<>(fields);
        this.values = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    public static Map<String, Object> field(String key, String label, boolean required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("label", label);
        map.put("required", required);
        return map;
    }

    public String importType() {
        return importType;
    }

    public List<String> headers() {
        return headers;
    }

    public List<Map<String, Object>> fields() {
        return fields;
    }

    public Map<String, String> values() {
        return values;
    }
}
