package compbase;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import compbase.config.Config;
import compbase.config.EnvLoader;
import compbase.config.TomlConfig;
import compbase.config.TomlLoader;
import compbase.db.Database;
import compbase.handler.BomHandler;
import compbase.handler.ComponentHandler;
import compbase.handler.HealthHandler;
import compbase.handler.ImportHandler;
import compbase.handler.ImportLogHandler;
import compbase.handler.LocationHandler;
import compbase.handler.ModelHandler;
import compbase.handler.NotesHandler;
import compbase.handler.PackageHandler;
import compbase.handler.StaticHandler;
import compbase.handler.TypeHandler;
import compbase.repository.BomRepository;
import compbase.repository.ComponentRepository;
import compbase.repository.ImportLogRepository;
import compbase.repository.LocationRepository;
import compbase.repository.PackageRepository;
import compbase.repository.TypeRepository;
import compbase.service.BaseDataImportService;
import compbase.service.BomService;
import compbase.util.NoteStore;

/**
 * 程序入口：读部署变量 -> 读仓库配置 -> 建库建表 -> 注册路由 -> 启动 HttpServer。
 * <p>
 * ENVIRONMENT_VAR 管部署（端口等），config.toml 管业务（数据库路径、备注目录）。
 * 两者都可以缺省，缺省时使用内置默认值，方便在开发板上直接跑起来。
 */
public class CompBaseApplication {
    private static final int DEFAULT_PORT = 6767;

    public static void main(String[] args) throws Exception {
        // 1. 部署环境变量
        Config env = loadEnv("ENVIRONMENT_VAR");
        int port = env.getInt("PORT", DEFAULT_PORT);
        System.out.println("Environment var file loader successfully!");
        System.out.println("=====ENVIRONMENT_VAR=====\nport -> " + port);

        // 2. 仓库内部配置
        TomlConfig cfg = TomlLoader.load("config.toml");
        String appName = cfg.getString("app.name", "CompBase");
        String appVersion = cfg.getString("app.version", "0.1.0");
        String dbPath = cfg.getString("database.path", "data/compbase.db");
        String notesDir = cfg.getString("notes.dir", "notes");
        String imagesDir = cfg.getString("notes.images_dir", "notes/images");
        System.out.println("Config.toml file loader successfully!");
        System.out.println("=====Config.toml=====");
        System.out.println("db path -> " + dbPath);
        System.out.println("notes dir -> " + notesDir);
        System.out.println("images of notes dir -> " + imagesDir);

        // 3. 初始化数据库
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.out.println("提示：未在类路径中找到 sqlite-jdbc，连接数据库时可能失败");
        }
        Database db = createDatabase(dbPath);
        db.initSchema("schema.sql");
        System.out.println("Database ready -> " + Path.of(dbPath).toAbsolutePath().normalize());

        // 4. 备注目录
        NoteStore noteStore = new NoteStore(Path.of(notesDir), Path.of(imagesDir));
        noteStore.ensureDirs();

        // 5. 仓库与 Handler
        ComponentRepository componentRepository = new ComponentRepository(db);
        TypeRepository typeRepository = new TypeRepository(db);
        LocationRepository locationRepository = new LocationRepository(db);
        PackageRepository packageRepository = new PackageRepository(db);
        BomRepository bomRepository = new BomRepository(db);
        ImportLogRepository importLogRepository = new ImportLogRepository(db);
        BomService bomService = new BomService(bomRepository, importLogRepository);
        BaseDataImportService importService = new BaseDataImportService(db, importLogRepository);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        NotesHandler notesHandler = new NotesHandler(noteStore);
        server.createContext("/api/components", new ComponentHandler(componentRepository, noteStore));
        server.createContext("/api/types", new TypeHandler(typeRepository));
        server.createContext("/api/subtypes", new TypeHandler(typeRepository));
        server.createContext("/api/zones", new LocationHandler(locationRepository));
        server.createContext("/api/locations", new LocationHandler(locationRepository));
        server.createContext("/api/package-types", new PackageHandler(packageRepository));
        server.createContext("/api/packages", new PackageHandler(packageRepository));
        server.createContext("/api/bom", new BomHandler(bomRepository, bomService));
        server.createContext("/api/import-log", new ImportLogHandler(importLogRepository));
        server.createContext("/api/import", new ImportHandler(importService));
        server.createContext("/api/models", new ModelHandler());
        server.createContext("/api/health", new HealthHandler(appVersion));
        // 同时注册 /notes 与 /notes/，让「/notes」这种不带斜杠的地址也能列出备注
        server.createContext("/notes", notesHandler);
        server.createContext("/notes/", notesHandler);
        server.createContext("/", new StaticHandler("static"));

        // 小内存开发板上固定少量线程即可；DB 是文件库，并发不会太高
        ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "compbase-http");
            thread.setDaemon(false);
            return thread;
        });
        server.setExecutor(executor);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("CompBase 正在停止...");
            server.stop(1);
            executor.shutdownNow();
        }, "compbase-shutdown"));

        System.out.println("=====CompBase " + appName + " " + appVersion + "=====");
        System.out.println("Web UI       -> http://localhost:" + port + "/");
        System.out.println("API 示例     -> http://localhost:" + port + "/api/components");
        System.out.println("Health check -> http://localhost:" + port + "/api/health");
        System.out.println("按 Ctrl+C 停止服务");

        // 主线程保持存活，否则 JVM 会退出
        Thread.currentThread().join();
    }

    private static Config loadEnv(String fileName) {
        try {
            return EnvLoader.load(fileName);
        } catch (IOException e) {
            System.err.println("读取 " + fileName + " 失败，使用默认配置：" + e.getMessage());
            return new Config(new HashMap<>());
        }
    }

    private static Database createDatabase(String dbPath) throws SQLException {
        try {
            return new Database(dbPath);
        } catch (SQLException e) {
            System.err.println("数据库初始化失败：" + e.getMessage());
            throw e;
        }
    }
}
