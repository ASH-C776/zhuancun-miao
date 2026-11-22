# 更新日志 · 转存喵（zhuancunmiao）

> 记录每个版本的功能改动。版本号沿用原项目，本次为改造起点。

## v0.6.0 · 基线导入（2026-11-22）

- 从 `D:\Doubao\重命名转存\AndroidApp` 导入源码基线，共 24 个 Kotlin 文件
- 新建独立工作目录 `D:\Doubao\zhuancunmiao`，初始化 git 仓库，便于按阶段回退
- 当前能力：夸克 / 光鸭一次性转存、剧集批量改名、增量跳过已存在集、转存记录
- 当前形态：手动粘贴链接 → 预览确认 → 转存，**无任务列表、无无持久化快照**

### Stage 0 完成记录

- **0.1 基线确认**：`gradle :app:assembleDebug --no-daemon` 构建成功（2m04s），产出 `app-debug.apk` 11.15MB
- **0.2 构建修复**：本机全局 `D:\Android\.gradle\init.d\mirrors.gradle` 会向所有项目注入 maven 仓库，与项目原 `FAIL_ON_PROJECT_REPOS` 冲突导致构建直接失败。已把 `settings.gradle.kts` 改为 `PREFER_SETTINGS`，并将阿里云镜像内置到项目，构建不再依赖本机 init.d
- git 分支 `dev`，两次提交：源码基线 + 构建修复

### 改造计划（详见《开发流程任务列表》）

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| Stage 0 | 基线准备 | ✅ 已完成（0.1 / 0.2） |
| Stage 1 | 引擎：目录深层定位（递归扫描 / 视频目录定位 / 保留原层级转存） | 🟡 1.1 / 1.4 / 1.5 / 1.6 完成，1.2 / 1.3 待真链接验证 |
| Stage 2 | 数据层：Room 三张表 + 凭证单账号化 | 待开始 |
| Stage 3 | UI 结构重构（主页 → 任务列表） | 待开始 |
| Stage 4 | 任务化功能（自动决策 / 快照 / 批次编排） | 待开始 |
| Stage 5 | TMDB 凭证 + 更新日 / 播出时刻 | 待开始 |
| Stage 6 | 稳定性（重试 / 幂等 / 日志） | 待开始 |
| Stage 7 | 表现层统一 | 待开始 |
| Stage 8 | 发布 | 待开始 |

### Stage 1 完成记录

**1.1 目录树模型（✅）**

- 新增 `core/model/ShareNode.kt`：分享目录树节点（file / children / depth），提供 `isVideo`、`directVideoCount`、`videoCount`、`flattenFiles()`、`findVideoDirs(minVideos=2)`、`maxDepth()`
- `VideoTypes`：mkv/mp4/ts/avi/rmvb/rm/mov/wmv/flv/m4v/webm/mpg/mpeg/iso；`isNoise` 识别 sample/trailer/预告/花絮/彩蛋/幕后，避免预告片被当成正片
- `NaturalOrder`：数字段按数值比较，`EP2` 排在 `EP10` 前；中文集数「第2集 / 第10集」同样生效
- `CloudDriveAdapter` 新增 `fetchShareTree(share, rootId, maxDepth)` **默认实现**：节点缓存避免重复请求、`visited` 防环、深度上限 3（硬上限 5）、子节点自然序；两个网盘适配器无需各写一套递归，直接复用现成 `fetchShareChildren`
- **转存粒度按原项目口径**：不扁平化，保留分享原有层级；`findVideoDirs` 只按**直接子级**判定（≥2 个视频才算剧集目录），上层容器目录不会被误判
- 新增 `app/src/test/.../ShareNodeTest.kt` 8 个单元测试：三层树构建、深度限制、剧集目录判定、单文件分享兜底、自然序（英文 + 中文）、`flattenFiles`、成环不死循环 —— `:app:testDebugUnitTest` 全绿
- 构建：`app/build.gradle.kts` 增加 `testImplementation("junit:junit:4.13.2")`（项目此前无任何测试依赖）

**1.5 转存粒度 + 1.6 深层改名（✅）**

- 新增 `transfer/TreeSyncEngine.kt`：目录级同步引擎，复刻原项目 `_sync_share_dir` 语义 —— 以**目录**为转存单元，保留分享原有层级，边比对边补齐
- 转存单元切分 `selectUnits()`（纯函数）：根下目录各自一个单元；根下散视频文件合为一个单元；只有一个目录单元且填了任务名时，把它作为顶层目录名
- 决策规则：
  1. 目标已有同名目录 → **不重复转存**，进入该目录递归补缺失文件（按文件名去重，已存在的集直接跳过并计入 `skippedFiles`）
  2. 目标缺同名目录 → 整目录转存一次（内部层级原样落地），再把顶层目录改名为剧名
  3. 散文件 → 按文件名比对，缺的才转存
  4. 深度上限默认 3（硬上限 5），超限记录失败不中断
- 深层改名：转存后递归展开新目录，按「原名 → 新名」逐层改名（转存不改名，所以文件名与分享树一一对应，按名匹配即可；每个文件改名后 `destCache` 失效，避免拿到脏数据）
- 产出 `Report(restoredDirs / restoredFiles / renamedItems / skippedFiles / failures)`，Stage 4 直接写入任务快照
- 新增 `TreeSyncEngineTest.kt` 7 个单元测试（内存假网盘模拟真实目录）：单目录整转存 + 顶层改名 + 深层改名、目标已有目录只补缺失集、三层结构整转存、三层结构递归下钻补第 N 集、深度超限不下钻、散文件按名跳过、`selectUnits` 边界
- 累计单测 15 项全绿（`ShareNodeTest` 8 + `TreeSyncEngineTest` 7）
