package compbase.service;

import compbase.db.Database;
import compbase.repository.ImportLogRepository;
import compbase.util.CsvTable;
import compbase.util.MappingRequiredException;
import compbase.util.Rows;
import compbase.util.SheetReader;
import compbase.util.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础数据批量导入（CSV / xlsx）：类型 / 子类型 / 封装类型 / 封装 / 位置区 / 位置。
 * <p>
 * 预览（preview=true）与真正导入走同一套逻辑：都在事务里真实执行，
 * 预览结束回滚，因此预览里的「重复 / 跳过」判断与最终结果一致。
 */
public class BaseDataImportService {
    public static final String TYPE = "TYPE";
    public static final String SUBTYPE = "SUBTYPE";
    public static final String PACKAGE_TYPE = "PACKAGE_TYPE";
    public static final String PACKAGE = "PACKAGE";
    public static final String ZONE = "ZONE";
    public static final String LOCATION = "LOCATION";

    private static final String SKIP = "SKIP";
    private static final String UPDATE = "UPDATE";
    private static final String INSERT_ALL = "INSERT_ALL";

    private static final String[] TYPE_NAME = {"类型名", "type", "name", "名称", "类型"};
    private static final String[] SUBTYPE_NAME = {"子类型名", "subtype", "子类型", "名称"};
    private static final String[] PACKAGE_TYPE_NAME =
            {"封装类型名", "package_type", "封装类型", "名称", "type"};
    private static final String[] PACKAGE_NAME =
            {"具体封装名", "package", "封装名", "封装", "名称"};
    private static final String[] ZONE_NAME = {"位置区名", "zone", "位置区", "名称"};
    private static final String[] DESCRIPTION = {"描述", "description", "desc", "备注"};
    private static final String[] TYPE_PARENT = {"类型名", "type", "父类型", "类型"};
    private static final String[] PACKAGE_TYPE_PARENT =
            {"封装类型名", "package_type", "封装类型", "父类型"};
    private static final String[] ZONE_PARENT = {"位置区名", "zone", "位置区", "父类型"};
    private static final String[] LOCATION_CODE =
            {"位置编号", "location_code", "编号", "区内编号"};
    private static final String[] PACKAGE_FULL_NAME =
            {"完整封装名", "full_name", "封装全名", "完整名称", "kicad封装名"};
    private static final String[] PACKAGE_PIN_COUNT =
            {"引脚数", "pin_count", "pins", "引脚"};
    private static final String[] PACKAGE_PITCH = {"引脚间距", "pitch", "间距"};
    private static final String[] PACKAGE_MODEL_FILE =
            {"模型文件", "model_file", "model", "3d模型"};
    private static final String[] PACKAGE_MODEL_FORMAT =
            {"模型格式", "model_format", "格式"};

    private final Database db;
    private final ImportLogRepository logRepository;

    public BaseDataImportService(Database db, ImportLogRepository logRepository) {
        this.db = db;
        this.logRepository = logRepository;
    }

    public Map<String, Object> importData(String importType, byte[] bytes, String fileName,
                                          String strategy, boolean preview,
                                          Map<String, Integer> columnOverrides)
            throws SQLException {
        String type = importType == null ? "" : importType.trim().toUpperCase();
        if (!isSupported(type)) {
            throw new IllegalArgumentException("不支持的导入类型: " + importType);
        }
        String strategyValue = normalizeStrategy(strategy);

        List<List<String>> rawRows = SheetReader.readRows(fileName, bytes);
        int headerRow = CsvTable.detectHeaderRow(rawRows, detectGroups(type));
        CsvTable table = CsvTable.from(rawRows, headerRow);
        if (table.displayHeaders().isEmpty()) {
            throw new IllegalArgumentException("文件内容为空或缺少表头");
        }
        if (table.rowCount() == 0) {
            throw new IllegalArgumentException("文件没有数据行");
        }

        Columns columns = resolveColumns(type, table, columnOverrides);
        Summary summary = new Summary(table.rowCount());

        long logId = -1;
        try (Connection conn = db.getConnection()) {
            Rows.begin(conn);
            try {
                importRows(conn, type, table, columns, strategyValue, summary);
                if (!preview) {
                    logId = logRepository.insert(conn, type,
                            fileName == null ? "import.file" : fileName, strategyValue,
                            summary.total, summary.success, summary.skipped, summary.failed,
                            summary.errors);
                }
                if (preview) {
                    Rows.rollback(conn);
                } else {
                    Rows.commit(conn);
                }
            } catch (SQLException | RuntimeException e) {
                Rows.rollback(conn);
                throw e;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("import_type", type);
        result.put("preview", preview);
        result.put("duplicate_strategy", strategyValue);
        result.put("total", summary.total);
        result.put("success", summary.success);
        result.put("skipped", summary.skipped);
        result.put("failed", summary.failed);
        result.put("rows", summary.rows);
        result.put("errors", summary.errors);
        result.put("log_id", logId < 0 ? null : logId);
        return result;
    }

    // ==================== 列识别 ====================

    private static final class Columns {
        int name = -1;
        int description = -1;
        int parent = -1;
        int code = -1;
        int fullName = -1;
        int pinCount = -1;
        int pitch = -1;
        int modelFile = -1;
        int modelFormat = -1;
    }

    private Columns resolveColumns(String type, CsvTable table, Map<String, Integer> overrides) {
        Columns columns = new Columns();
        List<Map<String, Object>> requiredFields = new ArrayList<>();

        switch (type) {
            case TYPE:
                columns.name = pick(table, overrides, "name_col", TYPE_NAME);
                columns.description = pick(table, overrides, "description_col", DESCRIPTION);
                requiredFields.add(MappingRequiredException.field("name_col", "类型名", true));
                requiredFields.add(MappingRequiredException.field("description_col", "描述", false));
                break;
            case SUBTYPE:
                columns.parent = pick(table, overrides, "parent_col", TYPE_PARENT);
                columns.name = pick(table, overrides, "name_col", SUBTYPE_NAME);
                columns.description = pick(table, overrides, "description_col", DESCRIPTION);
                requiredFields.add(MappingRequiredException.field("parent_col", "类型名", true));
                requiredFields.add(MappingRequiredException.field("name_col", "子类型名", true));
                requiredFields.add(MappingRequiredException.field("description_col", "描述", false));
                break;
            case PACKAGE_TYPE:
                columns.name = pick(table, overrides, "name_col", PACKAGE_TYPE_NAME);
                requiredFields.add(MappingRequiredException.field("name_col", "封装类型名", true));
                break;
            case PACKAGE:
                columns.parent = pick(table, overrides, "parent_col", PACKAGE_TYPE_PARENT);
                columns.name = pick(table, overrides, "name_col", PACKAGE_NAME);
                columns.fullName = pick(table, overrides, "full_name_col", PACKAGE_FULL_NAME);
                columns.pinCount = pick(table, overrides, "pin_count_col", PACKAGE_PIN_COUNT);
                columns.pitch = pick(table, overrides, "pitch_col", PACKAGE_PITCH);
                columns.modelFile = pick(table, overrides, "model_file_col", PACKAGE_MODEL_FILE);
                columns.modelFormat = pick(table, overrides, "model_format_col", PACKAGE_MODEL_FORMAT);
                columns.description = pick(table, overrides, "description_col", DESCRIPTION);
                requiredFields.add(MappingRequiredException.field("parent_col", "封装类型名", true));
                requiredFields.add(MappingRequiredException.field("name_col", "具体封装名", true));
                requiredFields.add(MappingRequiredException.field("full_name_col", "完整封装名", false));
                requiredFields.add(MappingRequiredException.field("pin_count_col", "引脚数", false));
                requiredFields.add(MappingRequiredException.field("pitch_col", "引脚间距", false));
                requiredFields.add(MappingRequiredException.field("model_file_col", "模型文件", false));
                requiredFields.add(MappingRequiredException.field("model_format_col", "模型格式", false));
                requiredFields.add(MappingRequiredException.field("description_col", "描述", false));
                break;
            case ZONE:
                columns.name = pick(table, overrides, "name_col", ZONE_NAME);
                columns.description = pick(table, overrides, "description_col", DESCRIPTION);
                requiredFields.add(MappingRequiredException.field("name_col", "位置区名", true));
                requiredFields.add(MappingRequiredException.field("description_col", "描述", false));
                break;
            case LOCATION:
                columns.parent = pick(table, overrides, "parent_col", ZONE_PARENT);
                columns.code = pick(table, overrides, "code_col", LOCATION_CODE);
                columns.description = pick(table, overrides, "description_col", DESCRIPTION);
                requiredFields.add(MappingRequiredException.field("parent_col", "位置区名", true));
                requiredFields.add(MappingRequiredException.field("code_col", "位置编号", true));
                requiredFields.add(MappingRequiredException.field("description_col", "描述", false));
                break;
            default:
                throw new IllegalArgumentException("不支持的导入类型: " + type);
        }

        boolean missingName = LOCATION.equals(type) ? columns.code < 0 : columns.name < 0;
        boolean missing = missingName || (needsParent(type) && columns.parent < 0);
        if (missing) {
            Map<String, String> values = new LinkedHashMap<>();
            if (!LOCATION.equals(type)) {
                values.put("name_col", String.valueOf(Math.max(columns.name, 0)));
            }
            values.put("description_col", String.valueOf(Math.max(columns.description, 0)));
            if (needsParent(type)) {
                values.put("parent_col", String.valueOf(Math.max(columns.parent, 0)));
            }
            if (LOCATION.equals(type)) {
                values.put("code_col", String.valueOf(Math.max(columns.code, 0)));
            }
            throw new MappingRequiredException(type, table.displayHeaders(), requiredFields, values);
        }
        return columns;
    }

    /** 用于表头识别的必需列分组。 */
    private static List<String[]> detectGroups(String type) {
        List<String[]> groups = new ArrayList<>();
        if (LOCATION.equals(type)) {
            groups.add(ZONE_PARENT);
            groups.add(LOCATION_CODE);
        } else {
            groups.add(nameCandidates(type));
            if (needsParent(type)) groups.add(parentCandidates(type));
        }
        return groups;
    }

    private static String[] nameCandidates(String type) {
        switch (type) {
            case SUBTYPE:
                return SUBTYPE_NAME;
            case PACKAGE_TYPE:
                return PACKAGE_TYPE_NAME;
            case PACKAGE:
                return PACKAGE_NAME;
            case ZONE:
                return ZONE_NAME;
            case TYPE:
            default:
                return TYPE_NAME;
        }
    }

    private static String[] parentCandidates(String type) {
        switch (type) {
            case PACKAGE:
                return PACKAGE_TYPE_PARENT;
            case ZONE:
            case LOCATION:
                return ZONE_PARENT;
            case SUBTYPE:
            default:
                return TYPE_PARENT;
        }
    }

    private static boolean needsParent(String type) {
        return SUBTYPE.equals(type) || PACKAGE.equals(type) || LOCATION.equals(type);
    }

    private int pick(CsvTable table, Map<String, Integer> overrides, String key, String... candidates) {
        Integer override = overrides == null ? null : overrides.get(key);
        if (override != null) {
            if (override < 0 || override >= table.headers().size()) {
                throw new IllegalArgumentException("列号超出范围: " + key + "=" + override);
            }
            return override;
        }
        return table.column(candidates);
    }

    private static String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return SKIP;
        String value = strategy.trim().toUpperCase();
        if (SKIP.equals(value) || UPDATE.equals(value) || INSERT_ALL.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("重复处理策略只能是 SKIP / UPDATE / INSERT_ALL");
    }

    private static boolean isSupported(String type) {
        return TYPE.equals(type) || SUBTYPE.equals(type) || PACKAGE_TYPE.equals(type)
                || PACKAGE.equals(type) || ZONE.equals(type) || LOCATION.equals(type);
    }

    // ==================== 逐行导入 ====================

    private void importRows(Connection conn, String type, CsvTable table, Columns columns,
                            String strategy, Summary summary) throws SQLException {
        for (int row = 0; row < table.rowCount(); row++) {
            int line = row + 2;
            switch (type) {
                case TYPE:
                    importType(conn, table, columns, strategy, summary, row, line);
                    break;
                case SUBTYPE:
                    importSubtype(conn, table, columns, strategy, summary, row, line);
                    break;
                case PACKAGE_TYPE:
                    importPackageType(conn, table, columns, strategy, summary, row, line);
                    break;
                case PACKAGE:
                    importPackage(conn, table, columns, strategy, summary, row, line);
                    break;
                case ZONE:
                    importZone(conn, table, columns, strategy, summary, row, line);
                    break;
                case LOCATION:
                    importLocation(conn, table, columns, strategy, summary, row, line);
                    break;
                default:
                    throw new IllegalArgumentException("不支持的导入类型: " + type);
            }
        }
    }

    private void importType(Connection conn, CsvTable table, Columns columns,
                            String strategy, Summary summary, int row, int line) throws SQLException {
        String name = table.cell(row, columns.name);
        if (name.isEmpty()) {
            summary.error(line, "", "类型名为空");
            return;
        }
        Long existing = findId(conn, "SELECT type_id FROM type WHERE type_name = ?", name);
        String description = descCell(table, row, columns.description);
        if (existing != null && !INSERT_ALL.equals(strategy)) {
            existing(conn, strategy, summary, line, name, existing, description,
                    "UPDATE type SET description = ? WHERE type_id = ?");
            return;
        }
        insert(conn, "INSERT INTO type (type_name, description) VALUES (?, ?)", name, description);
        summary.insert(line, name);
    }

    private void importSubtype(Connection conn, CsvTable table, Columns columns,
                               String strategy, Summary summary, int row, int line) throws SQLException {
        String parent = table.cell(row, columns.parent);
        String name = table.cell(row, columns.name);
        if (parent.isEmpty()) {
            summary.error(line, name, "类型名为空");
            return;
        }
        if (name.isEmpty()) {
            summary.error(line, parent, "子类型名为空");
            return;
        }
        Long typeId = findId(conn, "SELECT type_id FROM type WHERE type_name = ?", parent);
        if (typeId == null) {
            summary.error(line, parent + " / " + name, "类型名不存在：" + parent);
            return;
        }
        Long existing = findId(conn,
                "SELECT subtype_id FROM subtype WHERE type_id = ? AND subtype_name = ?", typeId, name);
        String description = descCell(table, row, columns.description);
        if (existing != null && !INSERT_ALL.equals(strategy)) {
            existing(conn, strategy, summary, line, parent + " / " + name, existing, description,
                    "UPDATE subtype SET description = ? WHERE subtype_id = ?");
            return;
        }
        insert(conn, "INSERT INTO subtype (type_id, subtype_name, description) VALUES (?, ?, ?)",
                typeId, name, description);
        summary.insert(line, parent + " / " + name);
    }

    private void importPackageType(Connection conn, CsvTable table, Columns columns,
                                   String strategy, Summary summary, int row, int line)
            throws SQLException {
        String name = table.cell(row, columns.name);
        if (name.isEmpty()) {
            summary.error(line, "", "封装类型名为空");
            return;
        }
        Long existing = findId(conn,
                "SELECT pkg_type_id FROM package_type WHERE pkg_type_name = ?", name);
        if (existing != null && !INSERT_ALL.equals(strategy)) {
            existing(conn, strategy, summary, line, name, existing, null, null);
            return;
        }
        insert(conn, "INSERT INTO package_type (pkg_type_name) VALUES (?)", name);
        summary.insert(line, name);
    }

    private void importPackage(Connection conn, CsvTable table, Columns columns,
                               String strategy, Summary summary, int row, int line) throws SQLException {
        String parent = table.cell(row, columns.parent);
        String name = table.cell(row, columns.name);
        if (parent.isEmpty()) {
            summary.error(line, name, "封装类型名为空");
            return;
        }
        if (name.isEmpty()) {
            summary.error(line, parent, "具体封装名为空");
            return;
        }
        Long pkgTypeId = findId(conn,
                "SELECT pkg_type_id FROM package_type WHERE pkg_type_name = ?", parent);
        if (pkgTypeId == null) {
            summary.error(line, parent + " / " + name, "封装类型不存在：" + parent);
            return;
        }
        Map<String, Object> data = packageData(table, row, columns);
        String key = parent + " / " + name;

        Long existing = findId(conn,
                "SELECT package_id FROM package WHERE pkg_type_id = ? AND package_name = ?",
                pkgTypeId, name);
        if (existing != null && !INSERT_ALL.equals(strategy)) {
            if (!UPDATE.equals(strategy)) {
                summary.skip(line, key, "已存在");
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE package SET package_name = ?, full_name = ?, pin_count = ?, pitch = ?, " +
                    "model_file = ?, model_format = ?, description = ? WHERE package_id = ?")) {
                ps.setString(1, name);
                Rows.set(ps, 2, data.get("full_name"));
                Rows.set(ps, 3, data.get("pin_count"));
                Rows.set(ps, 4, data.get("pitch"));
                Rows.set(ps, 5, data.get("model_file"));
                Rows.set(ps, 6, data.get("model_format"));
                Rows.set(ps, 7, data.get("description"));
                ps.setLong(8, existing);
                ps.executeUpdate();
            }
            summary.update(line, key);
            return;
        }
        insert(conn, "INSERT INTO package (pkg_type_id, package_name, full_name, pin_count, pitch, " +
                        "model_file, model_format, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                pkgTypeId,
                name,
                data.get("full_name"),
                data.get("pin_count"),
                data.get("pitch"),
                data.get("model_file"),
                data.get("model_format"),
                data.get("description"));
        summary.insert(line, key);
    }

    /** 封装行的可选字段（完整名、引脚数、间距、模型、描述），缺列时为 null。 */
    private static Map<String, Object> packageData(CsvTable table, int row, Columns columns) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("package_name", table.cell(row, columns.name));
        data.put("full_name", cellOrNull(table, row, columns.fullName));
        data.put("pin_count", parseLongOrNull(table.cell(row, columns.pinCount)));
        data.put("pitch", parseDoubleOrNull(table.cell(row, columns.pitch)));
        data.put("model_file", cellOrNull(table, row, columns.modelFile));
        data.put("model_format", normalizeModelFormat(cellOrNull(table, row, columns.modelFormat)));
        data.put("description", cellOrNull(table, row, columns.description));
        return data;
    }

    private static String cellOrNull(CsvTable table, int row, int column) {
        if (column < 0) return null;
        return Values.trimToNull(table.cell(row, column));
    }

    private static Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 只接受 OBJ / GLB / GLTF，其它值当作未填。 */
    private static String normalizeModelFormat(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase();
        if ("OBJ".equals(value) || "GLB".equals(value) || "GLTF".equals(value)) return value;
        return null;
    }

    private void importZone(Connection conn, CsvTable table, Columns columns,
                            String strategy, Summary summary, int row, int line) throws SQLException {
        String name = table.cell(row, columns.name);
        if (name.isEmpty()) {
            summary.error(line, "", "位置区名为空");
            return;
        }
        Long existing = findId(conn, "SELECT zone_id FROM zone WHERE zone_name = ?", name);
        String description = descCell(table, row, columns.description);
        if (existing != null && !INSERT_ALL.equals(strategy)) {
            existing(conn, strategy, summary, line, name, existing, description,
                    "UPDATE zone SET description = ? WHERE zone_id = ?");
            return;
        }
        insert(conn, "INSERT INTO zone (zone_name, description) VALUES (?, ?)", name, description);
        summary.insert(line, name);
    }

    private void importLocation(Connection conn, CsvTable table, Columns columns,
                                String strategy, Summary summary, int row, int line)
            throws SQLException {
        String parent = table.cell(row, columns.parent);
        String code = table.cell(row, columns.code);
        if (parent.isEmpty()) {
            summary.error(line, code, "位置区名为空");
            return;
        }
        if (code.isEmpty()) {
            summary.error(line, parent, "位置编号为空");
            return;
        }
        Long zoneId = findId(conn, "SELECT zone_id FROM zone WHERE zone_name = ?", parent);
        if (zoneId == null) {
            summary.error(line, parent + " / " + code, "位置区不存在：" + parent);
            return;
        }
        Long existing = findId(conn,
                "SELECT location_id FROM location WHERE zone_id = ? AND location_code = ?",
                zoneId, code);
        String description = descCell(table, row, columns.description);
        if (existing != null && !INSERT_ALL.equals(strategy)) {
            existing(conn, strategy, summary, line, parent + " / " + code, existing, description,
                    "UPDATE location SET description = ? WHERE location_id = ?");
            return;
        }
        insert(conn, "INSERT INTO location (zone_id, location_code, description) VALUES (?, ?, ?)",
                zoneId, code, description);
        summary.insert(line, parent + " / " + code);
    }

    // ==================== 小工具 ====================

    private void existing(Connection conn, String strategy, Summary summary, int line, String key,
                          long existingId, String description, String updateSql) throws SQLException {
        if (!UPDATE.equals(strategy)) {
            summary.skip(line, key, "已存在");
            return;
        }
        if (updateSql == null) {
            summary.skip(line, key, "该数据类型没有可更新的描述字段");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            Rows.set(ps, 1, description);
            ps.setLong(2, existingId);
            ps.executeUpdate();
        }
        summary.update(line, key);
    }

    private Long findId(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Rows.set(ps, i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long insert(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Rows.set(ps, i + 1, args[i]);
            }
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    private static String descCell(CsvTable table, int row, int column) {
        if (column < 0) return null;
        return Values.trimToNull(table.cell(row, column));
    }

    /** 导入结果收集器。 */
    private static final class Summary {
        int total;
        int success;
        int skipped;
        int failed;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        Summary(int total) {
            this.total = total;
        }

        void insert(int line, String key) {
            success++;
            add(line, "insert", key, null);
        }

        void update(int line, String key) {
            success++;
            add(line, "update", key, "已更新描述");
        }

        void skip(int line, String key, String message) {
            skipped++;
            add(line, "skip", key, message);
        }

        void error(int line, String key, String message) {
            failed++;
            errors.add("第" + line + "行：" + message);
            add(line, "error", key, message);
        }

        private void add(int line, String action, String key, String message) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line", line);
            row.put("action", action);
            row.put("key", key);
            row.put("message", message);
            rows.add(row);
        }
    }
}
