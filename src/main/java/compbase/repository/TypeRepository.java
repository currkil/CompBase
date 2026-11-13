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

/** 类型 / 子类型维护。类型列表同时带出子类型，前端一次取完即可。 */
public class TypeRepository {
    private final Database db;

    public TypeRepository(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> listTypes() throws SQLException {
        List<Map<String, Object>> types = new ArrayList<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT type_id, type_name, description FROM type ORDER BY type_name, type_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = Rows.map(rs, "type_id", "type_name", "description");
                    row.put("subtypes", new ArrayList<Map<String, Object>>());
                    types.add(row);
                    byId.put(Values.toLong(row.get("type_id")), row);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT subtype_id, type_id, subtype_name, description FROM subtype " +
                    "ORDER BY subtype_name, subtype_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> sub = Rows.map(rs, "subtype_id", "type_id", "subtype_name", "description");
                    Map<String, Object> parent = byId.get(Values.toLong(sub.get("type_id")));
                    if (parent != null) {
                        subtypesOf(parent).add(sub);
                    }
                }
            }
        }
        return types;
    }

    public List<Map<String, Object>> listSubtypes(Long typeId) throws SQLException {
        String sql = "SELECT subtype_id, type_id, subtype_name, description FROM subtype";
        if (typeId != null) sql += " WHERE type_id = ?";
        sql += " ORDER BY subtype_name, subtype_id";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (typeId != null) ps.setLong(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(Rows.map(rs, "subtype_id", "type_id", "subtype_name", "description"));
                }
            }
        }
        return list;
    }

    public Map<String, Object> findType(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT type_id, type_name, description FROM type WHERE type_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Rows.map(rs, "type_id", "type_name", "description") : null;
            }
        }
    }

    public long createType(String name, String description) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO type (type_name, description) VALUES (?, ?)")) {
            ps.setString(1, name);
            Rows.set(ps, 2, description);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public boolean updateType(long id, String name, String description) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE type SET type_name = ?, description = ? WHERE type_id = ?")) {
            ps.setString(1, name);
            Rows.set(ps, 2, description);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 删除类型及其子类型；仍被元器件引用时拒绝。 */
    public boolean deleteType(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            long used = Rows.count(conn,
                    "SELECT COUNT(*) FROM component WHERE type_id = ? " +
                    "OR subtype_id IN (SELECT subtype_id FROM subtype WHERE type_id = ?)", id, id);
            if (used > 0) {
                throw new ConflictException("该类型正被 " + used + " 个元器件使用，无法删除");
            }
            Rows.begin(conn);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subtype WHERE type_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                int rows;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM type WHERE type_id = ?")) {
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

    public Map<String, Object> findSubtype(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT subtype_id, type_id, subtype_name, description FROM subtype WHERE subtype_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Rows.map(rs, "subtype_id", "type_id", "subtype_name", "description") : null;
            }
        }
    }

    public long createSubtype(long typeId, String name, String description) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO subtype (type_id, subtype_name, description) VALUES (?, ?, ?)")) {
            ps.setLong(1, typeId);
            ps.setString(2, name);
            Rows.set(ps, 3, description);
            ps.executeUpdate();
            return Rows.lastInsertId(conn);
        }
    }

    public boolean updateSubtype(long id, Long typeId, String name, String description) throws SQLException {
        String sql = typeId == null
                ? "UPDATE subtype SET subtype_name = ?, description = ? WHERE subtype_id = ?"
                : "UPDATE subtype SET type_id = ?, subtype_name = ?, description = ? WHERE subtype_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (typeId != null) ps.setLong(index++, typeId);
            ps.setString(index++, name);
            Rows.set(ps, index++, description);
            ps.setLong(index, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteSubtype(long id) throws SQLException {
        try (Connection conn = db.getConnection()) {
            long used = Rows.count(conn, "SELECT COUNT(*) FROM component WHERE subtype_id = ?", id);
            if (used > 0) {
                throw new ConflictException("该子类型正被 " + used + " 个元器件使用，无法删除");
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subtype WHERE subtype_id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subtypesOf(Map<String, Object> typeRow) {
        return (List<Map<String, Object>>) typeRow.get("subtypes");
    }
}
