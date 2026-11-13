package compbase.repository;

import compbase.db.Database;
import compbase.util.Rows;
import compbase.util.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 元器件查询与维护。
 * 可用数量不落库，按 stock_qty - occupied_qty 在 SQL 里即时计算；
 * 位置显示名按「位置区-区内编号」拼接，避免前端重复处理 null。
 */
public class ComponentRepository {
    /** 所有列表查询共用的列（含联表得到的名称列）。 */
    private static final String[] COLUMNS = {
            "id", "type_id", "subtype_id", "value", "package_id", "location_id",
            "stock_qty", "occupied_qty", "available_qty", "price",
            "type_name", "subtype_name",
            "pkg_type_id", "pkg_type_name", "package_name",
            "zone_id", "zone_name", "location_code", "created_at", "updated_at"
    };

    private static final String SELECT =
            "SELECT c.id AS id, c.type_id AS type_id, c.subtype_id AS subtype_id, c.value AS value, " +
            "       c.package_id AS package_id, c.location_id AS location_id, " +
            "       c.stock_qty AS stock_qty, c.occupied_qty AS occupied_qty, " +
            "       (c.stock_qty - c.occupied_qty) AS available_qty, c.price AS price, " +
            "       t.type_name AS type_name, s.subtype_name AS subtype_name, " +
            "       p.pkg_type_id AS pkg_type_id, pt.pkg_type_name AS pkg_type_name, " +
            "       p.package_name AS package_name, " +
            "       z.zone_id AS zone_id, z.zone_name AS zone_name, l.location_code AS location_code, " +
            "       c.created_at AS created_at, c.updated_at AS updated_at " +
            "FROM component c " +
            "LEFT JOIN type t       ON t.type_id = c.type_id " +
            "LEFT JOIN subtype s    ON s.subtype_id = c.subtype_id " +
            "LEFT JOIN package p    ON p.package_id = c.package_id " +
            "LEFT JOIN package_type pt ON pt.pkg_type_id = p.pkg_type_id " +
            "LEFT JOIN location l   ON l.location_id = c.location_id " +
            "LEFT JOIN zone z       ON z.zone_id = l.zone_id ";

    private final Database db;

    public ComponentRepository(Database db) {
        this.db = db;
    }

    /** 条件查询，filters 支持 q / type_id / subtype_id / package_id / location_id / zone_id。 */
    public List<Map<String, Object>> findAll(Map<String, String> filters) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        String keyword = Values.trimToNull(filters.get("q"));
        if (keyword != null) {
            conditions.add("(c.value LIKE ? OR t.type_name LIKE ? OR s.subtype_name LIKE ?" +
                    " OR p.package_name LIKE ? OR pt.pkg_type_name LIKE ?" +
                    " OR z.zone_name LIKE ? OR l.location_code LIKE ?)");
            String like = "%" + keyword + "%";
            for (int i = 0; i < 7; i++) args.add(like);
        }
        addEquals(conditions, args, filters, "type_id", "c.type_id");
        addEquals(conditions, args, filters, "subtype_id", "c.subtype_id");
        addEquals(conditions, args, filters, "package_id", "c.package_id");
        addEquals(conditions, args, filters, "location_id", "c.location_id");
        addEquals(conditions, args, filters, "zone_id", "l.zone_id");

        if (!conditions.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", conditions)).append(' ');
        }
        sql.append("ORDER BY t.type_name IS NULL, t.type_name, c.value, c.id");

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Rows.set(ps, i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    public Map<String, Object> findById(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT + "WHERE c.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    /** 新增，返回自增主键。 */
    public long create(Map<String, Object> data) throws SQLException {
        String sql = "INSERT INTO component " +
                "(type_id, subtype_id, value, package_id, location_id, stock_qty, occupied_qty, price, " +
                " created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now','localtime'), datetime('now','localtime'))";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Rows.set(ps, 1, data.get("type_id"));
            Rows.set(ps, 2, data.get("subtype_id"));
            Rows.set(ps, 3, Values.trimToNull(data.get("value")));
            Rows.set(ps, 4, data.get("package_id"));
            Rows.set(ps, 5, data.get("location_id"));
            Rows.set(ps, 6, data.get("stock_qty") == null ? 0L : data.get("stock_qty"));
            Rows.set(ps, 7, data.get("occupied_qty") == null ? 0L : data.get("occupied_qty"));
            Rows.set(ps, 8, data.get("price"));
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    /** 整行更新（PUT 语义：未提供的字段会被清空），返回是否命中。 */
    public boolean update(long id, Map<String, Object> data) throws SQLException {
        String sql = "UPDATE component SET type_id = ?, subtype_id = ?, value = ?, package_id = ?, " +
                "location_id = ?, stock_qty = ?, occupied_qty = ?, price = ?, " +
                "updated_at = datetime('now','localtime') WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Rows.set(ps, 1, data.get("type_id"));
            Rows.set(ps, 2, data.get("subtype_id"));
            Rows.set(ps, 3, Values.trimToNull(data.get("value")));
            Rows.set(ps, 4, data.get("package_id"));
            Rows.set(ps, 5, data.get("location_id"));
            Rows.set(ps, 6, data.get("stock_qty") == null ? 0L : data.get("stock_qty"));
            Rows.set(ps, 7, data.get("occupied_qty") == null ? 0L : data.get("occupied_qty"));
            Rows.set(ps, 8, data.get("price"));
            Rows.set(ps, 9, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 按增量调整数量，结果不会小于 0；返回调整后的整行，不存在时返回 null。 */
    public Map<String, Object> adjustQty(long id, Long stockDelta, Long occupiedDelta) throws SQLException {
        String sql = "UPDATE component SET " +
                "stock_qty = CASE WHEN stock_qty + ? < 0 THEN 0 ELSE stock_qty + ? END, " +
                "occupied_qty = CASE WHEN occupied_qty + ? < 0 THEN 0 ELSE occupied_qty + ? END, " +
                "updated_at = datetime('now','localtime') " +
                "WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            long stock = stockDelta == null ? 0L : stockDelta;
            long occupied = occupiedDelta == null ? 0L : occupiedDelta;
            ps.setLong(1, stock);
            ps.setLong(2, stock);
            ps.setLong(3, occupied);
            ps.setLong(4, occupied);
            ps.setLong(5, id);
            if (ps.executeUpdate() == 0) return null;
        }
        return findById(id);
    }

    public boolean delete(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM component WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    // ==================== 供其他仓库删除前做引用检查 ====================

    public long countByType(long typeId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return Rows.count(conn, "SELECT COUNT(*) FROM component WHERE type_id = ?", typeId);
        }
    }

    public long countBySubtype(long subtypeId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return Rows.count(conn, "SELECT COUNT(*) FROM component WHERE subtype_id = ?", subtypeId);
        }
    }

    public long countByPackage(long packageId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return Rows.count(conn, "SELECT COUNT(*) FROM component WHERE package_id = ?", packageId);
        }
    }

    public long countByLocation(long locationId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return Rows.count(conn, "SELECT COUNT(*) FROM component WHERE location_id = ?", locationId);
        }
    }

    private Map<String, Object> readRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = Rows.map(rs, COLUMNS);
        row.put("location_label", label(Values.toStr(row.get("zone_name")),
                Values.toStr(row.get("location_code"))));
        return row;
    }

    /** 位置显示名：位置区-编号，缺一不可。 */
    static String label(String zoneName, String locationCode) {
        if (zoneName == null || locationCode == null) return null;
        return zoneName + "-" + locationCode;
    }

    private static void addEquals(List<String> conditions, List<Object> args,
                                 Map<String, String> filters, String key, String column) {
        String raw = Values.trimToNull(filters.get(key));
        if (raw == null) return;
        Object value = Values.toLong(raw);
        if (value == null) return;
        conditions.add(column + " = ?");
        args.add(value);
    }
}
