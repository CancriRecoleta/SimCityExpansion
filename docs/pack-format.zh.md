# 建筑拓展包 · 开发指南与格式规范（v2）

面向拓展包作者：如何把一批建筑打包成 zip，供「建筑拓展包管理器」一键安装到
SimuKraft（New-Simukraft-1.21.1）。

## 总览

```
my_pack.zip
├── pack.json                              清单（必需）
├── icon.png                               拓展包图标（可选，由 pack.json 的 "icon" 引用）
├── index.json                             文件清单（导出时自动生成，含大小与 SHA-256）
└── buildings/
    ├── residential/                       五个固定分类目录（见下）
    │   ├── small_house.litematic          结构文件（每个建筑必需，三选一）
    │   ├── small_house.sk                 SimuKraft 元数据（可选，优先级最高）
    │   ├── small_house.meta.json          本模组元数据（可选）
    │   └── small_house.json               SimuKraft 原生职业/交易定义（可选，原样安装）
    └── commercial/
        └── bakery.nbt
```

同一建筑的所有文件以**相同基础名**关联，必须直接位于 `buildings/<分类>/` 下（不支持更深层级）。

## pack.json

```json
{
  "format": 2,
  "id": "yourname.starter_homes",
  "name": "入门住宅包",
  "version": "1.0.0",
  "author": "YourName",
  "description": "五座适合开局的小型住宅",
  "icon": "icon.png",
  "generated": "2026-06-29T12:34:56.789Z"
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `format` | ✔ | 包格式版本。当前为 `2`；`1` 仍可读取（见「v1 兼容」） |
| `id` | ✔ | 全局唯一，建议 `作者.包名` 小写蛇形；安装注册表与卸载按它追踪 |
| `name` | — | 展示名（缺省回退为 id） |
| `version` / `author` / `description` | — | 展示用 |
| `icon` | — | 拓展包图标文件名（位于 zip 根，通常 `icon.png`）；缺省表示无图标 |
| `generated` | — | 导出时间戳（RFC 3339）；仅信息用途，读取时忽略 |

> `format` 仍为 `2`：`icon`、`generated`、`icon.png`、`index.json` 均为**向后兼容的附加项**，
> 不认识它们的旧版管理器会直接忽略，照常安装。

## 图标与文件清单（icon.png / index.json）

### `icon.png`

拓展包图标，放在 zip 根目录，并在 `pack.json` 中用 `"icon": "icon.png"` 引用。管理器浏览
拓展包时会把它显示在信息面板的预览区。导出对话框中：

- **勾选「包含图标」并留空路径** —— 自动用首个建筑的立体/俯视预览渲染生成；
- **填入本地 PNG 绝对路径** —— 使用你自己的品牌图（留空时回退为自动生成）。

### `index.json`

导出时在 zip 根目录自动生成的文件清单，用于追踪打包内容与校验完整性：

```json
{
  "kind": "simcity_expansion:pack_index",
  "format": 2,
  "packId": "export.20260629_123456",
  "generated": "2026-06-29T12:34:56.789Z",
  "icon": "icon.png",
  "totalFiles": 7,
  "files": [
    { "path": "pack.json", "type": "manifest", "size": 234, "sha256": "…" },
    { "path": "icon.png", "type": "icon", "size": 1024, "sha256": "…" },
    { "path": "buildings/residential/small_house.nbt",
      "type": "structure", "category": "residential", "size": 5012, "sha256": "…" }
  ]
}
```

`files[].type` 取值：`manifest` / `icon` / `structure` / `metadata`（.sk）/ `simukraft`（原生 .json）。
清单列出除自身外的所有文件；安装时不解析 `index.json`，它供发布前核对与完整性校验之用。

## 分类目录

对应 SimuKraft 2.0 建筑包内 `buildings/<分类>/` 的五个固定分类（与其官方
`official_building.zip` 布局一致），**不可自创**：

`residential`（住宅）· `commercial`（商业）· `industry`（工业）· `public`（公共）· `other`（其他）

> 本格式的 `buildings/<分类>/` 布局与 SimuKraft 2.0 原生建筑包完全一致：若包内结构均为
> `.nbt` 且每个建筑都带 `.sk`，成品 zip 可以直接放进 `simukraftbuilding/` 由 SimuKraft
> 读取（多出的 `pack.json`/`icon.png`/`index.json`/`.meta.json` 会被其忽略）。经由本模组
> 安装的收益是：`.litematic`/`.schem` 自动转换、DataVersion 升级、`.meta.json` 补齐 `.sk`、
> 跨包重名处理与一键卸载。

## 结构文件

| 扩展名 | 说明 |
|---|---|
| `.nbt` | 原版结构方块模板，原样安装（旧版本自动升级） |
| `.litematic` | Litematica 投影。多区域自动合并：取所有区域包围盒的并集，后定义的区域覆盖先定义的；输出包含空气方块（保证建造时清空内部空间） |
| `.schem` | Sponge / WorldEdit 原理图，v1、v2、v3 均支持 |

安装时统一转换为原版 `.nbt`（SimuKraft 的建造系统只解析原版格式），并：

- 经游戏 DataFixer 把旧 `DataVersion` 升级到当前版本（来自更新版本的结构会被拒绝降级并警告）；
- 方块实体（箱子内容、告示牌等）坐标重映射后保留；**实体不保留**；
- 调色板对照注册表检测缺失方块（缺失项建造时为空气，安装时警告）；
- 合并后体积上限 16,000,000 格，超出拒绝。

## 元数据（三选一，按优先级）

### 1. `<名>.sk` —— 原样安装，优先级最高

SimuKraft 的行式格式，UTF-8，每行 `键:值`，`#` 开头为注释：

```
name:面包店
size:14 x 8 x 12
amount:8.64元
author:枫烨
description:提供小麦后可制成面包，向市民售卖~
tags:price_low,material_low,stage_early
job_type:breadShopOwner
```

| 键 | 说明 |
|---|---|
| `name` | 建筑显示名 |
| `size` | `X x Y x Z`（自带 .sk 时请与结构实际尺寸一致） |
| `amount` | 造价，SimuKraft 内置写法如 `8.64元` |
| `tags` | 逗号分隔，内置常见值：`price_low/price_high`、`material_low/material_high`、`stage_early/stage_late` |
| `job_type` | 该建筑提供的职业 id（与原生 .json 中的职业对应） |
| `poi:` 行 | SimuKraft 支持的兴趣点扩展；本模组不生成但**原样保留** |

### 2. `<名>.meta.json` —— 由管理器转写为 .sk

```json
{
  "name": "面包店",
  "amount": "8.64元",
  "author": "枫烨",
  "description": "提供小麦后可制成面包",
  "tags": ["price_low", "material_low", "stage_early"],
  "job_type": "breadShopOwner"
}
```

`tags` 接受数组或逗号分隔字符串；`size` 无需提供（按结构自动计算）。

### 3. 都不提供 —— 自动生成

名称取文件基础名（litematic 取内嵌名称），尺寸自动计算，其余留空。

## SimuKraft 原生 `<名>.json`（职业 / 交易 / 容器）

原样安装到建筑目录，schema 由 SimuKraft 定义（以其内置建筑为准），节选示例：

```json
{
  "id": "breadShop",
  "name": "面包店",
  "job": { "id": "bread_shop_owner", "name": "面包店老板", "heldItem": "minecraft:bread" },
  "containers": { "input": { "type": "structure_pos", "positions": [[4,1,3]] } },
  "offers": [
    { "id": "shop_sells_bread", "visibleTo": "mixed",
      "cost": [{ "money": 0.25 }], "result": [{ "item": "minecraft:bread", "count": 1 }],
      "stock": { "item": "minecraft:bread", "max": 64,
                 "materials": [{ "item": "minecraft:wheat", "count": 3 }] } }
  ]
}
```

> `containers.positions` 为结构内局部坐标——若结构经过转换（litematic 多区域合并会
> 平移到合并包围盒原点），请以**转换后的 .nbt** 校对坐标：先用管理器安装一次，
> 从 `simukraftbuilding/` 下对应 `sce_*.zip` 内的 `buildings/<分类>/<名>.nbt` 取出调整。

## v1 兼容

`format: 1` 的旧包仍可安装。旧包中 `<名>.json` 含义模糊，按内容启发式判定：
含 `offers` / `containers` / `job` 任一键 → 视为 SimuKraft 原生定义；否则视为元数据。
新包请使用 `format: 2` 并按上文明确命名，不要依赖启发式。

## 安装产物与卸载

SimuKraft 2.0 只读取 `simukraftbuilding/*.zip` 建筑包，因此整包安装会把包规范化后写成
`simukraftbuilding/sce_pack_<包id>.zip`（单独安装的建筑写入共享的 `sce_local.zip`），
包内每个建筑为 `buildings/<分类>/` 下的：

```
<名>.nbt          转换后的结构
<名>.sk           元数据（管理器生成的首行带 “# generated by SimCity Expansion” 标记）
<名>.json         （若包内提供原生定义）
```

与**所有**已存在建筑包（含官方包）重名时自动追加 `_2/_3`（SimuKraft 按基名合并所有包）。
整包安装清单写入 `simcity_expansion/installed_packs.json`，卸载直接删除对应 zip：

```json
{ "packs": [ { "id": "yourname.starter_homes", "name": "入门住宅包",
  "version": "1.0.0", "installedAt": 1760000000000,
  "zip": "sce_pack_yourname.starter_homes.zip",
  "files": ["buildings/residential/small_house.nbt", "buildings/residential/small_house.sk"] } ] }
```

旧版（SimuKraft 2.0 之前）安装到 `simukraftbuilding/<分类>/` 的散文件会在服务器启动或打开
管理器时自动迁移进 `sce_local.zip`，原文件移入 `simukraftbuilding/legacy_backup/` 备份。

## 制作流程建议

1. 游戏内用 Litematica 圈选建筑导出 `.litematic`（或结构方块导出 `.nbt`），也可用本模组的
   `[` `]` `'` 快捷键直接捕获选区。
2. 丢进管理器导入目录，逐个安装；选中后点「体检」看报告（红床、价格格式、空气/结构空位、
   POI 落点等），用「布局预览」核对住房与工商业坐标；快速验证可用
   `/buildpack testbuild installed/<分类>/<名>` 实地放一遍（`undo` 还原）。
3. 最终仍建议**实际让 SimuKraft 建造一遍**（箱子内容会被上游丢弃、缺失方块会变空洞——体检
   都会提醒）；顺手在表单里把价格/标签/职业/POI 调好。
4. 「已安装」页签 →「导出为拓展包」得到 zip 雏形（已含转换后的 .nbt 与 .sk，
   以及同名 .json）。
5. 解开 zip 修改 `pack.json`（改成你的 id/名称/描述）、按需补充原生 .json，重新打包。
6. 把成品 zip 放回导入目录做一次完整的「安装 → 建造 → 卸载」回归测试。
   在世界里微调过的建筑，可以圈选后右键「从当前选区更新结构」直接回填，无需重走导入流程。

## 发布检查清单

- [ ] `pack.json` 的 `id` 全局唯一、`format: 2`
- [ ] 每个建筑「体检」无错误（住宅有红色床、amount 为纯数字、无 structure_void、POI 落点符合预期）
- [ ] 全部结构在目标版本（1.21.1）下安装无"缺失方块"警告，或在描述中注明依赖模组
- [ ] 含交易/职业的建筑：`job_type`（.sk）与原生 .json 的职业 id 对应，容器坐标已按转换后结构校对（校验会检查坐标是否指向容器方块）
- [ ] zip 内路径使用正斜杠 `/`，文件名避免 `\ / : * ? " < > |`
- [ ] 实测过 SimuKraft 完整建造一次
