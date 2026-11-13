# Three.js 存放说明（平铺）

3D 预览按下面的**平铺目录**加载（`index.html` 的 importmap 已经配好）：
全部文件放在 `static/vendor/three/` 下即可，不需要 `addons/` 子目录。

```text
static/vendor/three/
├── three.module.js
├── three.core.js                 ← 新版 three 拆分出来的核心，必需
├── GLTFLoader.js
├── OBJLoader.js
├── OrbitControls.js
├── BufferGeometryUtils.js        ← GLTFLoader 依赖
└── SkeletonUtils.js              ← GLTFLoader 依赖
```

`GLTFLoader.js` 里有两行 `import ... from '../utils/...'`，importmap 已把它们重定向到同目录下的
`BufferGeometryUtils.js` / `SkeletonUtils.js`，所以这两个文件不需要放进 `utils/` 子目录。

## 获取方式

从 npm 包 `three` 同一版本里取：

- `build/three.module.js`
- `build/three.core.js`
- `examples/jsm/loaders/GLTFLoader.js`
- `examples/jsm/loaders/OBJLoader.js`
- `examples/jsm/controls/OrbitControls.js`
- `examples/jsm/utils/BufferGeometryUtils.js`
- `examples/jsm/utils/SkeletonUtils.js`

例如（版本自选，7 个文件保持同一版本）：

```text
https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js
https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.core.js
https://cdn.jsdelivr.net/npm/three@0.180.0/examples/jsm/loaders/GLTFLoader.js
https://cdn.jsdelivr.net/npm/three@0.180.0/examples/jsm/loaders/OBJLoader.js
https://cdn.jsdelivr.net/npm/three@0.180.0/examples/jsm/controls/OrbitControls.js
https://cdn.jsdelivr.net/npm/three@0.180.0/examples/jsm/utils/BufferGeometryUtils.js
https://cdn.jsdelivr.net/npm/three@0.180.0/examples/jsm/utils/SkeletonUtils.js
```

## 完整性检查

这些文件常常因为下载中断而被截断（浏览器会报 `SyntaxError: Unexpected end of input`）。
放好后在 PowerShell 里跑一遍：

```powershell
cd src/main/resources/static/vendor/three
Get-ChildItem *.js | ForEach-Object {
  $m = "$($_.BaseName).mjs"
  Copy-Item $_.Name $m -Force
  node --check $m
  if ($LASTEXITCODE -ne 0) { Write-Host "不合法的文件: $($_.Name)" -ForegroundColor Red }
  Remove-Item $m
}
```

判断是否被截断也可以看结尾：

- `three.module.js` 应以 `export { ... };` 结尾；
- `GLTFLoader.js` 应以 `export { GLTFLoader };` 结尾；
- `OBJLoader.js` 应以 `export { OBJLoader };` 结尾（后面不应再有多余字符）；
- `three.core.js` 体积约 1.4MB，`three.module.js` 通常也有数百 KB。

## 验证

打开 `#/packages` → 某个封装的「3D 预览」（或直接访问 `#/package/{id}/3d`）：

- 状态行显示模型文件名 = 加载成功；
- 提示「未能加载 Three.js」并列出缺少的文件 = 按上面的清单补齐即可。

模型文件放在 `static/models/`，数据库只存文件名。
