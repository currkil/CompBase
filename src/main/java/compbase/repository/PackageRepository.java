package compbase.repository;

import compbase.db.Database;
import compbase.util.ConflictException;
import compbase.util.PackageNameParser;
import compbase.util.Rows;
import compbase.util.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 封装类型 / 封装维护（直插、贴片 -> DIP-8、0603）。
 * 封装带 KiCad 完整名、引脚数、间距与 3D 模型文件（模型本身在 static/models/）。
 */
public class PackageRepository {
    private static final String[] PACKAGE_COLUMNS = {
            "package_id", "pkg_type_id", "package_name", "full_name", "pin_count",
            "pitch", "model_file", "model_format", "description"
    };

    private static final String PACKAGE_SELECT =
            "SELECT p.package_id AS package_id, p.pkg_type_id AS pkg_type_id, " +
            "       p.package_name AS package_name, p.full_name AS full_name, " +
            "       p.pin_count AS pin_count, p.pitch AS pitch, " +
            "       p.model_file AS model_file, p.model_format AS model_format, " +
            "       p.description AS description, pt.pkg_type_name AS pkg_type_name " +
            "FROM package p LEFT JOIN package_type pt ON pt.pkg_type_id = p.pkg_type_id ";

    private static final Pattern MODEL_NAME = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]*\\.(glb|gltf|obj)$", Pattern.CASE_INSENSITIVE);

    private final Database db;

    public PackageRepository(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> listPackageTypes() throws SQLException {
        List<Map<String, Object>> types = new ArrayList<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pkg_type_id, pkg_type_name FROM package_type ORDER BY pkg_type_name, pkg_type_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = Rows.map(rs, "pkg_type_id", "pkg_type_name");
                    row.put("packages", new ArrayList<Map<String, Object>>());
                    types.add(row);
                    byId.put(Values.toLong(row.get("pkg_type_id")), row);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT package_id, pkg_type_id, package_name, full_name, pin_count, pitch, " +
                    "       model_file, model_format, description FROM package " +
                    "ORDER BY package_name, package_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> pkg = Rows.map(rs, PACKAGE_COLUMNS);
                    Map<String, Object> parent = byId.get(Values.toLong(pkg.get("pkg_type_id")));
                    if (parent != null) packagesOf(parent).add(pkg);
                }
            }
        }
        return types;
    }

    public List<Map<String, Object>> listPackages(Long pkgTypeId) throws SQLException {
        StringBuilder sql = new StringBuilder(PACKAGE_SELECT);
        if (pkgTypeId != null) sql.append("WHERE p.pkg_type_id = ? ");
        sql.append("ORDER BY pt.pkg_type_name, p.package_name, p.package_id");

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (pkgTypeId != null) ps.setLong(1, pkgTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(Rows.map(rs, "package_id", "pkg_type_id", "package_name", "full_name",
                            "pin_count", "pitch", "model_file", "model_format", "description",
                            "pkg_type_name"));
                }
            }
        }
        return list;
    }

    // ==================== 封装类型 ====================

    public Map<String, Object> findPackageType(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pkg_type_id, pkg_type_name FROM package_type WHERE pkg_type_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Rows.map(rs, "pkg_type_id", "pkg_type_name") : null;
            }
        }
    }

    public long createPackageType(String name) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO package_type (pkg_type_name) VALUES (?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public boolean updatePackageType(long id, String name) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE package_type SET pkg_type_name = ? WHERE pkg_type_id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 删除封装类型及其封装；封装仍被元器件引用时拒绝。 */
    public boolean deletePackageType(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            long used = Rows.count(conn,
                    "SELECT COUNT(*) FROM component WHERE package_id IN " +
                    "(SELECT package_id FROM package WHERE pkg_type_id = ?)", id);
            if (used > 0) {
                throw new ConflictException("该封装类型下仍有 " + used + " 个元器件，无法删除");
            }
            Rows.begin(conn);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM package WHERE pkg_type_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                int rows;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM package_type WHERE pkg_type_id = ?")) {
                    ps.setLong(1, id);
                    rows = ps.executeUpdate();
                }
                Rows.commit(conn);
                return rows > 0;
            } catch (SQLException | RuntimeException e) {
                Rows.rollback(conn);
                throw e;
            }
        }
    }

    // ==================== 封装 ====================

    public Map<String, Object> findPackage(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return findPackage(conn, id);
        }
    }

    private Map<String, Object> findPackage(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                PACKAGE_SELECT + "WHERE p.package_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Rows.map(rs, "package_id", "pkg_type_id", "package_name", "full_name",
                        "pin_count", "pitch", "model_file", "model_format", "description",
                        "pkg_type_name")
                        : null;
            }
        }
    }

    public long createPackage(long pkgTypeId, Map<String, Object> data) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return insertPackage(conn, pkgTypeId, data);
        }
    }

    private long insertPackage(Connection conn, long pkgTypeId, Map<String, Object> data)
            throws SQLException {
        String sql = "INSERT INTO package (pkg_type_id, package_name, full_name, pin_count, " +
                "pitch, model_file, model_format, description) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, pkgTypeId);
            ps.setString(2, Values.trimToNull(data.get("package_name")));
            Rows.set(ps, 3, Values.trimToNull(data.get("full_name")));
            Rows.set(ps, 4, Values.toLong(data.get("pin_count")));
            Rows.set(ps, 5, Values.toDouble(data.get("pitch")));
            Rows.set(ps, 6, Values.trimToNull(data.get("model_file")));
            Rows.set(ps, 7, Values.trimToNull(data.get("model_format")));
            Rows.set(ps, 8, Values.trimToNull(data.get("description")));
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    /** 更新封装；pkgTypeId 为空表示保持原封装类型。 */
    public boolean updatePackage(long id, Long pkgTypeId, Map<String, Object> data)
            throws SQLException {
        String sql = pkgTypeId == null
                ? "UPDATE package SET package_name = ?, full_name = ?, pin_count = ?, pitch = ?, " +
                "model_file = ?, model_format = ?, description = ? WHERE package_id = ?"
                : "UPDATE package SET pkg_type_id = ?, package_name = ?, full_name = ?, pin_count = ?, " +
                "pitch = ?, model_file = ?, model_format = ?, description = ? WHERE package_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (pkgTypeId != null) ps.setLong(index++, pkgTypeId);
            ps.setString(index++, Values.trimToNull(data.get("package_name")));
            Rows.set(ps, index++, Values.trimToNull(data.get("full_name")));
            Rows.set(ps, index++, Values.toLong(data.get("pin_count")));
            Rows.set(ps, index++, Values.toDouble(data.get("pitch")));
            Rows.set(ps, index++, Values.trimToNull(data.get("model_file")));
            Rows.set(ps, index++, Values.trimToNull(data.get("model_format")));
            Rows.set(ps, index++, Values.trimToNull(data.get("description")));
            ps.setLong(index, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deletePackage(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            long used = Rows.count(conn, "SELECT COUNT(*) FROM component WHERE package_id = ?", id);
            if (used > 0) {
                throw new ConflictException("该封装正被 " + used + " 个元器件使用，无法删除");
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM package WHERE package_id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    // ==================== 从 3D 模型库导入 ====================

    /**
     * 按模型文件名自动补齐封装信息并创建。
     * 已存在（full_name 或 model_file 相同）时：没有模型的就补上模型，否则跳过。
     *
     * @return {created, updated, existing, failed}，每项都是封装行或失败信息
     */
    public Map<String, Object> importFromModels(long pkgTypeId, List<String> modelFiles)
            throws SQLException {
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> existing = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        try (Connection conn = db.getConnection()) {
            Rows.begin(conn);
            try {
                for (String modelFile : modelFiles) {
                    String name = modelFile == null ? "" : modelFile.trim();
                    try {
                        if (name.isEmpty() || !MODEL_NAME.matcher(name).matches()) {
                            throw new IllegalArgumentException("模型文件名不合法: " + modelFile);
                        }
                        Map<String, Object> suggestion = PackageNameParser.suggest(name);
                        String fullName = Values.toStr(suggestion.get("full_name"));
                        Long existingId = findIdByFullNameOrModel(conn, fullName, name);
                        if (existingId == null) {
                            long id = insertPackage(conn, pkgTypeId, suggestion);
                            created.add(findPackage(conn, id));
                            continue;
                        }
                        Map<String, Object> row = findPackage(conn, existingId);
                        if (row.get("model_file") == null) {
                            fillPackageFromSuggestion(conn, existingId, row, suggestion);
                            updated.add(findPackage(conn, existingId));
                        } else {
                            existing.add(row);
                        }
                    } catch (SQLException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        Map<String, Object> fail = new LinkedHashMap<>();
                        fail.put("model_file", name);
                        fail.put("error", e.getMessage() == null ? "导入失败" : e.getMessage());
                        failed.add(fail);
                    }
                }
                Rows.commit(conn);
            } catch (SQLException | RuntimeException e) {
                Rows.rollback(conn);
                throw e;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("existing", existing);
        result.put("failed", failed);
        return result;
    }

    private Long findIdByFullNameOrModel(Connection conn, String fullName, String modelFile)
            throws SQLException {
        String sql = "SELECT package_id FROM package " +
                "WHERE (full_name IS NOT NULL AND full_name = ?) " +
                "   OR (model_file IS NOT NULL AND model_file = ?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Rows.set(ps, 1, fullName);
            Rows.set(ps, 2, modelFile);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** 已有封装没有模型时，用模型文件推断出的信息补齐（不覆盖已有值）。 */
    private void fillPackageFromSuggestion(Connection conn, long id, Map<String, Object> row,
                                           Map<String, Object> suggestion) throws SQLException {
        String fullName = Values.toStr(row.get("full_name"));
        String packageName = Values.toStr(row.get("package_name"));
        Long pinCount = Values.toLong(row.get("pin_count"));
        Double pitch = Values.toDouble(row.get("pitch"));
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE package SET full_name = ?, pin_count = ?, pitch = ?, " +
                "model_file = ?, model_format = ? WHERE package_id = ?")) {
            Rows.set(ps, 1, fullName == null || fullName.isBlank()
                    ? suggestion.get("full_name") : fullName);
            Rows.set(ps, 2, pinCount != null ? pinCount : suggestion.get("pin_count"));
            Rows.set(ps, 3, pitch != null ? pitch : suggestion.get("pitch"));
            Rows.set(ps, 4, suggestion.get("model_file"));
            Rows.set(ps, 5, suggestion.get("model_format"));
            ps.setLong(6, id);
            ps.executeUpdate();
        }
        // package_name 只在原值为空时补
        if (packageName == null || packageName.isBlank()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE package SET package_name = ? WHERE package_id = ?")) {
                ps.setString(1, Values.toStr(suggestion.get("package_name")));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** 封装格式（扩展名大写），用于展示。 */
    public static String formatOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> packagesOf(Map<String, Object> typeRow) {
        return (List<Map<String, Object>>) typeRow.get("packages");
    }
}
