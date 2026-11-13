package compbase.tool;

import compbase.config.TomlConfig;
import compbase.config.TomlLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 清空数据库命令：删除所有业务数据并把自增序号归零，保留表结构。
 * 由 Gradle 任务 {@code resetDb} 调用：{@code ./gradlew resetDb -Pyes=true}。
 * <p>
 * 可选参数：
 * <ul>
 *   <li>--yes：跳过交互确认（CI / 非终端环境必须带）</li>
 *   <li>--with-notes：同时删除 notes/ 下的 comp_*.md 与 notes/images 下的 comp_*</li>
 *   <li>--db=路径：覆盖 config.toml 里的 database.path</li>
 *   <li>--config=路径：指定 config.toml（默认当前目录的 config.toml）</li>
 * </ul>
 */
public final class ResetDatabase {
    private ResetDatabase() {
    }

    public static void main(String[] args) {
        boolean confirm = false;
        boolean withNotes = false;
        String dbOverride = null;
        String configPath = "config.toml";
        for (String arg : args) {
            if ("--yes".equals(arg)) confirm = true;
            else if ("--with-notes".equals(arg)) withNotes = true;
            else if (arg.startsWith("--db=")) dbOverride = arg.substring("--db=".length());
            else if (arg.startsWith("--config=")) configPath = arg.substring("--config=".length());
        }

        try {
            Path configFile = Path.of(configPath);
            TomlConfig config = TomlLoader.load(configFile.toString());
            String dbPath = dbOverride != null
                    ? dbOverride
                    : config.getString("database.path", "data/compbase.db");
            String notesDir = config.getString("notes.dir", "notes");
            String imagesDir = config.getString("notes.images_dir", "notes/images");

            Path database = Path.of(dbPath).toAbsolutePath().normalize();
            System.out.println("数据库：" + database);
            if (!Files.isRegularFile(database)) {
                System.out.println("数据库文件不存在，无需清理。");
                return;
            }

            System.out.println("将删除所有业务数据并重置自增序号" + (withNotes ? "，同时删除备注与图片" : ""));
            if (!confirm && !askConfirm()) {
                System.out.println("已取消。");
                System.exit(1);
                return;
            }

            Class.forName("org.sqlite.JDBC");
            clearDatabase(database);
            if (withNotes) {
                int notes = deleteFiles(Path.of(notesDir), "comp_*.md");
                int images = deleteFiles(Path.of(imagesDir), "comp_*");
                System.out.println("已删除备注文件 " + notes + " 个、图片 " + images + " 个。");
            } else {
                System.out.println("备注目录未改动（如需一起清空请加 -PwithNotes=true）。");
            }
            System.out.println("完成：数据库已清空，自增序号已归零。");
        } catch (Exception e) {
            System.err.println("清理失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean askConfirm() throws IOException {
        System.out.print("确认清空请输入 YES：");
        System.out.flush();
        String line;
        if (System.console() != null) {
            line = System.console().readLine();
        } else {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            line = reader.readLine();
        }
        return line != null && "YES".equalsIgnoreCase(line.trim());
    }

    private static void clearDatabase(Path database) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) tables.add(rs.getString(1));
            }

            int totalRows = 0;
            for (String table : tables) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM \"" + table.replace("\"", "\"\"") + "\"")) {
                    totalRows += ps.executeUpdate();
                }
            }

            boolean hasSequence = false;
            try (ResultSet rs = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'")) {
                hasSequence = rs.next();
            }
            if (hasSequence) {
                try (Statement sequence = conn.createStatement()) {
                    sequence.executeUpdate("DELETE FROM sqlite_sequence");
                }
            }

            try (Statement vacuum = conn.createStatement()) {
                vacuum.execute("VACUUM");
            }
            System.out.println("已清空 " + tables.size() + " 张表，共删除 " + totalRows + " 行。");
        }
    }

    private static int deleteFiles(Path dir, String glob) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && Files.deleteIfExists(file)) deleted++;
            }
        }
        return deleted;
    }
}
