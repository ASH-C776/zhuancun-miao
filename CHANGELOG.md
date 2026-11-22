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
| Stage 1 | 引擎：目录深层定位（递归扫描 / 视频目录定位 / 保留原层级转存） | ✅ 1.1-1.6 全部完成 |
| Stage 2 | 数据层：Room 三张表 + 凭证单账号化 | ✅ 已完成（2.1 / 2.2 / 2.3） |
| Stage 3 | UI 结构重构（主页 → 任务列表） | ✅ 已完成（3.1 / 3.2 / 3.3 骨架） |
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

**1.3 光鸭递归真链接验证（✅）**

实测样本：`https://www.guangyapan.com/s/1962143988912746595_aeWw8Ru726g3wcPx`（斗破苍穹 2017，310 个文件 / 169GB）
真实结构：根 →「斗破苍穹 (2017)」→ 7 个「第 N 季 - 分辨率 - 编码」目录 + 10 个海报/nfo → 每季 14 个 mp4

- 验证结论：`fetchShareChildren(share, parentId)` 逐层下钻 **完全可用**，三层都能正常返回
- 锁定字段口径（与 CASX 一致，但有两处必须注意）：
  - fid = `fileId`，文件名 = `fileName`，大小 = `fileSize`，父目录 = `parentId`
  - **目录判定只能靠 `resType=2`**：文件的 `dirType` 同样恒为 1，靠 `dirType` 会把所有文件误判成目录；现有 `GuangYaResp.isDir` 已按 `resType` 兜底，正确
  - accessToken 在 `data.accessToken`（**驼峰**），现有 `dataToken` 已兼容
- 新增 `tools/gy_share_probe.ps1`：无需登录，直接按 shareId 拉摘要 / 令牌 / 任意层目录，用于复现验证
- 新增 `GuangYaRespTest.kt` 8 个测试，全部使用上述**真实响应 JSON** 做回归（目录/季目录/剧集/海报/nfo 判定、token 驼峰提取、list 提取、参数错误判失败、摘要判成功）
- 累计单测 23 项全绿（ShareNode 8 + TreeSyncEngine 7 + GuangYaResp 8）
- ⚠️ 实测暴露的**产品级问题**（待 Stage 4 决策）：真实资源常是"整剧多季合集"，一次转存会是 310 文件 / 169GB；同一季还存在 1080p / 2160p 多版本。任务模型必须支持"锁定某季 + 某清晰度"，否则"一键全部转存"会拉满整个剧集
- 分页：17 条 / 14 条均单页返回，`pageSize=50` 的翻页路径尚未遇到 > 50 条的真实目录（`第 0 季`最可能），留待后续抓大目录验证

**1.2 夸克递归真链接验证（✅）**

实测样本：`https://pan.quark.cn/s/225348afbc93`（斗 P穹4）
真实结构：根 →「斗 P穹4」→ 同时挂「之前季番」「1-200」两个大目录 **和 19 个平铺视频**（`219-4K.mp4`、`218_4K.mkv`…）→「1-200」下还有「1-99」与一堆 4K zip

- 验证结论：`get_share_token`（匿名，只需 pwd_id + passcode）→ `sharepage/detail`（stoken + pdir_fid）逐层下钻 **完全可用**
- **修掉一个真实 bug**：转存需要的 token 字段已从 `fid_token` 改为 **`share_fid_token`**，旧代码只读 `fid_token` 会拿到 null，`fid_token_list` 变成空串数组。已改为双字段兼容（`share_fid_token` 优先，`fid_token` 兜底），并把条目解析抽成 `parseShareItem()` 便于回归测试
- 新增 `QuarkShareItemTest` 4 项（真实 JSON：目录带 share_fid_token、平铺视频、旧字段兼容、缺字段返回 null）
- ⚠️ 实测暴露的**结构问题**（已在本轮一并解决）：夸克这条分享是「剧名目录下平铺 201~219 集」，同层还挂着「1-200」这种两百集大目录。若按 SUBTREE 整棵转存，会把 1-200 一起拉走。为此给引擎加了 `Scope`：
  - `SUBTREE`（默认）：整棵子树保留层级，对应「剧名/第 1 季/14 集」
  - `DIRECT_ONLY`：只转该目录下平铺的视频，不建剧名文件夹、不进子目录，对应这条夸克分享
  - `TaskEntity` 增加 `scopeMode` 字段；新增 2 项引擎测试（只转平铺视频 / 空目录报错）
- 新增 `EpisodeParser`：集号从文件名解析，覆盖两种真实命名 —— `219-4K.mp4`（开头数字）与 `S01E01 - 第 1 集`（季集式 + 中文），并明确排除 `1080p` / `2160p` 分辨率误判；`seasonOf()` 取季号
- 新增清单 `tools/quark_share_probe.ps1`：匿名拉任意层分享目录，用于复现
- 累计单测 **45 项全绿**（ShareNode 8 + TreeSync 9 + GuangYaResp 8 + QuarkShareItem 4 + EpisodeParser 7 + Weekdays 5 + SnapshotCalc 4）

### Stage 3 完成记录

**3.1 主页换成任务列表（✅）**

- 新增 `ui/tasks/TaskListScreen.kt`：顶部「转存喵」+ 记录/设置入口，列表上方「N 个任务 / 全部检测」，右下 FAB「新建任务」，空状态引导
- 卡片按手机尺寸精简为 5 项信息：剧名、`E168 → E170`（盘里最大集 → TMDB 最新集）、状态徽标（已是最新 / 缺 N 集 / 未同步）、细进度条、`周六、周日 · 光鸭 · 11/21 22:02` 灰字
- 卡片底部只留三个动作：暂停/启用、检测转存、删除（删除有二次确认，文案说明网盘文件不受影响）
- 数据来自 `TaskRepository.observeCards()`，无快照时给默认值，UI 不判空

**3.2 新建任务弹窗（✅）**

- 字段：剧名、分享链接（多行 + 提取码文案）、更新日 7 个可点选按钮 + 「每天 / 工作日 1-5 / 清空」快捷、TMDB ID（可选）
- 链接解析**全本地**：`TaskDraftParser` 按域名判断夸克/光鸭，复用两个适配器已有正则取 shareId 与提取码，不发请求
- 保存即落 Room（`TaskEntity`），并提示「已添加」；同名/多任务的校验留到 Stage 4
- 新增 `TaskDraftParserTest` 6 项（夸克、带提取码、光鸭、不支持平台、空链接、类型判断）

**3.3 导航与拆文件（✅）**

- `Screen` 枚举新增 `TASKS` 并作为启动首页；`AppRoot` 增加 `tasksViewModel` 参数与 `TASKS` 分支
- `MainActivity` 注入 `TasksViewModel(TaskRepository(AppDatabase.get(...)))`，独立 factory，不污染原 `AppViewModel`
- 新页面全部写在 `ui/tasks/` 独立文件，**不复用也不改动 2441 行的 AppScreens.kt**，只在其 `AppRoot` 里加了一个分支；旧的粘贴式转存页暂时保留在 `Screen.HOME`，Stage 4 接通执行后下线
- 「全部检测」「检测转存」目前是占位提示，执行编排在 Stage 4 接入
- 累计单测 **55 项全绿**；`:app:assembleDebug` 通过

### Stage 2 完成记录

**2.1 Room 接入（✅）**

- 根 `build.gradle.kts` 加 KSP `2.0.21-1.0.28`（与 Kotlin 2.0.21 严格对齐），app 加 `ksp("androidx.room:room-compiler:2.6.1")` + `room-runtime` + `room-ktx`
- 新增 `AppDatabase`（`zhuancunmiao.db`，version 1，`exportSchema=false`），后续加字段走 Migration，不重建库（任务数据是用户手攒的）

**2.2 三张表（✅）**

- `TaskEntity`（tasks）：剧名、driveType、shareUrl/shareId/passcode、saveFid（目标文件夹）、**seasonFid + seasonName（目标季）**、tmdbId/tmdbMediaType、updateMode（tmdb/manual）、weekdays（"1,3,5"）、airTime、enabled
- `SnapshotEntity`（snapshots，主键 taskId）：localEpisodes / localLastEpisode / tmdbLatest / missing / status / lastSyncAt / lastMessage —— 卡片 5 项信息的唯一数据源
- `RunRecordEntity`（run_records）：trigger（manual / all / background）、耗时、四项计数、ok、message，保留最近 200 条
- 按口径**不做账号表**：任务不带 `accountName`，执行时按 `driveType` 取唯一凭证
- 清晰度不设字段：用户建任务时直接在分享里选中那一季的目录（1080p / 2160p 各是一个季目录）

**2.3 仓库层 + 快照口径（✅）**

- `Weekdays`：星期集合解析/格式化 + 卡片文案（每日更新 / 周六、周日 / 未设更新日）+ 工作日与每天预设
- `SnapshotCalc`：`missing()` / `status()` / `build()` 纯函数，未绑 TMDB 时为 UNKNOWN 不误报；网盘有超前集也不会算出负缺集
- `TaskRepository`：`observeCards()` 把任务与快照合并成卡片流（无快照给默认值，UI 不用判空）、`finishRun()` 一次落库（成功写快照，失败保留旧集数只更新说明与时间，避免卡片突然变空）
- 新增单测：`WeekdaysTest` 5 项 + `SnapshotCalcTest` 4 项
- 累计单测 **32 项全绿**（ShareNode 8 + TreeSync 7 + GuangYaResp 8 + Weekdays 5 + SnapshotCalc 4）
