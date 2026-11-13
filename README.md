# CompBase

个人电子元器件库存管理系统。运行在一台 Linux 小电脑上，浏览器访问，用来记录元器件信息、库存数量、存放位置，
按 BOM 表批量占用库存，并给每个元器件挂一份 Markdown 备注。 注意：本项目大量采用Deepseek Harness创作。

- 零 Web 框架：只用 JDK 自带的 `com.sun.net.httpserver.HttpServer`
- 零 JSON 依赖：手写序列化与解析（`compbase.util.Json`）
- 单文件数据库：SQLite（`data/compbase.db`）
- 前端零构建：原生 ES Module + 哈希路由，不需要 npm
- 备注与图片放在文件系统（`notes/`），数据库只存元器件与导入记录

## 功能

- 元器件增删改查，按类型 / 子类型、封装、位置选择
- 库存与占用分开记录，可用数量 = 库存 − 占用，查询时计算
- 库存快捷出入库（列表上的 `−` / `＋`）
- 搜索（值、类型、封装、位置）与按类型 / 位置区筛选
- 类型-子类型、位置区-位置、封装类型-封装 三层基础数据管理
- **新增元器件记忆**：类型 / 子类型 / 封装 / 位置记在浏览器 localStorage，下次新增自动带出
- **基础数据批量导入**：CSV / xlsx 导入类型、子类型、封装类型、封装、位置区、位置，支持预览、重复策略、列映射
- **BOM 匹配占用**：导入 BOM（CSV / xlsx），按「值 + 封装」自动匹配并标色，支持人工匹配、确认占用、撤销占用
- **导入日志**：所有导入（基础数据 + BOM）都留日志，可查看失败详情
- **封装大全**：封装可记录 KiCad 完整名、引脚数、引脚间距、描述，并关联 3D 模型
- **3D 预览**：独立整页 `#/package/{id}/3d`，Three.js 加载 GLB / glTF / OBJ，可旋转、缩放、平移
- 元器件列表可整行高亮某个 BOM 关联的元件，位置格外醒目
- 每个元器件一份 Markdown 备注，支持图片上传、预览、插入引用

## 技术栈

| 项目 | 决定 |
| --- | --- |
| 构建工具 | Gradle（Groovy DSL，`build.gradle`） |
| Group ID | `cd.cc.currkill` |
| Java 包名 | `compbase`（只有一层目录） |
| Web 服务器 | JDK 自带 `HttpServer`，不用 Spring |
| 数据库 | SQLite，文件在 `data/compbase.db`，连接时开启外键 |
| 配置分离 | `ENVIRONMENT_VAR`（部署变量）+ `config.toml`（仓库内部配置） |
| 前端 | 原生 ES Module + 哈希路由（`#/components` 等），无构建、无 CDN |
| 3D 预览 | Three.js（本地 vendor，`static/vendor/three/`），GLTFLoader / OBJLoader / OrbitControls |
| Java 版本 | JDK 11+ |

## 目录结构

```text
CompBase/
├── build.gradle
├── settings.gradle
├── ENVIRONMENT_VAR                 ← 部署环境变量（不提交 Git）
├── config.toml                     ← 仓库内部配置
├── src/main/java/compbase/
│   ├── CompBaseApplication.java    ← 入口：配置 → 建库 → 注册路由 → 启动
│   ├── config/                     ← Config / EnvLoader / TomlConfig / TomlLoader
│   ├── db/                         ← Database（连接、外键、schema、增量迁移）
│   ├── handler/                    ← 各资源 HTTP 处理器
│   ├── repository/                 ← Component / Type / Location / Package / Bom / ImportLog
│   ├── service/                    ← BomService（匹配占用）、BaseDataImportService（表格导入）
│   └── util/                       ← Json、Csv、CsvTable、SheetReader、Xlsx、ModelCatalog、NoteStore
├── src/main/resources/
│   ├── schema.sql                  ← 11 张表 + 索引
│   └── static/
│       ├── index.html              ← 单页外壳（顶栏导航 + #view + 备注抽屉 + importmap）
│       ├── style.css
│       ├── favicon.svg
│       ├── js/                     ← core / 路由 / 各视图模块（含 package-form、package-3d）
│       ├── models/                 ← 3D 模型（GLB / glTF / OBJ），数据库只存文件名
│       └── vendor/three/           ← 自行放入 Three.js（见「封装大全与 3D 预览」）
├── notes/                          ← comp_{id}.md
│   └── images/                     ← comp_{id}_*.jpg
├── data/compbase.db
└── README.md
```

> 说明：仓储层的行数据统一用 `Map<String, Object>`（联表查询的别名就是字段名），没有再写一层实体类；
> 这样与手写 JSON 的路线一致。

## 构建与运行

```bash
# 开发运行（工作目录必须是仓库根目录，才能找到 ENVIRONMENT_VAR / config.toml）
./gradlew run

# 生成可直接部署的目录（含依赖 jar 和启动脚本）
./gradlew installDist
./build/install/CompBase/bin/CompBase     # Linux 开发板上执行

# 打包（普通 jar 不含依赖，部署请用 installDist）
./gradlew jar
```

### 自定义 Gradle 任务

```bash
# 打包所有依赖 + 静态资源的可执行 jar → build/libs/compbase-all.jar
./gradlew fatJar

# 发布包：fat jar + data/ + notes/ + config.toml + ENVIRONMENT_VAR + README
# → build/dist/CompBase-1.0-SNAPSHOT.zip（不包含 .local、.git、build、.gradle 等开发文件）
./gradlew releaseZip

# 解压后直接运行（工作目录在解压目录即可读到 config.toml / ENVIRONMENT_VAR）
java -jar compbase-all.jar

# 清空数据库里的所有业务数据，并把自增序号归零（保留表结构）
./gradlew resetDb -Pyes=true
# 同时删除 notes/comp_*.md 与 notes/images/comp_*（因为 id 会从 1 重新开始，建议一起清）
./gradlew resetDb -Pyes=true -PwithNotes=true
# 指定另一个数据库文件
./gradlew resetDb -Pyes=true -Pdb=data/other.db
```

`resetDb` 不加 `-Pyes=true` 时会在终端里要求输入 `YES` 确认；清理的是 `config.toml` 里 `database.path`
指向的文件，不会动 `static/models/`、Three.js 等资源。

发布包结构与项目根目录一致：

```text
CompBase-1.0-SNAPSHOT/
├── compbase-all.jar
├── config.toml
├── ENVIRONMENT_VAR
├── README.md
├── data/compbase.db
└── notes/
```

启动后：

- Web 页面：<http://localhost:6767/>
- 健康检查：<http://localhost:6767/api/health>
- 接口示例：<http://localhost:6767/api/components>

JVM 参数在 `application` 配置中为 `-Xmx512m -Xms256m`。

## 配置

### ENVIRONMENT_VAR（部署变量，无后缀，不提交 Git）

```ini
PORT=6767
API_KEY=
```

- `PORT`：监听端口，默认 `6767`。
- `API_KEY`：预留的外部访问鉴权，当前未启用。

### config.toml（仓库内部配置）

```toml
[app]
name = "CompBase"
version = "0.1.0"

[database]
path = "data/compbase.db"

[notes]
dir = "notes"
images_dir = "notes/images"

[logging]
level = "INFO"
```

`config.toml` 缺失会抛 `IllegalStateException`；各键都有代码内默认值，路径相对于启动时的工作目录。

## 页面（哈希路由）

前端是单页应用，地址栏的 `#` 后面就是路由，可以直接收藏 / 刷新。

| 路由 | 页面 |
| --- | --- |
| `#/components` | 元器件列表（搜索、筛选、出入库、备注） |
| `#/component/new` | 新增元器件（整页表单，自动带出上次选择） |
| `#/component/{id}` | 编辑元器件（整页表单） |
| `#/packages` | 封装大全（一级导航；搜索、封装类型维护、3D 预览、从模型库导入） |
| `#/manage` | 数据管理（类型 / 位置，整页） |
| `#/package/new` | 新增封装（整页表单，可带 `?pkg_type_id=` 预选类型） |
| `#/package/{id}` | 编辑封装 |
| `#/package/{id}/3d` | 封装 3D 预览（独立整页，不弹窗） |
| `#/bom` | BOM 记录列表 + 导入 BOM |
| `#/bom/{id}` | BOM 明细（标色、人工匹配、确认 / 撤销占用） |
| `#/import` | 基础数据批量导入（CSV / xlsx） |
| `#/logs` | 导入日志 |

> 旧地址 `#/manage/packages` 会自动跳转到 `#/packages`。

### 新增元器件时的记忆

新增页面会把「类型 / 子类型 / 封装 / 位置」写入浏览器 `localStorage`（键 `compbase.form.prefs`），
下次打开新增页自动填好；库存数量与单价不记忆。编辑已有元器件时不受影响。
换浏览器 / 清缓存后记忆会丢失，这是刻意的（不往服务端存偏好）。

### BOM 匹配占用流程

1. `#/bom` 选择 CSV 或 xlsx（值 / 封装 / 数量 三列，支持中英文表头），可选填项目名
2. 表头识别失败时，页面会让你手动指定列
3. 导入后进入明细页，逐行按状态标色：
   - 绿色 `MATCHED`：值和封装都匹配，数量也够 → 可占用
   - 黄色：`WARN_SHORTAGE` 数量不足 / `WARN_AMBIGUOUS` 匹配到多条 / `WARN_PACKAGE_MISMATCH` 封装不符
   - 红色 `ERROR_NOT_FOUND`：值找不到
4. 黄色 / 红色行点「匹配」，按值搜索元器件后人工指定（记录 `manual_fixed=1`）
5. 点「确认占用」：**只占用绿色行**，`occupied_qty += quantity`，写 `occupy_log`，写 `confirmed_at`
6. 还有黄色 / 红色行时按钮会变成「强制确认」，确认后这些行不占用库存
7. 已确认的 BOM 可「撤销占用」：按净占用数量退回 `occupied_qty`，写反向流水，清空 `confirmed_at`

### 基础数据导入流程

1. `#/import` 选择数据类型（建议顺序：类型 → 子类型 → 封装类型 → 封装 → 位置区 → 位置）
2. 选择重复策略：`SKIP`（默认）/ `UPDATE`（更新描述）/ `INSERT_ALL`（全部插入）
3. 上传 CSV / xlsx → **预览**（预览在事务里真实执行后回滚，因此重复判断与最终结果一致）
4. 确认导入后写 `import_log`，并刷新基础数据

## 封装大全与 3D 预览

### 封装字段

| 字段 | 说明 |
| --- | --- |
| `package_name` | 日常显示用的简称（DIP-8、0603），列表、搜索、BOM 匹配都用它 |
| `full_name` | KiCad 原名（`DIP-8_W7.62mm`），用于和封装库 / 模型文件精确对应 |
| `pin_count` | 引脚数，可空 |
| `pitch` | 引脚间距（mm），可空 |
| `model_file` | 3D 模型文件名，例如 `DIP-8_W7.62mm.glb`（放在 `static/models/`） |
| `model_format` | `GLB` / `GLTF` / `OBJ`，留空时按扩展名判断 |
| `description` | 描述 |

封装管理入口是一级导航「封装大全」`#/packages`，支持搜索、按封装类型筛选、封装类型维护，
每行有「3D 预览 / 编辑 / 删除」；新增与编辑是整页表单。

### 从 3D 模型库导入封装

封装大全和元器件新增页都有「从 3D 库导入」：

1. 选择要导入到的封装类型；
2. 勾选 `static/models/` 里的模型文件（已关联的会标记「已导入」）；
3. 点「导入选中」。

会按 KiCad 风格文件名自动解析并补齐：

| 文件 | 简称 | 完整名 | 引脚 | 间距 |
| --- | --- | --- | --- | --- |
| `DIP-8_W7.62mm.glb` | DIP-8 | DIP-8_W7.62mm | 8 | — |
| `SOP-8_3.9x4.9mm_P1.27mm.glb` | SOP-8 | SOP-8_3.9x4.9mm_P1.27mm | 8 | 1.27 |
| `QFP-48_7x7mm_P0.5mm.glb` | QFP-48 | QFP-48_7x7mm_P0.5mm | 48 | 0.5 |
| `R_0603_1608Metric.glb` | 0603 | R_0603_1608Metric | — | — |

解析不到的字段留空，之后可以在封装编辑页补；已存在的封装如果没关联模型，导入时会自动补上模型。
元器件新增页选中封装后会显示完整名 / 引脚 / 间距 / 模型，并可直接「查看封装 3D」「编辑封装」。

### 模型文件与 Three.js

- 模型放在 `src/main/resources/static/models/`（随静态资源打包），数据库只存文件名。
- 命名约定：用 `full_name` 命名，例如 `DIP-8_W7.62mm.glb`、`0603.obj`。
- 预览器按 `model_format` 或扩展名选择 `GLTFLoader`（`.glb` / `.gltf`）或 `OBJLoader`（`.obj`）。
- Three.js 不内置于仓库，平铺放进 `src/main/resources/static/vendor/three/`：

```text
static/vendor/three/
├── three.module.js
├── three.core.js                 ← 新版 three 拆分出来的核心，缺了它 three.module.js 无法加载
├── GLTFLoader.js
├── OBJLoader.js
├── OrbitControls.js
├── BufferGeometryUtils.js        ← GLTFLoader 依赖
└── SkeletonUtils.js              ← GLTFLoader 依赖
```

`GLTFLoader.js` 里的 `../utils/...` 由 importmap 重定向到同目录，因此不需要 `utils/` 子目录。
具体下载来源见 `src/main/resources/static/vendor/three/README.md`；3D 页面会预检这些文件并列出缺少的路径。

模型来源：KiCad 官方 3D 库（`kicad-packages3d`，STEP / WRL，CC-BY-SA-4.0）。STEP 需要自行用 FreeCAD / fascat
转成 GLB（首选）或 OBJ（备选）后再放进 `static/models/`；转换不属于本仓库的构建流程。

### 交互

鼠标左键旋转、滚轮缩放、右键平移（OrbitControls 默认行为），WASD 平移（自定义按键处理）。
模型加载后自动居中并按包围盒调整相机距离，带网格参考；加载失败或没有模型时只显示提示，不会阻塞页面。

## HTTP API

所有接口返回 JSON（备注文本与图片除外），错误格式 `{"error": "..."}`，
状态码 400 / 404 / 405 / 409 / 500。列识别失败返回 400 + `{"code":"MAPPING_REQUIRED", "headers":[...], "fields":[...]}`。

### 元器件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/components` | 列表，支持 `q`（也兼容 `keyword`）、`type_id`、`subtype_id`、`package_id`、`location_id`、`zone_id` |
| POST | `/api/components` | 新增 |
| GET | `/api/components/{id}` | 单个 |
| PUT | `/api/components/{id}` | 整体更新（未提供的字段会被清空，数量置 0） |
| DELETE | `/api/components/{id}` | 删除，同时删除备注文件与图片 |
| POST | `/api/components/{id}/stock` | 数量增减：`{"delta": 1}` 或 `{"stock_delta": -2, "occupied_delta": 1}`，结果不小于 0 |

列表项字段：`id, type_id, subtype_id, value, package_id, location_id, stock_qty, occupied_qty,
available_qty, price, type_name, subtype_name, pkg_type_id, pkg_type_name, package_name,
zone_id, zone_name, location_code, location_label, note_exists, created_at, updated_at`。

### 类型 / 子类型（位置 / 封装同构）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/types` | 类型列表，每项内嵌 `subtypes` |
| POST | `/api/types` | `{type_name, description}` |
| GET / PUT / DELETE | `/api/types/{id}` | 单个 / 修改 / 删除（连同子类型） |
| GET / POST | `/api/types/{id}/subtypes` | 某类型下的子类型 / 新增 |
| GET | `/api/subtypes?type_id=` | 子类型列表 |
| GET / PUT / DELETE | `/api/subtypes/{id}` | 单个 / 修改 / 删除 |

位置接口是 `/api/zones`、`/api/zones/{id}/locations`、`/api/locations?zone_id=`；
封装接口是 `/api/package-types`、`/api/package-types/{id}/packages`、`/api/packages?pkg_type_id=`。
被元器件引用的类型 / 位置 / 封装不允许删除，返回 409。

封装列表 / 详情字段：`package_id, pkg_type_id, pkg_type_name, package_name, full_name,
pin_count, pitch, model_file, model_format, description`。
新增 / 修改时 `package_name` 必填，其余可选；`model_file` 只存文件名或相对路径。

### 3D 模型目录

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/models` | 列出 `static/models/` 下可用的模型，附带解析建议：`name, size, url, format, package_name, full_name, pin_count, pitch, auto_filled` |
| POST | `/api/packages/from-models` | 从模型库批量创建封装：`{pkg_type_id, model_files:[...]}`，返回 `{created, updated, existing, failed}` |
| GET | `/models/{file}` | 静态模型文件（由 StaticHandler 提供，`GLB` 为 `model/gltf-binary`） |

### BOM

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/bom/import?file_name=&project_name=&value_col=&package_col=&quantity_col=` | 请求体为 CSV 或 xlsx 原始字节，返回 `{bom, items, summary, log_id}` |
| GET | `/api/bom/list` | 所有 BOM 导入记录（含明细数 / 已匹配数 / 已占用数） |
| GET | `/api/bom/{id}` | BOM 详情 + 明细 |
| GET | `/api/bom/{id}/items` | 明细列表 |
| POST | `/api/bom/item/{itemId}/match` | 人工指定：`{"component_id": 12}` |
| POST | `/api/bom/{id}/confirm` | 确认占用：`{"force": true}` 表示强制确认 |
| POST | `/api/bom/{id}/revoke` | 撤销占用 |
| GET | `/api/bom/{id}/components` | 关联元器件 id 列表（列表页高亮用） |

### 基础数据导入

| 方法 | 路径 |
| --- | --- |
| POST | `/api/import/types` |
| POST | `/api/import/subtypes` |
| POST | `/api/import/package-types` |
| POST | `/api/import/packages` |
| POST | `/api/import/zones` |
| POST | `/api/import/locations` |

查询参数：`file_name`、`strategy=SKIP|UPDATE|INSERT_ALL`、`preview=true|false`，
以及人工指定列号 `name_col / description_col / parent_col / code_col`（0 开始）。
请求体为 CSV 或 xlsx 原始字节。返回 `{import_type, preview, total, success, skipped, failed, rows, errors, log_id}`。

### 导入日志

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/import-log` 或 `/api/import-log/list?type=` | 日志列表（倒序，`type` 可选 TYPE/SUBTYPE/.../BOM） |
| GET | `/api/import-log/{id}` | 单条详情，`errors` 已解析为数组，BOM 日志带 `bom_id` |

### 备注与图片

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/notes` | 备注文件列表 |
| GET / PUT / DELETE | `/notes/comp_{id}.md` | 读取（`text/markdown`）/ 写入 / 删除 |
| GET | `/notes/images` | 图片列表 |
| GET / PUT / DELETE | `/notes/images/{name}` | 读取 / 上传（二进制请求体）/ 删除 |

图片名建议 `comp_{id}_{时间戳}.{ext}`，只接受 jpg / jpeg / png / gif / webp。

## 表格格式（CSV / xlsx）与列识别

支持 `.csv` 与 `.xlsx`：

- CSV：编码支持 UTF-8（含 BOM）与 GBK（Excel 导出的中文 CSV 常见）。
- xlsx：读取**第一个工作表**，通过 JDK 自带的 zip + StAX 解析（不依赖 Apache POI）；
  支持共享字符串、内联字符串、数字、布尔与公式缓存值。旧版 `.xls` 不支持，请另存为 xlsx 或 CSV。
- 表头默认第一行；若前面有「XX BOM 表」之类的标题行，会在前 20 行里自动找最像表头的一行。
- 列名不区分大小写，先精确匹配再做包含匹配，失败时接口返回可人工指定列的信息。

| 导入类型 | 需要的列 | 示例 |
| --- | --- | --- |
| 类型 | 类型名, 描述 | `电阻,固定电阻器` |
| 子类型 | 类型名, 子类型名, 描述 | `电容,瓷片电容,陶瓷介质` |
| 封装类型 | 封装类型名 | `贴片` |
| 封装 | 封装类型名, 具体封装名, 完整封装名, 引脚数, 引脚间距, 模型文件, 模型格式, 描述 | `贴片,0603,R_0603_1608Metric,2,0.8,sample_box.glb,GLB,0603 电阻封装` |
| 位置区 | 位置区名, 描述 | `3号袋,最小号自封袋` |
| 位置 | 位置区名, 位置编号, 描述 | `3号袋,05,电阻区` |
| BOM | 值, 封装, 数量 | `10kΩ,0603,5` |

重复判断依据：类型名 / 类型+子类型名 / 封装类型名 / 封装类型+封装名 / 位置区名 / 位置区+编号。
`UPDATE` 策略会更新描述；封装还会一并更新完整名、引脚数、间距、模型字段（封装类型没有描述字段，按跳过处理）。

## 数据库

```text
type(type_id, type_name, description)
subtype(subtype_id, type_id → type, subtype_name, description)
package_type(pkg_type_id, pkg_type_name)
package(package_id, pkg_type_id → package_type, package_name,
        full_name, pin_count, pitch, model_file, model_format, description)
zone(zone_id, zone_name, description)
location(location_id, zone_id → zone, location_code, description)
component(id, type_id, subtype_id, value, package_id, location_id,
          stock_qty, occupied_qty, price, note_file, created_at, updated_at)
bom_import(bom_id, project_name, file_name, imported_at, confirmed_at, log_id, note)
bom_item(bom_item_id, bom_id → bom_import, component_id → component,
         raw_value, raw_package, quantity, match_status, match_note, manual_fixed)
occupy_log(log_id, bom_id → bom_import, component_id → component, delta, created_at)
import_log(log_id, import_type, file_name, duplicate_strategy,
           total_rows, success_rows, skipped_rows, failed_rows, errors, created_at)
```

设计决策：

- 可用数量不存库：`available_qty = stock_qty - occupied_qty`，在 SQL 中计算。
- 备注不存库：按 `notes/comp_{id}.md` 推算；`component.note_file` 列预留未用。
- 每次连接执行 `PRAGMA foreign_keys = ON`；`bom_item` / `occupy_log` 的元器件外键为 `ON DELETE SET NULL`，
  元器件被删后历史记录仍在，占用流水按 `component_id IS NOT NULL` 过滤。
- 撤销占用按「该 BOM 的净占用量」反向写流水（`SUM(delta)` 分组），反复确认 / 撤销也不会把数量算错。
- 旧库升级：`CREATE TABLE IF NOT EXISTS` 只会补建新表；`Database.initSchema()` 会给已存在的
  `component` 表补 `created_at` / `updated_at`，给 `package` 表补 `full_name` / `pin_count` / `pitch` /
  `model_file` / `model_format` / `description`（`ALTER TABLE` 无法补 CHECK 约束，旧库的约束校验由应用层负责）。

## DeepSeek Harness
在此感谢梁圣开发出DeepSeek。本项目共使用147M token。缓存命中率99.9%，平均129 token/s，9轮459步。

## 已知限制

- 旧库的 `component` 表无法补 `CHECK (stock_qty >= 0)` 等约束；新库才有。
- `PUT /api/components/{id}` 是整体替换语义，前端总是提交完整表单。
- BOM 的「确认占用」只处理绿色行；黄色 / 红色行永远需要人工匹配或强制确认。
- xlsx 只读第一个工作表；不支持旧版 `.xls`；公式取 Excel 保存时的缓存值；合并单元格只有左上角单元格有值。
- Three.js 不在仓库内，需要按上文把 7 个文件平铺放进 `static/vendor/three/`；缺失时 3D 页面只显示提示并列出缺哪些文件。
- vendor 文件下载不完整（`three.module.js` / `GLTFLoader.js` 被截断等）会报 `SyntaxError: Unexpected end of input`，
  按 `static/vendor/three/README.md` 的「完整性检查」重新下载即可。
- 仓库里只有两个占位模型（`sample_box.glb`、`sample_pyramid.obj`）用于验证流程；真实封装模型需自行从
  KiCad 3D 库转换后放入 `static/models/`。
- OBJ 预览不加载 `.mtl` 材质，未带材质时使用默认灰白材质。
- 图片与备注没有鉴权，`API_KEY` 目前只是预留项，不要直接暴露到公网。
- 静态资源从 classpath 读取，修改前端后需要重新构建（`./gradlew run` 会重新编译资源）。

## 后续可做

- 一维码生成与热敏打印（58mm 纸宽、203dpi、条码横打；`jSerialComm` 接串口机芯）
- 库存变动流水 / 借出归还记录
- 数据导出（CSV）、BOM 模板下载
- 导入日志按时间范围筛选、失败行一键重试
