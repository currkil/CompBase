package compbase.repository;

import compbase.db.Database;
import compbase.util.ConflictException;
import compbase.util.Rows;
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
 * BOM 导入记录、明细、占用流水。
 * 确认占用 / 撤销都在一个事务里完成，撤销按「该 BOM 的净占用量」反向写流水，
 * 这样反复确认 / 撤销也不会把数量算错。
 */
public class BomRepository {
    public static final String STATUS_MATCHED = "MATCHED";
    public static final String STATUS_WARN_SHORTAGE = "WARN_SHORTAGE";
    public static final String STATUS_WARN_AMBIGUOUS = "WARN_AMBIGUOUS";
    public static final String STATUS_WARN_PACKAGE_MISMATCH = "WARN_PACKAGE_MISMATCH";
    public static final String STATUS_ERROR_NOT_FOUND = "ERROR_NOT_FOUND";
    public static final String STATUS_PENDING = "PENDING";

    private static final String ITEM_SELECT =
            "SELECT i.bom_item_id AS bom_item_id, i.bom_id AS bom_id, i.component_id AS component_id, " +
            "       i.raw_value AS raw_value, i.raw_package AS raw_package, i.quantity AS quantity, " +
            "       i.match_status AS match_status, i.match_note AS match_note, i.manual_fixed AS manual_fixed, " +
            "       c.value AS component_value, c.stock_qty AS stock_qty, c.occupied_qty AS occupied_qty, " +
            "       (c.stock_qty - c.occupied_qty) AS available_qty, c.price AS price, " +
            "       p.package_name AS package_name, p.pkg_type_id AS pkg_type_id, " +
            "       t.type_name AS type_name, s.subtype_name AS subtype_name, " +
            "       z.zone_name AS zone_name, l.location_code AS location_code " +
            "FROM bom_item i " +
            "LEFT JOIN component c ON c.id = i.component_id " +
            "LEFT JOIN type t ON t.type_id = c.type_id " +
            "LEFT JOIN subtype s ON s.subtype_id = c.subtype_id " +
            "LEFT JOIN package p ON p.package_id = c.package_id " +
            "LEFT JOIN location l ON l.location_id = c.location_id " +
            "LEFT JOIN zone z ON z.zone_id = l.zone_id ";

    private final Database db;

    public BomRepository(Database db) {
        this.db = db;
    }

    // ==================== 导入记录 ====================

    public long createImport(String projectName, String fileName, String note) throws SQLException {
        String sql = "INSERT INTO bom_import (project_name, file_name, imported_at, note) " +
                "VALUES (?, ?, datetime('now','localtime'), ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Rows.set(ps, 1, projectName);
            ps.setString(2, fileName);
            Rows.set(ps, 3, note);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public void linkLog(long bomId, long logId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE bom_import SET log_id = ? WHERE bom_id = ?")) {
            ps.setLong(1, logId);
            ps.setLong(2, bomId);
            ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> listImports() throws SQLException {
        String sql = "SELECT b.bom_id, b.project_name, b.file_name, b.imported_at, b.confirmed_at, " +
                "       b.log_id, b.note, " +
                "       (SELECT COUNT(*) FROM bom_item i WHERE i.bom_id = b.bom_id) AS item_count, " +
                "       (SELECT COUNT(*) FROM bom_item i WHERE i.bom_id = b.bom_id " +
                "        AND i.match_status = 'MATCHED') AS matched_count, " +
                "       (SELECT COALESCE(SUM(i.quantity), 0) FROM bom_item i " +
                "        WHERE i.bom_id = b.bom_id AND i.match_status = 'MATCHED') AS matched_qty, " +
                "       (SELECT COALESCE(SUM(o.delta), 0) FROM occupy_log o WHERE o.bom_id = b.bom_id) AS occupied_qty " +
                "FROM bom_import b ORDER BY b.bom_id DESC";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(decorateImport(Rows.map(rs,
                        "bom_id", "project_name", "file_name", "imported_at", "confirmed_at",
                        "log_id", "note", "item_count", "matched_count", "matched_qty", "occupied_qty")));
            }
        }
        return list;
    }

    public Map<String, Object> findImport(long bomId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return findImport(conn, bomId);
        }
    }

    private Map<String, Object> findImport(Connection conn, long bomId) throws SQLException {
        String sql = "SELECT bom_id, project_name, file_name, imported_at, confirmed_at, log_id, note " +
                "FROM bom_import WHERE bom_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bomId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? decorateImport(Rows.map(rs,
                        "bom_id", "project_name", "file_name", "imported_at", "confirmed_at",
                        "log_id", "note")) : null;
            }
        }
    }

    private Map<String, Object> decorateImport(Map<String, Object> row) {
        boolean confirmed = row.get("confirmed_at") != null;
        row.put("confirmed", confirmed);
        row.put("status_label", confirmed ? "已占用" : "未确认");
        return row;
    }

    // ==================== 明细 ====================

    public long insertItem(long bomId, String rawValue, String rawPackage, long quantity)
            throws SQLException {
        String sql = "INSERT INTO bom_item (bom_id, raw_value, raw_package, quantity, match_status) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bomId);
            Rows.set(ps, 2, rawValue);
            Rows.set(ps, 3, rawPackage);
            ps.setLong(4, quantity);
            ps.setString(5, STATUS_PENDING);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public void updateItemMatch(long itemId, Long componentId, String status, String note,
                                boolean manual) throws SQLException {
        String sql = "UPDATE bom_item SET component_id = ?, match_status = ?, match_note = ?, " +
                "manual_fixed = ? WHERE bom_item_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Rows.set(ps, 1, componentId);
            ps.setString(2, status);
            Rows.set(ps, 3, note);
            ps.setInt(4, manual ? 1 : 0);
            ps.setLong(5, itemId);
            ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> listItems(long bomId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            return listItems(conn, bomId);
        }
    }

    private List<Map<String, Object>> listItems(Connection conn, long bomId) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(ITEM_SELECT + "WHERE i.bom_id = ? " +
                "ORDER BY i.bom_item_id")) {
            ps.setLong(1, bomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(decorateItem(Rows.map(rs,
                            "bom_item_id", "bom_id", "component_id", "raw_value", "raw_package",
                            "quantity", "match_status", "match_note", "manual_fixed",
                            "component_value", "stock_qty", "occupied_qty", "available_qty",
                            "price", "package_name", "pkg_type_id", "type_name", "subtype_name",
                            "zone_name", "location_code")));
                }
            }
        }
        return list;
    }

    public Map<String, Object> findItem(long itemId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(ITEM_SELECT + "WHERE i.bom_item_id = ?")) {
            ps.setLong(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? decorateItem(Rows.map(rs,
                        "bom_item_id", "bom_id", "component_id", "raw_value", "raw_package",
                        "quantity", "match_status", "match_note", "manual_fixed",
                        "component_value", "stock_qty", "occupied_qty", "available_qty",
                        "price", "package_name", "pkg_type_id", "type_name", "subtype_name",
                        "zone_name", "location_code")) : null;
            }
        }
    }

    private Map<String, Object> decorateItem(Map<String, Object> row) {
        String zone = Values.toStr(row.get("zone_name"));
        String code = Values.toStr(row.get("location_code"));
        row.put("location_label", zone == null || code == null ? null : zone + "-" + code);
        row.put("manual_fixed", Values.toLong(row.get("manual_fixed")) != null
                && Values.toLong(row.get("manual_fixed")) > 0);
        return row;
    }

    /** 该 BOM 关联到的元器件 id（用于元器件列表高亮）。 */
    public List<Long> componentIds(long bomId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT component_id FROM bom_item " +
                     "WHERE bom_id = ? AND component_id IS NOT NULL ORDER BY component_id")) {
            ps.setLong(1, bomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    // ==================== 匹配候选 ====================

    /** 按「值」找候选元器件（忽略大小写与首尾空白）。 */
    public List<Map<String, Object>> findComponentsByValue(String value) throws SQLException {
        String sql = "SELECT c.id, c.value, c.stock_qty, c.occupied_qty, " +
                "       (c.stock_qty - c.occupied_qty) AS available_qty, c.price, " +
                "       c.type_id, c.subtype_id, c.package_id, c.location_id, " +
                "       t.type_name, s.subtype_name, p.package_name, pt.pkg_type_name, " +
                "       z.zone_name, l.location_code " +
                "FROM component c " +
                "LEFT JOIN type t ON t.type_id = c.type_id " +
                "LEFT JOIN subtype s ON s.subtype_id = c.subtype_id " +
                "LEFT JOIN package p ON p.package_id = c.package_id " +
                "LEFT JOIN package_type pt ON pt.pkg_type_id = p.pkg_type_id " +
                "LEFT JOIN location l ON l.location_id = c.location_id " +
                "LEFT JOIN zone z ON z.zone_id = l.zone_id " +
                "WHERE LOWER(TRIM(c.value)) = LOWER(TRIM(?)) ORDER BY c.id";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = Rows.map(rs,
                            "id", "value", "stock_qty", "occupied_qty", "available_qty", "price",
                            "type_id", "subtype_id", "package_id", "location_id",
                            "type_name", "subtype_name", "package_name", "pkg_type_name",
                            "zone_name", "location_code");
                    String zone = Values.toStr(row.get("zone_name"));
                    String code = Values.toStr(row.get("location_code"));
                    row.put("location_label", zone == null || code == null ? null : zone + "-" + code);
                    list.add(row);
                }
            }
        }
        return list;
    }

    /** 单个元器件当前可用数量（确认占用时再校验一次）。 */
    public Map<String, Object> findComponentBrief(long componentId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, value, stock_qty, occupied_qty, " +
                     "(stock_qty - occupied_qty) AS available_qty FROM component WHERE id = ?")) {
            ps.setLong(1, componentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Rows.map(rs, "id", "value", "stock_qty",
                        "occupied_qty", "available_qty") : null;
            }
        }
    }

    // ==================== 确认 / 撤销 ====================

    /**
     * 确认占用：只占用 MATCHED（绿色）行。
     * 存在非绿色行时必须 force=true（页面上的「强制确认」），这些行不会占用库存。
     */
    public Map<String, Object> confirm(long bomId, boolean force) throws SQLException {
        try (Connection conn = db.getConnection()) {
            Rows.begin(conn);
            try {
                Map<String, Object> bom = findImport(conn, bomId);
                if (bom == null) {
                    Rows.rollback(conn);
                    return null;
                }
                if (bom.get("confirmed_at") != null) {
                    throw new ConflictException("该 BOM 已确认占用，如需重新占用请先撤销");
                }

                List<Map<String, Object>> items = listItems(conn, bomId);
                int pending = 0;
                for (Map<String, Object> item : items) {
                    if (!STATUS_MATCHED.equals(item.get("match_status"))
                            || item.get("component_id") == null) {
                        pending++;
                    }
                }
                if (pending > 0 && !force) {
                    throw new ConflictException("有 " + pending + " 行不是绿色（未匹配或数量不足），" +
                            "确认后不会占用这些行的库存；如仍要继续请使用强制确认");
                }

                int occupied = 0;
                List<Map<String, Object>> skipped = new ArrayList<>();
                for (Map<String, Object> item : items) {
                    if (!STATUS_MATCHED.equals(item.get("match_status"))
                            || item.get("component_id") == null) {
                        continue;
                    }
                    long itemId = Values.toLong(item.get("bom_item_id"));
                    long componentId = Values.toLong(item.get("component_id"));
                    long quantity = Values.toLong(item.get("quantity")) == null
                            ? 0 : Values.toLong(item.get("quantity"));
                    Long available = Values.toLong(item.get("available_qty"));
                    if (available == null || available < quantity) {
                        Map<String, Object> skip = new LinkedHashMap<>();
                        skip.put("bom_item_id", itemId);
                        skip.put("raw_value", item.get("raw_value"));
                        skip.put("raw_package", item.get("raw_package"));
                        skip.put("quantity", quantity);
                        skip.put("available_qty", available);
                        skip.put("reason", "可用数量已不足");
                        skipped.add(skip);
                        continue;
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE component SET occupied_qty = occupied_qty + ?, " +
                            "updated_at = datetime('now','localtime') WHERE id = ?")) {
                        ps.setLong(1, quantity);
                        ps.setLong(2, componentId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO occupy_log (bom_id, component_id, delta, created_at) " +
                            "VALUES (?, ?, ?, datetime('now','localtime'))")) {
                        ps.setLong(1, bomId);
                        ps.setLong(2, componentId);
                        ps.setLong(3, quantity);
                        ps.executeUpdate();
                    }
                    occupied++;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE bom_import SET confirmed_at = datetime('now','localtime') " +
                        "WHERE bom_id = ?")) {
                    ps.setLong(1, bomId);
                    ps.executeUpdate();
                }
                Rows.commit(conn);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("occupied", occupied);
                result.put("skipped", skipped);
                result.put("bom", findImport(bomId));
                return result;
            } catch (SQLException | RuntimeException e) {
                Rows.rollback(conn);
                throw e;
            }
        }
    }

    /** 撤销占用：按该 BOM 的净占用量反向写流水，并清空 confirmed_at。 */
    public Map<String, Object> revoke(long bomId) throws SQLException {
        try (Connection conn = db.getConnection()) {
            Rows.begin(conn);
            try {
                Map<String, Object> bom = findImport(conn, bomId);
                if (bom == null) {
                    Rows.rollback(conn);
                    return null;
                }
                if (bom.get("confirmed_at") == null) {
                    throw new ConflictException("该 BOM 尚未确认占用，无需撤销");
                }

                List<Map<String, Object>> nets = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT component_id, SUM(delta) AS net FROM occupy_log " +
                        "WHERE bom_id = ? AND component_id IS NOT NULL " +
                        "GROUP BY component_id HAVING SUM(delta) <> 0")) {
                    ps.setLong(1, bomId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("component_id", rs.getLong("component_id"));
                            row.put("net", rs.getLong("net"));
                            nets.add(row);
                        }
                    }
                }

                long restored = 0;
                for (Map<String, Object> row : nets) {
                    long componentId = Values.toLong(row.get("component_id"));
                    long net = Values.toLong(row.get("net"));
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE component SET " +
                            "occupied_qty = CASE WHEN occupied_qty - ? < 0 THEN 0 ELSE occupied_qty - ? END, " +
                            "updated_at = datetime('now','localtime') WHERE id = ?")) {
                        ps.setLong(1, net);
                        ps.setLong(2, net);
                        ps.setLong(3, componentId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO occupy_log (bom_id, component_id, delta, created_at) " +
                            "VALUES (?, ?, ?, datetime('now','localtime'))")) {
                        ps.setLong(1, bomId);
                        ps.setLong(2, componentId);
                        ps.setLong(3, -net);
                        ps.executeUpdate();
                    }
                    restored += net;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE bom_import SET confirmed_at = NULL WHERE bom_id = ?")) {
                    ps.setLong(1, bomId);
                    ps.executeUpdate();
                }
                Rows.commit(conn);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("revoked", nets.size());
                result.put("restored", restored);
                result.put("bom", findImport(bomId));
                return result;
            } catch (SQLException | RuntimeException e) {
                Rows.rollback(conn);
                throw e;
            }
        }
    }
}
