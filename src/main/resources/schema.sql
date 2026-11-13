-- ============================================================
-- CompBase 数据库结构
-- 说明：CREATE TABLE IF NOT EXISTS 只对空库生效；
--      旧库缺列时由 Database.initSchema() 做增量迁移（见 migrate）。
-- ============================================================

-- 类型表
CREATE TABLE IF NOT EXISTS type (
    type_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    type_name   TEXT NOT NULL,
    description TEXT
);

-- 子类型表
CREATE TABLE IF NOT EXISTS subtype (
    subtype_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id      INTEGER NOT NULL REFERENCES type(type_id) ON DELETE CASCADE,
    subtype_name TEXT NOT NULL,
    description  TEXT
);

-- 封装类型表
CREATE TABLE IF NOT EXISTS package_type (
    pkg_type_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    pkg_type_name TEXT NOT NULL
);

-- 封装表
CREATE TABLE IF NOT EXISTS package (
    package_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    pkg_type_id  INTEGER NOT NULL REFERENCES package_type(pkg_type_id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    full_name    TEXT,
    pin_count    INTEGER,
    pitch        REAL,
    model_file   TEXT,
    model_format TEXT,
    description  TEXT
);

-- 位置区表
CREATE TABLE IF NOT EXISTS zone (
    zone_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    zone_name   TEXT NOT NULL,
    description TEXT
);

-- 位置表
CREATE TABLE IF NOT EXISTS location (
    location_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    zone_id       INTEGER NOT NULL REFERENCES zone(zone_id) ON DELETE CASCADE,
    location_code TEXT NOT NULL,
    description   TEXT
);

-- 导入日志（先建，BOM 导入记录要引用它）
CREATE TABLE IF NOT EXISTS import_log (
    log_id             INTEGER PRIMARY KEY AUTOINCREMENT,
    import_type        TEXT NOT NULL,
    file_name          TEXT,
    duplicate_strategy TEXT,
    total_rows         INTEGER NOT NULL DEFAULT 0,
    success_rows       INTEGER NOT NULL DEFAULT 0,
    skipped_rows       INTEGER NOT NULL DEFAULT 0,
    failed_rows        INTEGER NOT NULL DEFAULT 0,
    errors             TEXT,
    created_at         TEXT DEFAULT (datetime('now','localtime'))
);

-- 元器件表
CREATE TABLE IF NOT EXISTS component (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    type_id      INTEGER REFERENCES type(type_id),
    subtype_id   INTEGER REFERENCES subtype(subtype_id),
    value        TEXT,
    package_id   INTEGER REFERENCES package(package_id),
    location_id  INTEGER REFERENCES location(location_id),
    stock_qty    INTEGER NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    occupied_qty INTEGER NOT NULL DEFAULT 0 CHECK (occupied_qty >= 0),
    price        REAL CHECK (price >= 0),
    note_file    TEXT,
    created_at   TEXT DEFAULT (datetime('now','localtime')),
    updated_at   TEXT DEFAULT (datetime('now','localtime'))
);

-- BOM 导入记录
CREATE TABLE IF NOT EXISTS bom_import (
    bom_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    project_name TEXT,
    file_name    TEXT NOT NULL,
    imported_at  TEXT DEFAULT (datetime('now','localtime')),
    confirmed_at TEXT,
    log_id       INTEGER REFERENCES import_log(log_id) ON DELETE SET NULL,
    note         TEXT
);

-- BOM 明细
CREATE TABLE IF NOT EXISTS bom_item (
    bom_item_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    bom_id       INTEGER NOT NULL REFERENCES bom_import(bom_id) ON DELETE CASCADE,
    component_id INTEGER REFERENCES component(id) ON DELETE SET NULL,
    raw_value    TEXT,
    raw_package  TEXT,
    quantity     INTEGER NOT NULL DEFAULT 0,
    match_status TEXT NOT NULL DEFAULT 'PENDING',
    match_note   TEXT,
    manual_fixed INTEGER NOT NULL DEFAULT 0
);

-- 占用流水（撤销用）
CREATE TABLE IF NOT EXISTS occupy_log (
    log_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    bom_id       INTEGER NOT NULL REFERENCES bom_import(bom_id) ON DELETE CASCADE,
    component_id INTEGER REFERENCES component(id) ON DELETE SET NULL,
    delta        INTEGER NOT NULL,
    created_at   TEXT DEFAULT (datetime('now','localtime'))
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_subtype_type        ON subtype(type_id);
CREATE INDEX IF NOT EXISTS idx_package_pkgtype     ON package(pkg_type_id);
CREATE INDEX IF NOT EXISTS idx_location_zone       ON location(zone_id);
CREATE INDEX IF NOT EXISTS idx_component_type      ON component(type_id);
CREATE INDEX IF NOT EXISTS idx_component_subtype   ON component(subtype_id);
CREATE INDEX IF NOT EXISTS idx_component_package   ON component(package_id);
CREATE INDEX IF NOT EXISTS idx_component_location  ON component(location_id);
CREATE INDEX IF NOT EXISTS idx_bom_item_bom        ON bom_item(bom_id);
CREATE INDEX IF NOT EXISTS idx_bom_item_component  ON bom_item(component_id);
CREATE INDEX IF NOT EXISTS idx_occupy_log_bom      ON occupy_log(bom_id);
CREATE INDEX IF NOT EXISTS idx_import_log_type     ON import_log(import_type);
CREATE INDEX IF NOT EXISTS idx_import_log_time     ON import_log(created_at);
