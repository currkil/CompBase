# 3D 模型目录

把封装的三维模型放在这里，数据库只存文件名（`package.model_file`），不存二进制。

## 格式

- **GLB**（首选）：单文件、体积小，`GLTFLoader` 直接加载。
- **glTF**：`.gltf` + 外部 `.bin`/贴图也可以，但要注意相对路径。
- **OBJ**（备选）：可配合 `.mtl`，当前预览器不加载 MTL，未带材质时用默认灰白材质。

前端按 `model_format` 或文件扩展名自动选择加载器。

## 命名约定

用封装表里的 `full_name`（KiCad 原名）命名，例如：

```text
DIP-8_W7.62mm.glb
0603.obj
SOP-8_3.9x4.9mm_P1.27mm.glb
```

这样封装记录和模型文件能一一对应；`full_name` 与 `model_file` 仍可在封装编辑页手工修改。

## 从 KiCad 3D 库转换

KiCad 官方 3D 库（`kicad-packages3d`）提供 STEP / WRL，需要自行转成 GLB/OBJ（FreeCAD、fascat 等），
转换步骤不属于本仓库的构建流程。建议先转常用的几十个封装，验证流程后再批量转换。

## 当前占位文件

- `sample_box.glb`：手写的最小 GLB（长方体，带法线与材质），用于验证 GLB 加载。
- `sample_pyramid.obj`：最小 OBJ（四棱锥），用于验证 OBJ 加载。

真实模型就绪后直接替换/新增文件即可，不需要改代码；封装编辑页的「模型文件」下拉会列出本目录下的文件。
