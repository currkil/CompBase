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

/** 位置区 / 位置维护。位置显示名在查询时拼成「位置区-编号」。 */
public class LocationRepository {
    private final Database db;

    public LocationRepository(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> listZones() throws SQLException {
        List<Map<String, Object>> zones = new ArrayList<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT zone_id, zone_name, description FROM zone ORDER BY zone_name, zone_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = Rows.map(rs, "zone_id", "zone_name", "description");
                    row.put("locations", new ArrayList<Map<String, Object>>());
                    zones.add(row);
                    byId.put(Values.toLong(row.get("zone_id")), row);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT location_id, zone_id, location_code, description FROM location " +
                    "ORDER BY location_code, location_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> loc = Rows.map(rs,
                            "location_id", "zone_id", "location_code", "description");
                    Map<String, Object> parent = byId.get(Values.toLong(loc.get("zone_id")));
                    if (parent != null) {
                        loc.put("label", ComponentRepository.label(
                                Values.toStr(parent.get("zone_name")), Values.toStr(loc.get("location_code"))));
                        locationsOf(parent).add(loc);
                    }
                }
            }
        }
        return zones;
    }

    public List<Map<String, Object>> listLocations(Long zoneId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT l.location_id AS location_id, l.zone_id AS zone_id, l.location_code AS location_code, " +
                "       l.description AS description, z.zone_name AS zone_name " +
                "FROM location l LEFT JOIN zone z ON z.zone_id = l.zone_id ");
        if (zoneId != null) sql.append("WHERE l.zone_id = ? ");
        sql.append("ORDER BY z.zone_name, l.location_code, l.location_id");

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (zoneId != null) ps.setLong(1, zoneId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = Rows.map(rs,
                            "location_id", "zone_id", "location_code", "description", "zone_name");
                    row.put("label", ComponentRepository.label(
                            Values.toStr(row.get("zone_name")), Values.toStr(row.get("location_code"))));
                    list.add(row);
                }
            }
        }
        return list;
    }

    // ==================== 位置区 ====================

    public Map<String, Object> findZone(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT zone_id, zone_name, description FROM zone WHERE zone_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Rows.map(rs, "zone_id", "zone_name", "description") : null;
            }
        }
    }

    public long createZone(String name, String description) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO zone (zone_name, description) VALUES (?, ?)")) {
            ps.setString(1, name);
            Rows.set(ps, 2, description);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public boolean updateZone(long id, String name, String description) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE zone SET zone_name = ?, description = ? WHERE zone_id = ?")) {
            ps.setString(1, name);
            Rows.set(ps, 2, description);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 删除位置区及其位置；区内位置仍被元器件引用时拒绝。 */
    public boolean deleteZone(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            long used = Rows.count(conn,
                    "SELECT COUNT(*) FROM component WHERE location_id IN " +
                    "(SELECT location_id FROM location WHERE zone_id = ?)", id);
            if (used > 0) {
                throw new ConflictException("该位置区下仍有 " + used + " 个元器件，无法删除");
            }
            Rows.begin(conn);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM location WHERE zone_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                int rows;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM zone WHERE zone_id = ?")) {
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

    // ==================== 位置 ====================

    public Map<String, Object> findLocation(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT location_id, zone_id, location_code, description FROM location WHERE location_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Rows.map(rs, "location_id", "zone_id", "location_code", "description") : null;
            }
        }
    }

    public long createLocation(long zoneId, String code, String description) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO location (zone_id, location_code, description) VALUES (?, ?, ?)")) {
            ps.setLong(1, zoneId);
            ps.setString(2, code);
            Rows.set(ps, 3, description);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public boolean updateLocation(long id, Long zoneId, String code, String description) throws SQLException {
        String sql = zoneId == null
                ? "UPDATE location SET location_code = ?, description = ? WHERE location_id = ?"
                : "UPDATE location SET zone_id = ?, location_code = ?, description = ? WHERE location_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (zoneId != null) ps.setLong(index++, zoneId);
            ps.setString(index++, code);
            Rows.set(ps, index++, description);
            ps.setLong(index, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteLocation(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            long used = Rows.count(conn, "SELECT COUNT(*) FROM component WHERE location_id = ?", id);
            if (used > 0) {
                throw new ConflictException("该位置存放着 " + used + " 个元器件，无法删除");
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM location WHERE location_id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> locationsOf(Map<String, Object> zoneRow) {
        return (List<Map<String, Object>>) zoneRow.get("locations");
    }
}
