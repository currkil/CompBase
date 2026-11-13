package compbase.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDBC 小工具：把 ResultSet 的一行映射成 Map，以及按值类型安全地绑定参数。
 * 全部数据都以 Map 形式进出，避免为每张表写一遍实体类的样板代码。
 */
public final class Rows {
    private Rows() {
    }

    /** 按给定列名（或别名）取出整行，保持列顺序。 */
    public static Map<String, Object> map(ResultSet rs, String... columns) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
            row.put(column, rs.getObject(column));
        }
        return row;
    }

    /** 关闭自动提交并开启一个事务。 */
    public static void begin(java.sql.Connection conn) throws SQLException {
        if (conn.getAutoCommit()) conn.setAutoCommit(false);
    }

    /** 提交事务并恢复自动提交。 */
    public static void commit(java.sql.Connection conn) throws SQLException {
        conn.commit();
        conn.setAutoCommit(true);
    }

    /** 回滚事务并恢复自动提交。 */
    public static void rollback(java.sql.Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败时交给上层的连接关闭处理
        }
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }

    /** 只取一个计数结果。 */
    public static long count(java.sql.Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                set(ps, i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 取 SQLite 的 last_insert_rowid()。 */
    public static long lastInsertId(java.sql.Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_insert_rowid()");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** 按运行时类型绑定参数，null 用 SQL NULL。 */
    public static void set(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
        } else if (value instanceof Long) {
            ps.setLong(index, (Long) value);
        } else if (value instanceof Integer) {
            ps.setInt(index, (Integer) value);
        } else if (value instanceof Short) {
            ps.setShort(index, (Short) value);
        } else if (value instanceof Double) {
            ps.setDouble(index, (Double) value);
        } else if (value instanceof Float) {
            ps.setFloat(index, (Float) value);
        } else if (value instanceof Number) {
            ps.setDouble(index, ((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            ps.setInt(index, ((Boolean) value) ? 1 : 0);
        } else {
            ps.setString(index, String.valueOf(value));
        }
    }
}
