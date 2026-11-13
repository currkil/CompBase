package compbase.repository;

import compbase.db.Database;
import compbase.util.Json;
import compbase.util.Rows;
import compbase.util.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 导入日志：所有 CSV 导入（基础数据 + BOM）都写一条。 */
public class ImportLogRepository {
    private static final String[] COLUMNS = {
            "log_id", "import_type", "file_name", "duplicate_strategy",
            "total_rows", "success_rows", "skipped_rows", "failed_rows",
            "errors", "created_at", "bom_id"
    };

    private final Database db;

    public ImportLogRepository(Database db) {
        this.db = db;
    }

    public long insert(Connection conn, String importType, String fileName, String strategy,
                       int total, int success, int skipped, int failed, List<String> errors)
            throws SQLException {
        String sql = "INSERT INTO import_log (import_type, file_name, duplicate_strategy, " +
                "total_rows, success_rows, skipped_rows, failed_rows, errors, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now','localtime'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, importType);
            Rows.set(ps, 2, fileName);
            Rows.set(ps, 3, strategy);
            ps.setInt(4, total);
            ps.setInt(5, success);
            ps.setInt(6, skipped);
            ps.setInt(7, failed);
            ps.setString(8, Json.write(errors == null ? new ArrayList<String>() : errors));
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    /** 自动开连接的版本。 */
    public long insert(String importType, String fileName, String strategy,
                       int total, int success, int skipped, int failed, List<String> errors)
            throws SQLException {
        try (Connection conn = db.getConnection()) {
            return insert(conn, importType, fileName, strategy, total, success, skipped, failed, errors);
        }
    }

    /** 列表（按时间倒序），type 为空表示全部。 */
    public List<Map<String, Object>> list(String importType) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT l.log_id, l.import_type, l.file_name, l.duplicate_strategy, l.total_rows, " +
                "l.success_rows, l.skipped_rows, l.failed_rows, l.errors, l.created_at, " +
                "(SELECT b.bom_id FROM bom_import b WHERE b.log_id = l.log_id) AS bom_id " +
                "FROM import_log l ");
        if (importType != null && !importType.isBlank()) {
            sql.append("WHERE import_type = ? ");
        }
        sql.append("ORDER BY log_id DESC");

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (importType != null && !importType.isBlank()) {
                ps.setString(1, importType.toUpperCase());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(decorate(Rows.map(rs, COLUMNS)));
                }
            }
        }
        return list;
    }

    public Map<String, Object> find(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT l.log_id, l.import_type, l.file_name, l.duplicate_strategy, l.total_rows, " +
                     "l.success_rows, l.skipped_rows, l.failed_rows, l.errors, l.created_at, " +
                     "(SELECT b.bom_id FROM bom_import b WHERE b.log_id = l.log_id) AS bom_id " +
                     "FROM import_log l WHERE l.log_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? decorate(Rows.map(rs, COLUMNS)) : null;
            }
        }
    }

    /** 把 errors 的 JSON 字符串换成数组，方便前端直接用。 */
    private Map<String, Object> decorate(Map<String, Object> row) {
        Object raw = row.get("errors");
        List<Object> errors = new ArrayList<>();
        if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                Object parsed = Json.parse(String.valueOf(raw));
                if (parsed instanceof List) {
                    for (Object item : (List<?>) parsed) errors.add(item);
                }
            } catch (IllegalArgumentException ignored) {
                errors.add(String.valueOf(raw));
            }
        }
        row.put("errors", errors);
        row.put("log_id", Values.toLong(row.get("log_id")));
        return row;
    }
}
