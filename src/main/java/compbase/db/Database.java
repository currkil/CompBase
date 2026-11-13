package compbase.db;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

public class Database {
    private final String url;

    public Database(String dbPath) throws SQLException {
        try {
            Path parent = Path.of(dbPath).getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new SQLException("Cannot create db directory", e);
        }
        this.url = "jdbc:sqlite:" + dbPath;
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        // 外键约束按连接生效，每次连接后都要开
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    public void initSchema(String resourceName) {
        try (Connection conn = getConnection();
             InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) throw new IllegalStateException("schema not found: " + resourceName);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement st = conn.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) st.execute(part);
                }
            }
            migrate(conn);
        } catch (Exception e) {
            throw new RuntimeException("Failed to init schema", e);
        }
    }

    /**
     * 旧库的增量迁移。
     * CREATE TABLE IF NOT EXISTS 不会给已存在的表补列，这里只补齐能安全添加的列
     * （ALTER TABLE ... ADD COLUMN 不支持带非常量默认值，所以时间戳由代码写入）。
     */
    private void migrate(Connection conn) throws SQLException {
        ensureColumns(conn, "component", new String[][]{
                {"created_at", "TEXT"},
                {"updated_at", "TEXT"}
        });
        ensureColumns(conn, "package", new String[][]{
                {"full_name", "TEXT"},
                {"pin_count", "INTEGER"},
                {"pitch", "REAL"},
                {"model_file", "TEXT"},
                {"model_format", "TEXT"},
                {"description", "TEXT"}
        });
    }

    private void ensureColumns(Connection conn, String table, String[][] columns) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        try (Statement st = conn.createStatement()) {
            for (String[] column : columns) {
                if (!existing.contains(column[0])) {
                    st.execute("ALTER TABLE " + table + " ADD COLUMN " + column[0] + " " + column[1]);
                }
            }
        }
    }
}
