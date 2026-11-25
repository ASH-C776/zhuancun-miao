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
| Stage 4 | 任务化功能（自动决策 / 快照 / 批次编排） | ✅ 已完成（4.1-4.4，含真机端到端） |
| Stage 5 | TMDB 凭证 + 更新日 / 播出时刻 | ✅ 已完成（5.1-5.4，含真机 UI 验证） |
| Stage 6 | 稳定性（重试 / 幂等 / 日志） | ✅ 已完成（6.1 / 6.2 / 6.3） |
| Stage 7 | 表现层统一 | ✅ 已完成（7.1 / 7.2） |
| Stage 8 | 发布 | ✅ 已完成（8.1 / 8.2） |

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

### Stage 4 完成记录（代码部分）

**4.1 集数统计 `EpisodeCounter`（✅）**

- 从文件名列表算「已存集数 + 最大集号」：`EpisodeParser` 逐条解析，按集号去重（同集多清晰度算 1 集）
- 剔除干扰项：海报/封面图、`*.nfo`、`*.zip`/`*.rar`、`sample`/`trailer`/`预告`/`花絮`、`1080p`/`2160p` 之类的纯分辨率文件名
- 输出 `Result(episodes, lastEpisode)`，`episodes` 用于「盘里共 N 集」，`lastEpisode` 用于卡片 `E168` 与缺集计算
- 新增 `EpisodeCounterTest` 5 项（空目录、去重、跳非视频、跳 sample/trailer、混合命名）

**4.2 执行编排 `TaskRunner`（✅）**

- `run(task, trigger)` 单任务流水线：按 `task.driveType` 取唯一凭证（未登录直接失败，不抛异常）→ 解析分享（含提取码）→ 递归扫分享树 → `ScopeAdvisor` 决定范围 → `TreeSyncEngine.sync` 转存 → 统计目标目录集数 → `repo.finishRun` 落快照与运行记录
- `runAll(tasks, trigger, onEach)` 串行遍历，**不并发**（避免网盘风控与 token 竞争），每个任务回调进度
- 光鸭执行后把轮换过的 access/refresh token 回写 `CredentialStore`（`currentCredential()`）
- 失败时不覆盖上次集数，只写失败原因，卡片不会突然变空
- `tmdbLatest` 暂传 0，卡片显示「未绑定 TMDB」，Stage 5 接 TMDB 后填入真实集数

**4.3 ViewModel / UI / 注入打通（✅）**

- `TasksViewModel`：`createTask` 保存成功后**立即执行一轮**（新建即见结果）；`runTask` / `runAll(trigger)` / `syncOnStartOnce()`；`running`（当前在跑的任务 id 集合）、`progress`（"2/5 正在检测 XXX"）、`toast`
- `TaskListScreen`：`LaunchedEffect` 触发打开 App 自动同步一轮（每 ViewModel 生命周期一次）；「全部检测」在运行中变转圈 +「同步中」并禁用；单卡「检测转存」运行中同样转圈禁用；列表上方显示批次进度文案
- `MainActivity`：`TasksViewModel` factory 注入 `TaskRepository` + `CredentialStore` + `TaskRunner`
- **单测 60 项全绿**（原 55 + `EpisodeCounterTest` 5）；`:app:compileDebugKotlin` 与 `:app:assembleDebug` 均通过，APK 11.61MB

**4.4 真机端到端回归（✅，2026-11-23）**

真机（Xiaomi Civi 3，无线调试端口每次重开都会变，本轮 `192.168.31.144:40907`）上跑通，过程中修掉三个真实问题：

1. **任务目录隔离（新功能）**：原先所有任务都写进保存路径（默认网盘根），多个剧会混在一层、集数统计也会把整盘视频算进一个任务。新增 `CloudDriveAdapter.ensureFolder(parentFid, name)`，夸克/光鸭各自实现（先列目录找同名 → 没有才建 → 建完再列目录确认 fid，参数按 CASX `mkdir` / `fs_create_dir` 实测字段）；`TaskRunner` 先为任务建「剧名」目录，再用它做转存目标和统计边界
2. **光鸭分页死循环（严重 bug）**：`get_share_page_files_list` / `file/get_file_list` 原先只判断「本页不足 50 条就停」，实测某个目录翻页参数不生效（每页都回同一批 50 条），一路翻到上限，同步卡死并打出上百个重复请求。改为三重保护：`data.total` 达到即停、本页无新 fid 即停、`MAX_PAGES=40` 硬上限；`listFolders`/`listDirFids`/`listDirFiles` 合并成同一个分页实现，不再各写一遍
3. **大目录转存超时被误判失败**：光鸭轮询 20 次（20 秒）、夸克 30 秒后直接 `throw`，而 300+ 文件/169GB 的目录远超这个时间。改为超时只记日志、继续走目录 diff 等待（与 CASX 一致），diff 也拿不到才算失败

配套调整：
- `countLocal` 由「目标目录 + 直接子目录」改为递归到 3 层：真实资源是「剧名 / 第 N 季 - 分辨率 / 集.mp4」三层，只扫两层会把盘里集数算成 0
- 新增只读排查工具 `tools/read_tasks.py`：`adb exec-out run-as ... cat databases/*` 导出 db+wal 后直接读 tasks/snapshots/run_records，不用截图
- 新增调试入口：`--ez debugTaskName 剧名 --es debugShareUrl 链接` 直接建任务并同步、`--ez debugRunAll true` 跑一轮全部检测（HyperOS 拒绝 `input tap/keyevent` 时的验证手段）
- 空状态不再弹「没有启用的任务」（自动同步无任务时静默）

实测数据（`run_records`）：

| 场景 | 结果 |
| --- | --- |
| 夸克分享（19 平铺 4K 视频） | 目标目录已存在 → 跳过 18、`localEpisodes=18`、`localLastEpisode=219`，耗时 9 秒 |
| 光鸭分享（310 文件 / 169GB，6 个季目录） | 首轮建「剧名」目录并整目录转存；次轮全部跳过 123 个文件、`localEpisodes=214` |
| 一键全部（4 个任务串行） | 27 秒跑完，每条 `ok=1`，任务间无并发 |

单测仍 **60 项全绿**；`:app:assembleDebug` 通过。

**遗留待确认**

- 测试期间在用户网盘里留下了 4 个测试用目录/任务（`斗破苍穹之少年归`、`斗破测试2`、`斗破测试3`、`夸克测试剧`），需要用户确认是否清理
- `tmdbLatest` 仍为 0（卡片显示「未绑定 TMDB」），Stage 5 接入

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
- **真机验证（Xiaomi Civi 3 / HyperOS）**：无线调试 `adb pair` + `connect 192.168.31.144:40147` 安装成功；空状态与新建任务弹窗均已截图确认
- 修 UI 问题两处：7 个更新日按钮在窄 Dialog 里被裁掉（周六个字看不到）→ 改为两行（1-4 / 5-7）；空状态文案按句拆行，避免「链接，」断在行尾
- 新增调试入口：`adb shell am start -n com.ashchan.cloudsaver/.MainActivity --ez openNewTask true` 可直接拉起新建弹窗（HyperOS 拒绝 `input tap` 注入事件时的验证手段，也为将来「外部分享直达新建」留口子）

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

### Stage 5 完成记录

**5.1 TMDB 客户端（✅）**

- 新增 `core/tmdb/TmdbClient.kt`：OkHttp 直连，**凭证自动判别** —— 含 ≥2 个点视为 v4 Read Access Token 走 `Authorization: Bearer`，否则当 v3 api_key 走 `?api_key=`
- `fetchTv(id)` 取 `last_episode_to_air` / `next_episode_to_air` / `number_of_seasons`；`TmdbTvInfo.latestEpisodeNumber(season)` 绑了目标季就取该季集数，否则取整部剧最新集
- `weekdayHint()`：优先用 `next_episode_to_air.air_date`（下一集还没播说明就是它的星期），没有则退回 `last_episode_to_air.air_date`
- `weekdayOf(isoDate)` 用 `GregorianCalendar` 且**必须 `isLenient=false`**：宽松模式会把 `2026-13-45` 滚成一个合法日期，静默算错星期；改为先做年月日范围校验再解析
- `ping()` 用 tv 1399（权游）探活，供设置页「保存并测试」使用

**5.2 凭证存储 + 设置页（✅）**

- 新增 `storage/TmdbStore.kt`：EncryptedSharedPreferences（文件名 `cloudsaver_tmdb`），与网盘凭证同级别加密
- 新增 `ui/TmdbSettings.kt`：设置页「TMDB（可选）」区块，一个输入框（Key/Token 二合一）+ 接口地址 + 「保存并测试」+「清除」；测试成功才落盘，失败显示红字原因
- 挂进 `AppScreens.kt` 的 `SettingsScreen`（同包，只加一行调用，不动原 2441 行文件）

**5.3 更新日推断 + 播出时刻（✅）**

- `data/SeasonParser.kt`：从季目录名解析季号 —— `第 2 季 - 2160p HDR` / `Season 3` / `S02` / 纯数字 / 中文数字（第二季、第十季、第二十一季）都认，且不会把 `1080p` 误当季号
- `TaskRunner` 注入 `TmdbStore`，`resolveTmdb()` 全程 try 兜底：TMDB 挂了、Key 错、网断，一律回 0/null，**不影响转存本身**
- `updateMode == tmdb` 且用户没手设过星期时，把推断结果回写 `weekdays`（手动设过的不覆盖）
- 卡片第三行：手动星期 + 有效期角标；绑了 ID 但还没推断出来时显示「TMDB 自动待推断」
- 新建弹窗加「播出时刻（可选）」输入框，字段透传到 `TaskEntity.airTime`（仅记录，提醒/调度属二期 P2）

**5.4 网络实测 + 接口地址（✅ 真机）**

- **国内直连不通，PC 与手机双端实测一致**：PC `api.themoviedb.org` 15 秒超时；手机日志 `TmdbDebug: ok=false value=Failed to connect to api.themoviedb.org/199.96.59.61:443`（DNS 解析成功但 TCP 建不起来）
- 因此**在 TMDB 区块加了「接口地址」输入框**（默认官方域名，可改镜像 / 自建反代），`TmdbClient` 构造参数 `baseUrl`，`TmdbStore.loadBaseUrl()` 统一读取
- 新增 `tools/tmdb-proxy-worker.js`：一段可直接粘进 Cloudflare Worker 的 TMDB 反代（只放行 `/3/*`、只透传鉴权头、不落日志）。用户用自己的域名/Worker 部署后把地址填进设置页即可
- 新增调试入口 `--es debugTmdbPing KEY --es debugTmdbBase 地址`：启动即打一条 `TmdbDebug` 日志，不用点 UI 就能验网络（HyperOS 拒注入事件时的验证手段）
- 真机截图 `docs/s5c.png`：4 个任务卡片、`E216 → E—`、灰字「未绑定 TMDB」、第三行「TMDB 自动待推断 · 光鸭 · 11/23 07:30」、顶部「同步 1/4」进度文案全部生效
- 新增单测 `SeasonParserTest` 6 项 + `TmdbParseTest` 8 项（含 `2026-13-45` 这类非法日期不得通过），**累计 74 项全绿（12 个 suite）**；`:app:assembleDebug` 通过

**遗留待确认**

- **TMDB 端到端未跑通**：用户还没提供真实 Key，且国内需先解决接口地址（自建反代）。填好后卡片应从「未绑定 TMDB」变为真实集数
- 「播出时刻」只存不用，没有任何提醒逻辑（后台定时是二期 P2，走 AlarmManager/WorkManager）
- 卡片集数口径：「盘里 214 集」与「最新 E216」并存，来自 `SxxEyy` 与「第 N 集」两套编号混算，是否统一编号待确认
- 真机上 4 条测试任务（`斗破苍穹之少年归`、`斗破测试2`、`斗破测试3`、`夸克测试剧`）与用户网盘里对应目录仍待确认是否清理

### Stage 6 完成记录

**6.1 重试与幂等（✅）**

- **执行互斥**：`TaskRunner` 内置 `Mutex`（companion 级，进程内唯一）。单条、一键全部、打开 App 自动同步、将来 AlarmManager 触发的后台任务，都排队串行。
  - 要解决的问题是真实的：并发时「列目录 → 建同名目录 → 比对」会穿插，可能出现两条链同时建「剧名」目录、或集数统计读到对方刚写入的一半文件。
  - 转存本身靠目录 diff 幂等（已存在就跳过），所以不需要分布式锁，进程内一把锁足够。
- **关键步骤重试一次**：`retryOnce()` 包住四个**只读**步骤 —— 解析分享链接 / 拿访问令牌 / 建任务目录 / 拉目录树，失败等 1.2 秒再来一次。网盘接口偶发 5xx、连接重置时能自愈一次，不用用户手点第二遍。
- **故意不重试转存动作**：`TreeSyncEngine.sync` 自带 diff，重复执行本来就安全；它在大目录上一次要几十秒，再加一层重试只会把等待翻倍。
- 日志：重试与最终失败都打 `TaskRunner` tag（`首次失败，重试一次` / `重试仍失败`），排查时能对上。

**6.2 运行日志（✅ 含数据库迁移）**

- 新增 `ui/tasks/RunLogScreen.kt`：运行日志页，倒序展示最近 30 条（时间、触发方式、成功/失败、耗时、新建目录 / 新增 / 改名 / 跳过数量、错误原文）。空态有引导文案。
- 主页顶栏「记录」图标从日历换成 `History`，并改指向这个新页面（原来的 `Screen.HISTORY` 仍保留旧一次性转存记录，未删除）。
- **真机上暴露的缺陷**：日志只存了 `taskId`，列表看不出「这条是哪部剧」，任务删掉更对不上号。
  - `RunRecordEntity` 新增 `taskName`（时间点快照，任务改名后旧记录保留旧名，符合日志语义）
  - `AppDatabase` 版本 1 → 2，新增 `MIGRATION_1_2`：`ALTER TABLE run_records ADD COLUMN taskName TEXT NOT NULL DEFAULT ''`
  - 旧记录 `taskName` 为空，UI 回退显示「任务 #id」
- **迁移真机验证**：用旧版（v1、23 条记录）APK 直接覆盖安装新版，导出 db 校验 `user_version=2`、`run_records` 含 `taskName` 列、23 条旧记录完好；随后跑一轮写入 `(24, taskId=1, taskName='斗破苍穹之少年归', trigger='all', ok=1)`，字段正确落库。

**6.3 异常守护（✅）**

- 新增 `core/AppLog.kt`：安装 `Thread.setDefaultUncaughtExceptionHandler`，把未捕获异常追加到 `filesDir/crash.log`（含时间、线程名、完整堆栈），超过 256KB 自动裁剪到后半段；`record()` 供协程内部兜底记录、`clear()` 供用户清空。
- `MainActivity.onCreate` 首行安装钩子（在 `enableEdgeToEdge` 之前，保证启动早期崩溃也能记到）。
- 运行日志页顶部按需显示「崩溃日志」按钮（只有真的写出过日志才出现，平时不占位），点开是纯文本堆栈，可清空 —— 用户自己就能判断「是不是系统杀后台杀的」。

**验收**

- 单测 **74 项全绿**（12 suite），`:app:assembleDebug` 通过
- 真机截图 `docs/s6a.png`：运行日志页正常渲染（时间 / 触发 / 成功 / 耗时与跳过数）；设备为 Xiaomi Civi 3，安装时从 v1 库平滑迁移到 v2
- 迁移与 `taskName` 落库均用 `tools/read_tasks.py` 直读 db 验证，不靠截图

**遗留待确认**

- 崩溃日志入口未做真机可视化验证：`crash.log` 只在真崩时产生，没有人为造崩溃；想看效果可等一次真实异常，或我加个调试入口写测试记录
- 观察到一次「一键全部只跑了第 1 个任务就停」：当时手机前台被别的 App（`com.example.minimalnav`）抢走，HyperOS 对后台进程做了冻结。这属于系统行为，但**二期后台定时（AlarmManager/WorkManager）必须考虑被冻结的应对**，不能假设进程一直活着
- `TaskRunner.buildAdapter` 有一行格式被之前的编辑压掉缩进（`when (drive) {  DriveType.QUARK -> ...`），功能无影响，留到 Stage 7 清理

### Stage 7 完成记录

**7.1 语义色与失败可见（✅）**

- 新增 `TaskColors`：成功 / 缺集 / 未同步三套语义色，按系统深浅色各给一版，徽标底色统一取 12% 透明度。
  - 之前是两处散落的硬编码 `Color(0xFF2E7D32)`、`Color(0xFFB26A00)`：深色主题下压得发暗、与浅色底对比也不一致，现在深色用 `0xFF7BC67E` / `0xFFE0A94F`。
- **卡片上直接显示失败**：`SnapshotEntity` 新增 `lastOk`，失败时卡片多一行红字「上次失败 · 原因」（`maxLines = 2`），不用点进运行日志才知道昨晚没补上。
  - 数据库 2 → 3，新增 `MIGRATION_2_3`：`ALTER TABLE snapshots ADD COLUMN lastOk INTEGER NOT NULL DEFAULT 1`
  - 默认值 1 让老数据按「成功」处理，避免升级后满屏红字误报

**7.2 旧首页下线 + 功能回收（✅）**

- `Screen.HOME`（粘贴链接一次性转存页）从枚举里删除，`AppScreens` 里 11 处 `go(Screen.HOME)`（设置 / 关于 / 打赏 / 选路径页的返回键与 onBack）全部改指 `Screen.TASKS`，`when (screen)` 分支同步移除。
- **发现并补回一处功能回退**：主题切换按钮原本只长在旧首页顶栏（浅色/深色/自动三态循环）。首页一下线，「换主题」就没入口了。
  - 现在搬到设置页：新增「外观」区块，三个 `FilterChip`（跟随系统 / 浅色 / 深色），`SettingsScreen` 接收 `themeMode` 与 `onThemeModeChange` 并写 prefs，主题持久化逻辑不变。
- `HomeScreen` 函数本身暂留（未调用），加注释说明「入口已全部迁移、保留仅作旧交互对照」，避免一次性删 2000 行引发编译问题时无法回退；`Screen.HISTORY`（旧一次性转存记录页）同理保留但已无入口。
- 顺手清理：`TaskRunner.buildAdapter` 那行被压平的缩进恢复正常。

**验收**

- 单测 **74 项全绿**（12 suite），`:app:assembleDebug` 通过
- 真机（Xiaomi Civi 3）覆盖安装后：首页正常渲染 4 张卡片，顶栏「记录」图标已换成列表样式，旧粘贴页无任何可达路径；截图 `docs/s7a.png`
- 数据库迁移真机验证：`user_version=3`，`snapshots` 含 `lastOk` 列，4 条旧快照 `lastOk=1`（不误报失败）

**遗留待确认**

- 设置页「外观」三个 FilterChip 未做真机点击验证（HyperOS 拒绝 `input tap` 注入，只能靠 `am start` extras 进页；截图验证需要手动操作一次）
- 主题切换从首页搬到设置页属于交互位置变化，如果希望保留在首页顶栏，我可以加回图标（一个 IconButton 的事）

### Stage 8 完成记录

**8.1 版本号与混淆规则（✅）**

- `versionCode 23 → 30`，`versionName 0.6.0 → 0.7.0`（debug 包自动带 `-debug` 后缀，关于页能一眼区分）
- **补一条 R8 规则**：`-keep class com.ashchan.cloudsaver.core.tmdb.** { *; }`
  - 理由：TMDB 响应模型（`TmdbTvInfo` / `TmdbEpisode`）字段名就是 JSON key（`last_episode_to_air` 等），混淆后 Gson 找不到字段，最新集会静默变 0 —— 这种 bug 不会报错，只会让卡片一直显示「未绑定 TMDB」，极难排查
  - 原有规则只 keep 了 `drive.**` 与 `core.model.**`，新包 `core.tmdb` 是 Stage 5 才加的，属于规则遗漏
- 沿用项目原有签名方案：release 复用 debug keystore，正式包与调试包可互相覆盖安装、数据不丢

**8.2 双包交付（✅）**

| 包 | 体积 | 说明 |
| --- | --- | --- |
| `app-release.apk` | **1.96 MB** | R8 混淆 + 资源收缩，日常使用 |
| `app-debug.apk` | 11.62 MB | 排查用（`run-as` 可读数据库） |

- 真机（Xiaomi Civi 3，USB 连接）覆盖安装 release 包：`versionName=0.7.0` 生效，首页正常渲染 4 张卡片，跑一轮同步后快照时间从 07:49 更新到 09:21，卡片无「上次失败」红字 —— 说明 **R8 没有破坏光鸭/夸克的 Gson 解析、Room 读写与 Compose 渲染**
- 新增 `RELEASE-0.7.0.md`：安装说明、功能范围、TMDB 反代部署步骤、已知限制
- 截图 `docs/s8a.png`

**一个必须记住的运维差异**

- release 包 `debuggable=false`，`adb run-as` 直接报 `package not debuggable`，导不出数据库。**排查数据一律装 debug 包**，`tools/read_tasks.py` 只对 debug 有效。

**全部阶段完成**

- 8 阶段 29 步全部落地；单测 74 项、12 个 suite 全绿；数据库 v3（两次迁移均在真机上验证过）
- 累计提交见 `git log`，`dev` 分支

## Stage 9 · 新建任务表单重构（2026-11-23）

> 需求：新建任务改成**全屏页**（字段多，弹窗放不下）；文案全部嵌进输入框省空间；字段补齐为
> 任务名称 / 资源链接 / 网盘 / 保存路径 / 命名规则 / TMDB（搜索选剧）/ 播出时刻（时间选择器）。

**发现的问题：字段不全 + 改名链路是断的**

| 字段 | 数据层 | 旧弹窗 | 结论 |
| --- | --- | --- | --- |
| 名称 / 链接 | 有 | 有 | 正常 |
| 网盘 `driveType` | 有 | 无（按链接自动识别，不可改） | 补手动选择 |
| 保存路径 `saveFid` | 有 | 无（写死 `"0"` 根目录） | 补目录选择 |
| 命名规则 | **没有字段** | 无 | 新增 `ruleKey` |
| TMDB | `tmdbId` | 只能手填数字 | 补搜索 |
| 播出时刻 | `airTime` | 只能手填文本 | 改时间选择器 |

更严重的是：`RenameEngine` 能算改名映射、`TreeSyncEngine` 也收 `renamePlan`，但 `TaskRunner` 里写死
`renamePlan = emptyMap()` —— **规则配了也不会生效，整条改名链是断的**。

**9.1 全屏新建页（✅）**

- 新增 `ui/tasks/NewTaskScreen.kt` + `Screen.NEW_TASK`；列表页 FAB、空态按钮都跳这里，旧 `NewTaskDialog` 整段删除。
- 所有说明文案下沉到 `placeholder`（不再用 `label` 占一行）；名称框单行、链接框 2 行，一屏能同时看到「更新日」。
- 字段顺序：剧名 → 链接 → 网盘 chip → 保存路径 → 命名规则 → TMDB → 更新日 → 播出时刻。
- 二级弹层：目录浏览（逐层下钻 + 面包屑 + 上级）、规则单选、TMDB 搜索、时间选择器（`TimePicker`，24 小时制）。

**9.2 四个新能力（✅）**

1. `TmdbClient.searchTv(query)` → `/3/search/tv`，返回 `TmdbSearchItem`（名称 / 原名 / 首播年份 / 季数 / 简介前 60 字），列表按「中文名 + 年份」展示，同名翻拍能区分。
2. `CloudAdapterFactory`：把「按网盘类型造适配器」从 `TaskRunner` 私有方法提成公共对象，新建页列目录与执行转存共用一份凭证拆分逻辑。
3. `TasksViewModel.listDirs / searchTv / isLoggedIn / hasTmdb / renameRules`：目录列举按当前选中的网盘实时拉取（不依赖旧 `FolderPickerScreen`）。
4. `TaskEntity.ruleKey` + 数据库 3 → 4（`MIGRATION_3_4`：`ALTER TABLE tasks ADD COLUMN ruleKey TEXT NOT NULL DEFAULT ''`）。

**9.3 改名链路接通（✅）**

- `TaskRunner` 注入 `RuleStore`，新增 `buildRenamePlan(task, tree)`：
  - `ruleKey` 为空 → 不改名（与老行为一致，老任务升级后不受影响）
  - 规则在设置页被禁用 → 也不改名
  - 只对视频文件算，目录名不动（目录名是层级结构，改了会与「保留原层级」冲突）
  - 新名与原名相同的条目跳过，减少无意义的重命名请求

**9.4 未确认点的默认处理**

用户当时没在线，四项按最稳妥方式落地，后面可一句话改回：

| 点 | 采用 | 理由 |
| --- | --- | --- |
| 命名规则 | 单选一套（含「不改名」） | 同名剧在不同盘里命名风格常不同，全局开关做不到 |
| 网盘 | 手动选择优先，链接识别只做预填 | 链接本身有时看不出平台 |
| TMDB 未配置 | 搜索按钮置灰 + 提示去设置，并保留「改为手填 ID」兜底 | 不让人以为「搜不到剧」 |
| 目标季 `seasonFid` | 本轮不进表单 | 表单已 8 个字段，季选择单独做更清楚 |

**验收**

- `:app:assembleDebug` 通过，单测 74 项仍全绿（`searchTv` 解析未加单测，需要网络，靠真机验证）
- 真机（Xiaomi Civi 3 / USB）安装后：`am start --ez openNewTask true` 直达新页，截图 `docs/newtask-full.png`
- 数据库 v4 迁移未在真机上单独校验（本轮装机时 App 已从 v3 库启动成功，无 migration 报错）

## Stage 10 · 修同步死循环与 TMDB 解析崩溃（2026-11-23）

- **同步一直转圈 / 集数不对**：`syncFiles` 原来按「分享原名」比对，改名后本地文件名与原名不一致，被判定为「一集都没转过」，于是整批重下再改名，撞上光鸭 `code:160 文件名已存在`。改为按**目标文件名**比对：已存在跳过，原名存在则就地改名，只转真正缺失的集
- **TMDB 绑定失败**：已完结剧 `next_episode_to_air` 为 `null`，旧代码 `JsonNull as JsonObject` 直接崩。`TmdbClient` 加 `obj(key)` 容错，`resolveTmdb` 失败时写日志（tag `TmdbResolve`）
- 网络结论纠正：`api.tmdb.org` 国内可直连（DNS 正常），`api.themoviedb.org` 被污染才连不上；App 默认地址用前者，不需要反代
- 真机验收：卡片「鉴定 · 已是最新 · E48 → E48 · 周五 · TMDB 自动」，日志 `TmdbResolve: tv=259785 season=null latest=48 weekday=5`

## Stage 11 · 季度选项 + 转存扁平化（2026-11-23）

- 新增季度选择：不选 = `剧名/视频平铺`；第 N 季 = `剧名/Season N/视频平铺`
- 转存不再把分享方文件夹名带进网盘：`TreeSyncEngine.syncFlat` 递归收集视频、按季过滤（`collectVideos`）
- 转存粒度改批量 `restoreChunk`（每批 8 个），48 集从「逐文件 8 秒/个」降到 3–9 秒一轮
- `TaskEntity.seasonNo` + 数据库 4 → 5（`MIGRATION_4_5`）；单测 `TreeSyncEngineTest` 断言随批量改动更新

## Stage 12 · 关闭启动自动同步 + 文件夹带 TMDB ID（2026-11-23）

- **不再启动即同步**：删掉 `autoSynced` / `syncOnStartOnce` 与首页 `LaunchedEffect`，全部改手动
- 手动入口：卡片「检测转存」按钮 + 顶部「全部检测」
- 命名规则加「文件夹加 TMDB ID」：文件夹名 `剧名 tmdb-<id>`（`TaskEntity.folderWithTmdbId` + 数据库 5 → 6，`TaskRunner.taskNameOf()`）
- 未绑 TMDB 时该开关置灰

## Stage 13 · 极简 + 手动化（列表与新建页重做，2026-11-23）

**列表页（`TaskListScreen.kt`）**

- 顶部「全部检测」文字按钮改成**刷新图标**（`IconButton(Refresh)`），文案「N 个任务」一并去掉
- 卡片「检测转存」文字按钮改成**刷新图标**，位置挪到左下角原暂停按钮处（左手拇指可达），运行中在同一位置转圈
- **删掉暂停按钮**（没有自动刷新后无意义），暂停态改为卡片右侧一行灰字提示
- **长按卡片 = 删除**：`combinedClickable(onClick = 编辑, onLongClick = 删除)`，弹确认对话框后才真删
- 点卡片主体 → 进编辑页（与新建同一页，字段预填）
- 「新增任务」按钮改成**纯加号 FAB**，无文字

**新建 / 编辑页（`NewTaskScreen.kt`）**

- 标题：新增时「新增任务」（去掉「追剧」），编辑时「编辑任务」
- 右上两个动作：`保存`（落库，不同步）+ 播放图标`运行一次`（不保存任务、只按当前填的内容转存一次，仍写运行日志）
- 季度：不选 / 第1季 / 第2季 / 第3季 / **自定义**；点自定义弹数字输入（placeholder「季度数」），输入 N 即下级文件夹 `Season N`
- 命名规则三项竖排、每项一行：`默认`、`通用命名`、`tmdb id`
  - `tmdb id` 仅在选中「通用命名」且已绑 TMDB 时可点（文件夹名 = `剧名 tmdb-ID`）
  - 选「默认」会自动清掉 `tmdb id`
- 选了 TMDB 剧集后，**更新日自动点亮**（`viewModel.tmdbWeekday(tvId)` → `TmdbTvInfo.weekdayHint()`；用户手动点过更新日则不再覆盖）

**配套改动**

- `TasksViewModel`：`editing`（当前编辑任务）、`startEdit/clearEdit`、`saveEdit(...)`、`runDraft(...)`（`saveSnapshot = false`）、`tmdbWeekday(tvId)`
- `TaskRunner.run(task, trigger, saveSnapshot = true)`、`TaskRepository.finishRun(..., saveSnapshot)`：false 时只写运行记录、不动卡片快照
- `AppScreens.kt`：`TASKS` 传 `onOpenEdit`，`NEW_TASK` 用 `editing` 决定预填与标题

**验收**

- `:app:compileDebugKotlin` / `:app:assembleDebug` / `:app:testDebugUnitTest` 均通过（74 项单测全绿）
- 未装机验证：本机 adb 当前无设备（USB 未插、无线 `192.168.31.144:5555` 连不上），真机截图与「运行一次」实际日志待接上手机后补

### Stage 13.1 · 保存位置合并成一行（2026-11-23）

- 表单里原来的「网盘」chips + 「保存路径」两行合成一行 **保存位置**：`光鸭云盘 / 网盘根目录`，未登录的网盘在行内直接标 `（未登录）`
- 点这行进 **全屏选择器**（`SaveLocationDialog`，`Dialog(usePlatformDefaultWidth = false)` 铺满）：
  - 顶部返回 / 标题 / `选这里`；下面一排网盘 chip（未登录置灰不可点）
  - 面包屑每段可点：点网盘名回根目录、点中间某级直接跳回那级
  - 切网盘时目录从根重新拉、选中路径清空（不同盘 fid 不通用）
  - 子目录列表带 📁 前缀，空目录给「可以直接用当前目录」提示
- 选完把 `drive + saveFid + 可读 label` 一起带回表单，并标记「网盘是用户手选的」，链接识别不再覆盖它
- `:app:compileDebugKotlin` / `:app:assembleDebug` / 单测通过；真机仍未连接（adb 无设备），截图待补
- 命名规则三项（默认 / 通用命名 / tmdb id）改为**并排一行**等宽选项，与季度、更新日同一套视觉；`tmdb id` 不可用时置灰不可点
### Stage 13.3 · 修 tmdb id 开关不亮（2026-11-23）

- **根因**：`TmdbStore.DEFAULT_BASE_URL` 仍是 `https://api.themoviedb.org`，国内 DNS 污染导致 TMDB 搜索/绑定失败，`tmdbId` 永远为空 → `tmdb id` 开关恒灰
- 默认地址改为 `https://api.tmdb.org`（与官方接口等价、国内可直连）；老配置里存着 `api.themoviedb.org` 的自动视为默认，不用手动改
- 设置页接口地址的说明文案同步更正
- 命名规则下方加动态提示：未选通用命名时提示「选『通用命名』后才能开启 tmdb id」；选了但没绑剧时提示「先在名称框右侧搜索绑定剧集」
- `:app:assembleDebug` + 单测通过
### Stage 13.4 · 主页更紧凑（2026-11-23）

- 「全部检测」从独立一行挪进顶栏，排在「运行日志」之前，与日志、设置同一行（运行中原地转圈）
- 卡片：刷新图标从底部独立一行提到信息行**左侧**，与「更新日 · 网盘 · 时间」同一行；删掉分隔线和底部空行，卡片纵向再省约 30dp
- 信息行整体**右对齐**：左段（周几 / TMDB 自动 / 网盘 / 已暂停）过长时只压缩自身并省略号，时间（`11/23 12:27`）永不挤压
- 卡片内边距 12 → 10dp；空状态文案改为「点卡片上的刷新图标才会检测补齐集数」（不再提自动同步）
### Stage 14 · 登录态自动恢复（不用再回设置页手点登录，2026-11-23）

**问题**：网盘会话自己掉线后，凭证其实一直存在加密存储里，但只有「启动时 autoLogin」和「设置页点登录」会重新验证；选目录（`TasksViewModel.listDirs`）和转存（`TaskRunner.run`）都是拿到凭证直接建 adapter 就用，所以掉线后必须回设置页手点一下。

**改动**

- 新增 `drive/DriveSession.kt`：`ensure(drive, store, force)` = 读凭证 → 建 adapter → `validateLogin`（等价于点一次设置页登录）→ **立刻回写最新凭证**（光鸭 token 轮换时不会丢）→ 返回可用 adapter。同盘 5 分钟内验证过就跳过账号接口；`invalidate()` 标记可疑，`isAuthError()` 判断登录类错误
- 选保存位置：`listDirs` 先 `ensure` 再列目录；列目录撞到登录类错误时强制重新登录一次再重试
- 转存：`TaskRunner.run` 在全局锁内先 `ensure`，成功才继续；失败直接写一条「登录已失效」运行记录
- 跑一半掉线：捕获 auth 类错误 → 强制重新登录 → 整轮重跑一次（目录 diff 幂等，不会重复转存）
- `GuangYaAdapter.validateLogin`：access_token 失效时自动回落到 refresh_token 刷新（原来只有 access 为空才刷新，导致自动登录在 access 过期时假失败）
- 夸克仍是 Cookie 机制：只能检测有效性并提示「Cookie 已失效，请到设置重新粘贴」，无法凭空续期

**验收**：`:app:assembleDebug` + 74 项单测通过；真机装包后 `debugRunAll` 跑一轮，两张卡均刷新到 13:03「已是最新」，说明正常路径未受影响（`docs/home13.png`）
### Stage 15 · 更新日推断对齐 CASX 原项目（多天更新，2026-11-23）

**对比结论（为什么原来不准）**

| | CASX 原项目 | 我们改之前 |
| --- | --- | --- |
| 样本 | 最后一季**逐集播出日期**（去重、按日期排序、最多 100 条）+ last_episode + next_episode | 只取 `next_episode_to_air`（null 时退回 last_episode）**单条** |
| 算法 | 按时间加权投票：越新权重 0.9^年龄；≥4 条且主日 ≥60% → 单日；否则取「出现 ≥2 次且权重 ≥20%」的日子，最多 3 个；天天播兜底全返回 | 直接 `weekdayOf(日期)`，永远只有一个 |

结果：一周播两天（周一+周二）、周更+周末加更、天天播的短剧（如《鉴定》每天更 4 集）都被压成单日。

**改动**

- `TmdbClient.inferWeekdaysFromSamples(samples)`：**完整移植**原项目 `_infer_weekdays_from_weekday_samples`（0.9 衰减权重、60% 单日阈值、20%/2 次规则、天天播兜底、最多 3 个）
- `TmdbClient.inferWeekdays(tvId, info)`：按原项目口径取样本——`last_episode_to_air` 的季号（无则取总季数）→ 拉该季集表 → 播出日期去重排序取最近 100 条 → 追加 last/next → 权重投票
- `TaskRunner.resolveTmdb` 改返回「多天列表」；`updateMode == TMDB`（用户从没手动点过更新日）时**每次跑都用最新推断覆盖**，修掉历史单日错误值；手动点过切 MANUAL，不再被覆盖
- 新建页选剧后自动点亮**多天**（`viewModel.tmdbWeekdays`）；更新日那一行新增「按 TMDB 重算」，点一下直接按新口径重算（自动置为 TMDB 模式）
- `updateModeAuto` 参数打通 `createTask / saveEdit`

**验证**

- 新增单测 `TmdbWeekdayInferenceTest`（7 例：单日、两天、三天、天天播、样本过少、改档、空样本），全绿
- 真机日志：`tv=307106 weekdays=[1, 2]`（恋爱博士 周一+周二）、`tv=259785 weekdays=[1, 2, 3, 4, 5, 6, 7]`（鉴定 每日更新，与优酷「每天更 4 集」一致）
- 现有两条任务是 MANUAL 模式（早前手点过星期），不会自动覆盖；编辑页点「按 TMDB 重算」再保存即可更新
### Stage 16 · 日志可清除 + TMDB 凭证脱敏 + 去掉小字（2026-11-23）

- 运行日志右上角加「清除」垃圾桶图标（有记录才显示），点了先弹确认「共 N 条记录会被删除，任务和集数进度不受影响」；`RunRecordDao.clearAll()` → `TaskRepository.clearRuns()` → `TasksViewModel.clearRuns()`，只删 run_records 表
- TMDB 凭证脱敏：保存并验证通过后输入框变只读，显示「前 9 位 + 星号」，与设置页 Refresh Token / Cookie 同一口径（星号最多 12 个，不泄露原长度）；点「清除」后可重新填写
- 去掉 TMDB 区块的小字说明：「填 API Key 或 Read Access Token…」「官方 api.themoviedb.org 在国内被 DNS 污染…」supportingText、label 后缀「（默认 api.tmdb.org，国内可直连）」全部删除，label 简化为「API Key」「接口地址」
- 新增调试入口 `--ez openSettings true` 直接进设置页（截图验收用）
### Stage 16.1 · 分享链接框改为单行（2026-11-23）

- 链接输入框从 `minLines=2 / maxLines=3` 改成 `singleLine = true`，高度与「任务名称」一致，不再占三行
- 超长链接只显示前段：用 `TextFieldValue` 控光标位置，失焦时 selection 归 0，所以编辑页打开时露出的永远是开头（`https://www.guangyapan.com/s/19…`），不会像默认那样把末尾甩出来
- 新增调试入口 `--es editTaskName 剧名`：按任务名直接进编辑页，验证编辑态 UI 不再受「无法注入点击」限制
### Stage 16.2 · 「保存位置」全屏选择器沉浸式修正（2026-11-24）

**问题**：这个选择器用的是独立 `Dialog` 窗口，Activity 的 `enableEdgeToEdge` 管不到它，状态栏和底部小白条区域各留了一条灰带，跟主页/设置/新建页的沉浸效果不一致。

**尝试与结论**：先在 Dialog 窗口里手设 `decorFitsSystemWindows=false`、`setLayout(MATCH_PARENT, MATCH_PARENT)`、两条系统栏透明、关掉导航栏对比度遮罩——截图仍然是灰带（弹窗窗口在 Compose 里仍按内容高度/安全区收口，改不动）。

**最终做法**：干掉 Dialog 窗口，改成 Activity 同一窗口里的全屏覆盖层。`NewTaskScreen` 根部包一层 `Box`，选择器画在 `Scaffold` 之上：

- `Surface(fillMaxSize)` 铺满整屏（底色延到状态栏与小白条后面）
- 正文 `windowInsetsPadding(WindowInsets.safeDrawing)` 让开两端系统栏
- `BackHandler` 接管返回键
- 与主页/设置页共用同一套 `enableEdgeToEdge`，状态栏图标深浅也由 Activity 统一管理

**验证**：真机截图 `docs/pk18.png`，状态栏区域与正文同为暖奶油底色，无灰带，无系统栏留白；调试入口 `--ez openPathPicker true` 可直接摊开选择器。
### Stage 16.3 · 卡片排版重排：时间挪到集数行右端（2026-11-24）

原排版问题：底行要同时塞「周一、周二 · TMDB 自动 · 光鸭」+ 时间，两个字串都不短，容易被压成省略号。

新排版：

- 行 2「E12 → E12」右端放**上次同步时间**（11/23 13:16），弱色小字，独占右端不会被压缩
- 行 3 只剩刷新图标 + 「周一、周二 · 光鸭 · 自动」，去掉了时间，长度砍掉近一半
- 「TMDB 自动」缩成「自动」；顺序改为「周几 · 网盘 · 自动」，更顺口
- 未绑定 TMDB 的任务显示「未设 · 光鸭 · 未绑定 TMDB」；已绑定但还没推断出星期显示「未设 · 光鸭 · 待推断」
- 行 2 原来右端的「缺 N 集 / 未绑定 TMDB」文案删除（与右上角状态徽标重复），信息不再重复占位

真机截图：`docs/home18.png`
### Stage 17 · 卡片标识改为「转存完 / 最新了 / 缺 N 集」（2026-11-24）

**需求口径（两个目标、一条优先级）**

1. 盘里集数 >= TMDB **总集数** → 「转存完」：整部剧收齐了，没得补，用户看到这个标识自己决定删不删任务
2. 否则 盘里集数 >= TMDB **当前最新已播集** → 「最新了」：眼下不缺，等下次更新
3. 否则 → 「缺 N 集」，N = 最新已播集 - 盘里集数

**实现**

- `TmdbClient`：`fetchTv` 多解析 `number_of_episodes` → `TmdbTvInfo.totalEpisodes`；新增 `totalEpisodes(tvId, season, info)`：指定季度取该季集号去重数量，否则取「最新集所在那一季」的集数（TMDB 的 `episode_number` 是季内编号，用全剧总数比会永远对不上）
- `TaskRunner.resolveTmdb` 回 `TmdbLatest(latestEpisode, weekdays, totalEpisodes)`，日志多打印 `total=`
- `SnapshotEntity` 新增 `tmdbTotal` 字段 + `STATUS_FINISHED` 常量；库版本 6 → 7（`MIGRATION_6_7` 只加一列，老快照默认 0）
- `SnapshotCalc` 三态判断与缺集数改写为「按去重集号数量」比较：中间缺一集、最大号已到总数也不算收齐；盘里超前（重复/特辑）仍算「最新了」，不报负数
- 卡片：徽标 `转存完 / 最新了 / 缺 N 集 / 未同步`；进度条分母改用总集数（无总数时退回最新集）
- 任务状态一律不动：不自动停用、不弹窗，完全由用户看标识决定

**验证**

- 单测 `SnapshotCalcTest` 重写为 7 例（缺 1 集、追平、到达总数、超前、未绑定、只有总数、整条快照），84 项全绿
- 真机 `debugRunAll` 日志：`tv=307106 latest=12 total=12`、`tv=259785 latest=48 total=48`
- 真机截图 `docs/home20.png`：两卡均显示「转存完」（12/12、48/48 都到齐）

## Stage 18 — UI 规范 P0 落地：单手操作与统一性（2026-11-23）

按 `docs/UI-SPEC.md` 的 P0 清单改造，只动布局与尺寸，不动业务逻辑。

### 1) 高频操作全部下移到右下拇指区
- 主页：顶栏三个图标（全部检测 / 运行日志 / 设置）下线，改为右下角一列圆形按钮（48dp 热区、24dp 图标、圆角 28 液态胶囊 + 4dp 阴影），最下方是「+」FAB。列表底部留白 96→200dp，卡片不再被按到。
- 新增/编辑任务页：顶栏「保存 / 运行一次」下线，改为底部 bar —— 左侧「运行一次」图标（48dp），右侧「保存」按钮（48dp 高），并套 `WindowInsets.navigationBars` 避让小白条。

### 2) 返回按钮统一图标
- `AppTopBar` 与各全屏页返回由文字/混合写法统一为 `ArrowBack` 图标（48dp 热区 / 24dp 图标），并改用 `Icons.AutoMirrored` 版本（支持 RTL，且消除 deprecated 警告）。

### 3) 触控与尺寸收敛
- `SmallChoice`（季度 / 命名规则 / 星期）由 38dp 提到 `heightIn(min = 48dp)`，圆角统一 12。
- 保存位置选择器的网盘 `FilterChip` 补 `heightIn(min = 48dp)`。
- 卡片行内刷新按钮热区 36 → 48dp（图标仍 20dp）。
- 图标尺寸向 16 / 20 / 24 / 32 收敛：账号行 14→16、提示条 18→16、清空 18→16、再转一次 14→16、登录成功勾 40→32、空态刷新 48→32、验证码弹窗关闭键 28→40（含 20dp 图标）；箭头图标统一 16。

### 4) 去掉 emoji 当图标
- 保存位置选择器列表由 `📁` emoji 改为「目录名 + 右侧 chevron（`KeyboardArrowRight` 20dp）」，行高与点击区更符合列表习惯，视觉也与全局 Material 图标一致。

### 验证
- `:app:testDebugUnitTest` + `:app:assembleDebug` 通过，84 项单测无回归。
- 真机截图：`docs/h22.png`（主页右下操作列）、`docs/n21.png`（新增页底部保存栏）、`docs/s21.png`（设置页）、`docs/pk22.png`（选择器 chevron 列表）。
- 遗留：间距仍有个别 10/14 等非规范值（P1），「又小又灰」双重降级与部分 `contentDescription` 缺失待 P1 处理。

## Stage 19 — UI 规范 P1 落地：间距 / 圆角 / 字号阶梯（2026-11-23）

承接 Stage 18，把「散乱数值」全部收进白名单。纯排版改动，无业务逻辑变更。

### 1) 间距全部收敛到 4 / 8 / 12 / 16 / 24
- 组件间距 `Arrangement.spacedBy`：10→12、6→8。
- 显式 `Spacer`：2→4、6→8、10→12、20→16（横向）/24（纵向）、28→24。
- `padding(...)` 参数：2→4、6→8、10→12、14→16、18→16、20→16、28→24、32→24。
- 现状：Spacer 只剩 4/8/12/16/24 五档，无零头值。

### 2) 圆角收敛到 8 / 12 / 16 / 24（+ 圆形 50）
- 6→8、10→12、14→12、20→24、28→24、32→24；`Theme.kt` 里形状定义同步。

### 3) 字号阶梯补齐 12 起底
- 全项目 6 处 `labelSmall`（11sp）全部升为 `bodySmall`（12sp），不再出现低于 12sp 的文字。
- 这 6 处原本是「小号 + 灰字」双重降级（最近转存摘要、新版本提示、已存在/已过滤状态、未解析集数提示、日志触发来源），一并把颜色从 `onSurfaceVariant` 提到 `onSurface`，改为「小号但不灰」。
- 登录成功后的 Token 提示同样改为深色。

### 4) emoji 图标彻底清空 + 用项目自带 folder 图标
- 保存位置选择器目录行：左侧 `R.drawable.ic_folder`（20dp、主题色）+ 目录名 + 右侧箭头（20dp 弱色），替代上一版的纯箭头行。
- 全 `ui/` 已无 emoji 充当图标。

### 5) 无障碍复查结论（不改代码）
- 现有 13 处 `contentDescription = null` 全部是「图标旁边已有同名可见文字」的装饰图标（如提示条刷新、箭头、勾选后的小对勾、目录箭头），按规范属于正确省略；没有任何「图标是唯一信息载体」却无描述的情况。

### 验证
- `:app:testDebugUnitTest` + `:app:assembleDebug` 通过，84 项单测无回归。
- 按用户要求本轮不做装机与截图复测。

### 遗留（P2）
- 弹层形态：仍有 AlertDialog / 覆盖层 / 全屏三种形态并存，需按「重操作=覆盖层、轻确认=Dialog」逐页统一。
- 文案长度：个别按钮超过 4 字（如「按 TMDB 重算」「重新验证」），属功能明确性优先，暂保留。

## Stage 20 — 主页操作合并胶囊 / 底部按钮统一 / 选择器返回即保存（2026-11-23）

### 1) 主页右下四个操作合并成一个胶囊
- 原来「三键竖列 + 独立 FAB」改为**一个横向胶囊**，四键并排：刷新 / 日志 / 设置 / 「+」。
- 四键同为 48dp 热区、24dp 图标、同底色；只有「+」用主色图标区分主次，不再有大小两个视觉层级。
- 列表底部留白从 200dp 收到 96dp（胶囊变矮，不需要那么多让位）。

### 2) 新增/编辑页底部两个按钮规格统一
- 原来「运行一次」是无外框的小三角图标按钮、「保存」是实心按钮，视觉不成对。
- 现在两者都是 48dp 高、并排等宽（各 `weight(1f)`）：左「运行一次」= 描边按钮（`OutlinedButton`，内含 20dp ▶ 图标 + 文字），右「保存」= 实心按钮。

### 3) 保存位置选择器：返回即保存
- 去掉顶栏右侧的「选这里」（位置过高、单手不好点）。
- 左上箭头返回 = 确认当前目录；系统手势返回（`BackHandler`）同样 = 确认当前目录。
- 防误覆盖：如果打开选择器后既没切换网盘、也没下钻任何目录就直接返回，则视为「只是看看」，保持表单里原来的选择不变。
- 面包屑行右侧加一枚提示「返回即保存」，用主色小字，和面包屑同一行。

### 验证
- `:app:testDebugUnitTest` + `:app:assembleDebug` 通过，84 项单测无回归。
- 按用户要求本轮不做装机复测。

## Stage 21 — UI 规范 P2 落地：弹层 / 空状态 / 文案（2026-11-23）

规范全文同步更新在 `docs/UI-SPEC.md`（含第 4 节图标档位、第 10 节选择器行、第 11 节迁移清单结论）。

### 1) 弹层形态定案
- **结论：轻量单步输入保持 Dialog，不改成覆盖层。** TMDB 搜索、自定义季度、时间选择三者都只有一步输入，改成全屏覆盖层反而多一次整屏切换。
- 全部 12 处 `AlertDialog` 统一：圆角 **24dp**、标题 `titleSmall` + SemiBold、正文 `bodyMedium`。
- 破坏性确认（删除任务 / 清除日志 / 清空记录）的确认按钮文字统一用 `error` 色，并保留「影响范围」说明句。

### 2) 空状态补齐
- 主页空态：补 32dp `List` 图标位；文案从三行压到「一句标题 + 一句指引」，按钮「新建第一个任务」→「新建任务」。
- 日志页空态：补 32dp `Refresh` 图标位，说明句从 `bodySmall` 升为 `bodyMedium`。
- 记录页空态：说明句同样升为 `bodyMedium`，间距按 4/8/12/16 收敛。

### 3) 尺寸与字重彻底收口
- 图标/热区只剩 **16 / 20 / 24 / 32 / 48**：其余 14→16、18→20（按钮内转圈）、40→48（验证码弹窗关闭键，图标仍 20）。
- 字号阶梯去掉 22sp：`titleLarge` 3 处全部换 `titleMedium`；全项目 `FontWeight.Bold/Black` **清零**，强调只用 SemiBold。

### 4) 按钮文案 ≤4 字
- 使用此格式→**用此格式**；收不到验证码？重新获取→**重新获取**；按 TMDB 重算→**重算**；我已了解，开始→**我已了解**；登录并获取→**登录获取**；改用手动粘贴→**手动粘贴**。
（提示正文里引用旧按钮名的地方同步改为「手动粘贴」。）

### 验收复核（对照规范第 12 节）
- 顶栏返回均为图标 ✔
- 高频操作全在屏幕下 40%（主页胶囊四键、新增页底部双按钮） ✔
- 图标尺寸只有 16/20/24/32，热区 48 ✔
- 字号只出现 12/14/16/20 ✔
- 间距只有 4/8/12/16/24 ✔
- 可点元素热区 ≥48dp ✔
- 破坏性操作二次确认 + 影响范围 ✔
- 全屏选择器沉浸（无灰带） ✔
- 状态色只出现在徽标/进度条/错误文案 ✔

### 验证
- `:app:testDebugUnitTest` + `:app:assembleDebug` 通过，84 项单测无回归。
- 本轮按用户要求不装机复测。

### 备注
- 少数「非按钮」的说明文字仍超过 4 字（如登录声明、Cookie 说明），属正文不在此约束内。

## Stage 22 — 改为传统底栏四栏（2026-11-23）

按用户新要求，覆盖此前「不做底部多 Tab」的旧口径：导航改成微信式底栏，四个主栏目。

### 1) 栏目与命名（统一 2 字，动词/名词都成立）
| 序 | 名称 | 图标 | 对应页面 |
|---|---|---|---|
| 1 | **任务** | Home | 追剧任务列表（原主页） |
| 2 | **添加** | Add | 新增/编辑任务表单 |
| 3 | **日志** | List | 运行日志（原从主页图标进入） |
| 4 | **设置** | Settings | 网盘账号 / TMDB / 外观 / 关于 |

命名理由：四个都用名词短语、都 2 字，等宽显示不会有一个被挤成两行；「添加」而不是「新建」，与「任务」形成「任务 / 添加任务」的动宾关系，用户一眼知道这栏干什么；「日志」比「记录」更贴近内容（运行日志而非转存记录）。

### 2) 底栏实现（`ui/AppTabBar.kt`，新增）
- 高 64dp（另加系统手势条避让），顶部一条 `outlineVariant` 分隔线，底色 `surfaceContainer`。
- 四个等宽可点区（≥48dp 热区），选中态三处同时变化：图标与文字转主色、文字 SemiBold、图标下方一块 40×28 的主色浅胶囊底（`primaryContainer`）。这是「切换栏目时的状态显示」，不再靠猜当前在哪一栏。
- 图标只用 core 库已有的 Home / Add / List / Settings，不引扩展库。

### 3) 各页接入
- 任务页：顶栏只留标题「转存喵」；底栏选「任务」；**原四个键的胶囊拆除，只保留一个圆形「全部检测」按钮悬在底栏上方靠右下**，日志/设置两键取消（改由底栏承担）。
- 添加页：底栏 = 动作行（运行一次 / 保存）+ 四栏底栏；系统手势条只在最底部避让一次。
- 日志页：去掉左上返回箭头（作为一级栏目不再需要「返回任务」），顶栏保留标题 + 清除垃圾桶；底栏选「日志」；硬件返回仍回任务页。
- 设置页：去掉返回箭头，底栏选「设置」；硬件返回仍回任务页。
- 切栏逻辑统一走 `AppRoot.goTab()`：从「添加」切走时清空编辑态，避免从别的栏目回来仍停在编辑。

### 4) 验证（真机已装）
- `:app:compileDebugKotlin` / `:app:assembleDebug` 通过。
- 截图：`docs/t_home.png`（任务栏选中）、`docs/t_add.png`（添加栏选中，动作行在底栏之上）、`docs/t_log2.png`（日志栏选中）、`docs/t_set.png`（设置栏选中）。
- 注意：HyperOS 仍禁止 adb 注入点击，栏间切换需人工点验。

## Stage 23 — 卡片显示总集数 + 三段进度条 + 绑定 TMDB 自动改名（2026-11-23）

### 1) 第二行改成三个集数
`E10 → E20（共30）`：左端盘里到第几集，箭头后 TMDB 最新已播集，括号是全剧总集数（只有绑了 TMDB 才显示）。右端同步时间保留，左侧集数区改为 `weight(1f)`，空间不够时由「（共N）」省略，时间永不被压缩。

### 2) 进度条改为三段、按总集数配比
- 分母统一为 **TMDB 总集数**：
  绿段 = 盘里已有（`localEpisodes`）、黄段 = 已播未转（`tmdbLatest - have`）、浅橘粉段 = 尚未播出（`total - max(have, aired)`）。
- 例：已有 10 / 已播 20 / 共 30 → 绿 1/3 + 黄 1/3 + 粉 1/3（真机截图 `docs/bar2.png` 已确认）。
- 未绑 TMDB（无总集数）时退回原来的单段绿条，分母用最新集号，不画假的三段。
- 盘里集数超过总集数时只画绿到底；集号超过已播时黄段为 0。
- 计算抽成纯函数 `data/EpisodeSegments.kt`（`episodeSegments()`），新增 `EpisodeSegmentsTest` 7 例覆盖：三等分、全绿、超前、超总数、全黄、无总数、负数。
- 颜色：绿 `TaskColors.ok()`、黄 `TaskColors.behind()`、粉 `primaryContainer`（深色模式自动取深色版）。

### 3) 绑定 TMDB 后任务名改成官方名
「添加」页搜索选中某剧后，任务名直接覆盖为 TMDB 官方名（`item.name`，取不到退回「名称 (年份)」），同时清掉旧报错。用户确认口径是「直接覆盖，包括手打的名字」。

### 验证
- `:app:testDebugUnitTest` + `:app:assembleDebug` 通过（单测由 84 增至 91 项，全绿）。
- 真机：`docs/bar2.png` 三段条与「（共30）」正常；为截这张图临时往数据库插入了一行「演示用·恋爱博士」，截完已还原原库（当前任务列表为空，与操作前一致）。

## Stage 24 多源转存（一个任务最多 5 个分享链接）

### 口径
- 一个任务只属于一个网盘：网盘由任务的保存位置决定，链接行不存网盘类型，从根上不跨盘（粘别的盘链接会在该行就地红字拦截）。
- 一个任务 = **一套规则 + 多个源**：保存位置 / 季度 / 命名规则 / tmdb id / 更新日 / 播出时刻全任务共用，源只是多几个链接入口。
- 链接区：第 1 行右端是「+」（点一次在末尾追加一行空框，满 5 行置灰）；第 2~5 行右端是「−」（直接删掉这一行）。第 2 行起行首小字「源2 / 源3」。
- 每行就地校验：链接无法识别 / 与前面某行重复 / 不是本任务网盘，都在该行下方红字，不等点保存；空行保存时自动丢弃。

### 代码
- 新增 `data/db/TaskSourceEntity.kt`（`task_sources`：taskId / position / shareUrl / shareId / passcode / createdAt，索引 taskId）。
- `AppDatabase` 7 → 8 + `MIGRATION_7_8`：建表 + 索引 + 把老任务的 `shareUrl/shareId/passcode` 搬成 `position = 0` 一行；只加表不改 `tasks` 结构，可回滚。
- `TaskRepository`：`TaskCard` 增 `sourceCount`；`sourcesOf` / `saveSources`（先删后插、顺序即行序，同时把第一行镜像回 `tasks` 旧字段）；`delete` 连带删源；卡片流改成任务 / 快照 / 源三路 `combine`。
- 新增 `data/SourceLinks.kt`（纯函数：去空、去重、上限 5、全空退回任务旧 shareUrl）。
- `TaskRunner`：`run` 增 `draftLinks`（试跑用表单里的多行）；目录只建一次后逐源「解析链接 → 拿令牌 → 拉树 → 改名计划 → syncFlat」；某源异常只记「源K 失败：原因」继续下一个源，掉线类异常仍交给外层重登重跑；`linksOf` 走 `SourceLinks.normalize`。
- `TreeSyncEngine.Report` 增 `explicitOk`（null = 沿用 failures 判空）：多源里「源2 失败但源1 成功」这一轮算成功，失败原因仍留在 failures 里提示。
- `TasksViewModel`：`createTask / saveEdit / runDraft` 参数由 `link: String` 改为 `links: List<String>`，新增 `parseLinks`（逐行识别 + 同盘校验）；新增 `sourceLinks(taskId)` 供编辑页回填。
- `NewTaskScreen`：链接框改成 `LinkSlot` 列表（稳定 id，删中间行不串内容），第 1 行「+」/ 其余「−」，每行就地红字，保存/试跑都传 `links`；编辑时按 `sourceLinks` 回填。
- `TaskListScreen`：卡片底行源数 > 1 时加「· 2 源」。
- `MainActivity`：调试入口新增 `debugShareUrl2`（第二个源）；新增 `wakeScreen` extra（HyperOS 拒 adb input 注入，截屏前靠 App 自己点亮屏幕），`AndroidManifest` 只加了普通权限 `WAKE_LOCK`。

### 验证
- 单测 91 → 99 项全绿（新增 `SourceLinksTest` 8 例：单源 / 空行 / 重复 / 顺序 / 超五个 / 全空退回 / 空列表 / 有值忽略 fallback）。
- 真机装包后读库确认真实迁移成功：`user_version = 8`，`task_sources` 列齐全、索引 `index_task_sources_taskId` 存在，现有 2 个任务各搬出 1 行 `position = 0`，`shareId` 与 `tasks` 一致。
- 界面截屏待补（当时手机锁屏、adb 无法注入解锁事件）。

## Stage 25 添加页输入框与按钮对齐（多源排版收尾）

### 口径
- 去掉链接行的「源2 / 源3」序号：所有输入框左边缘、右边缘、宽度完全一致，行与行靠 placeholder 区分（第 1 行「分享链接」，其后「附加源链接」）。
- 任务名称右侧的搜索按钮从输入框里拆出来，改为独立的 48dp 方块描边按钮，和链接行的「+ / −」排成同一条竖线、同尺寸、同圆角（12）、同描边色。
- 输入框与按钮统一 48dp 高：文字是 16sp，56dp 会让上下各空 20dp 显得「框大、字小」；48dp 上下各 16dp，且正好等于最小可点尺寸。
- 满 5 行时「+」置灰不隐藏，避免行位跳动。

### 关键修法（真机踩到的坑）
- 直接把 `OutlinedTextField` 压到 48dp 会把文字上下裁掉（截图里「任务名称」顶部缺一截），因为它的内部最小高度是 56dp、内边距是上下各 16dp。
- 改为 `BasicTextField` + 自绘边框（`CompactStringField` / `CompactValueField`）：高度由自己掌控，文字 `CenterVertically` 居中，不裁字；聚焦时边框转主色 2dp，错误时转 error 色。
- 链接行仍用 `TextFieldValue` 版本以保留「失焦后光标回开头、长链接只显示前段」的行为。
- 「−」用等宽半角减号字符（material-icons core 包没有 Remove/Minimize 图标），与「+」线宽观感一致。

### 调试入口
- 新增 `--ei sourceRows N`：进「添加」页直接摊开 N 行空链接，用来截屏验收多源排版。

### 验证
- 单测 99 项全绿。
- 真机 `docs/n3.png`：4 行链接（1 主 + 3 附加）、右侧一列 48dp 方块按钮（放大镜 / + / − / − / −），框高与按钮一致，文字完整居中不裁。

## Stage 26 添加页：统一控件高度 48dp + 补「任务」分区标题

### 口径
- 全页可点控件统一 48dp 高：输入框、右侧方块按钮、保存位置 / 播出时刻设置行、季度与命名规则小选项、星期格（原 40dp）。
- 文字一律垂直居中且完整显示：设置行改为固定 48dp 高 + 上下不再加 12dp 内边距，改由 Row 的 CenterVertically 居中；标题单行超长走省略号，不压成两行把行高撑开。
- 设置行图标统一 20dp（原来默认 24dp，把行撑到 56dp）。
- 顶部补一个「任务」分区标题，让「任务名称 + 分享链接」与下面「保存位置 / 季度 / 命名规则 / 更新日 / 播出时刻」同一层级，不再是没标题的裸字段。

### 验证
- 单测 99 项全绿；`:app:assembleDebug` 通过。
- 真机截图待补：装包时手机无线 adb 掉线（IP 192.168.31.145:5555 已不可达），APK 已构建完成，重连后再装并截屏。

## Stage 27 源状态（多源失效提示）

### 口径（用户确认：方案 A + 编辑页圆点 + 两级判定）
- **两级判定**：一次没连上 = `WARN`（琥珀），连续两次 = `FAIL`（红，才算"失效"），成功一律回 `OK`（绿）；从没测过 = `UNKNOWN`（灰）。避免网络抖动把源误标成失效。
- **卡片**：只在"多源 + 有 FAIL"时，底行尾部追加红字「· N 失效」（如「周二 · 光鸭 · 自动 · 2 源 · 1 失效」）；正常时不出现任何字样，单源任务不追加（仍走原来那行红字「上次失败 · 原因」）。
- **编辑页**：每行右侧、`−` 按钮左边加 8dp 小圆点（绿/琥珀/红/灰），固定占位以保持按钮列对齐；琥珀与红只在该行下方写一行原因，绿色与灰色不写任何字样。
- **状态只做提示**：不参与任何转存判断；一个源失效其它源照跑，整轮仍按"至少一个源成功即成功"。

### 代码
- `TaskSourceEntity` 加 `lastStatus / lastError / lastCheckedAt` + `statusAfterRun(previous, ok)` 纯函数（两级判定）。
- `AppDatabase` 8 → 9 + `MIGRATION_8_9`（只给 task_sources 加三列，默认 UNKNOWN / '' / 0）。
- `TaskSourceDao.updateStatus(...)`；`TaskRepository.markSourceStatus(...)`（原因截断 200 字）。
- `TaskRunner`：新增 `RunTarget(position, url, previousStatus)`；`targetsOf` 区分"试跑用表单链接 / 正式执行读 task_sources / 老数据退回 task.shareUrl"；每个源跑完无论成败都回写状态（WARN/FAIL 带原因），试跑不落库。
- `TaskCard` 加 `failedSources`；卡片流统计 FAIL 数量。
- `TaskListScreen`：底行改用 `buildAnnotatedString`，「· N 失效」单独着 error 色。
- `NewTaskScreen`：`SlotState(status, error)` 与 slots 一一对应，加源/删行同步维护；新增 `SourceStatusDot`；编辑时用 `viewModel.sourceRows(taskId)` 回填链接 + 状态。
- 删源时状态记录随行删除；编辑保存后源行重建，状态自动回到"未检测"（链接换了，旧结论作废）。

### 验证
- 单测 99 → 104 项全绿（新增 `SourceStatusTest` 5 例：首次失败 WARN / WARN→FAIL / FAIL→FAIL / 成功回 OK / 单源抖动不算失效）。
- `:app:assembleDebug` 通过；真机安装与截屏仍待手机无线 adb 恢复（IP 192.168.31.145:5555 当前不可达）。

### 真机验收（2026-11-23 21:00，设备改为 192.168.31.158:5555）
- `docs/s1.png`：Stage 27 装机后主页基线（龙珠超 E6→E7 共19「缺 1 集」、玫瑰战争 转存完，底栏四栏）。
- `docs/s2.png`：注入一条 FAIL 源后的卡片 —— 底行「每日更新 · 光鸭 · 2 源 · 自动 · 1 失效」，「1 失效」为 error 红字。
- `docs/s3.png`：编辑页 —— 任务名称 + 搜索方块；两行链接右侧圆点（第 1 行绿 OK、第 2 行红 FAIL）固定占位对齐；红点行下方红字「分享已被取消或链接已失效」；保存位置/季度/命名规则控件高度一致；底部「运行一次 / 保存」等宽。
- `docs/s4.png`：演示数据已回滚，主页恢复正常（无「2 源」「失效」字样）。
- 注意：手机 DHCP 换了地址，原 `192.168.31.145:5555` 失效，现为 `192.168.31.158:5555`（已更新 `F:\下载\last_ip.txt`）。
- 待优化观察：卡片底行顺序为「每日更新 · 光鸭 · 2 源 · 自动 · 1 失效」，「2 源」夹在「光鸭」与「自动」之间略显突兀，可考虑把「N 源」移到末尾或与「N 失效」相邻。

## Stage 28 失效链接改「划线」（去掉状态圆点）

### 原因
Stage 27 在链接行右侧放了 8dp 状态圆点，虽然固定占位，但它吃掉了输入框宽度，
导致链接框比「任务名称」框窄一截（用户截图指出）。

### 做法（用户口径：失效链接直接在文字上划一道横线，不占框宽）
- 删除 `SourceStatusDot` 及其调用，链接行恢复「框 weight(1f) + 8dp + 48dp 方块按钮」，
  与任务名称框等宽、左右边缘齐平。
- `CompactValueField` 新增 `status` 参数：FAIL → 文字转 `error` 红 + `TextDecoration.LineThrough` 红线；
  WARN → 文字转琥珀 + 琥珀划线；OK / UNKNOWN → 不划、保持原色。
- 原因行保留：FAIL 红字、WARN 琥珀字，仍只有出问题的行才出现。
- 删除无用的 `SourceStatusDot` 组合函数。

### 验证
- `:app:assembleDebug` 通过，已装机。
- 真机 `docs/s5.png`：三行链接框等宽对齐，第 1 行正常无划线，第 2 行红色划线 + 红字原因，
  第 3 行琥珀划线 + 琥珀原因；演示数据已回滚。

## Stage 29 未绑 TMDB 的卡片状态（修「永远未同步」）

### 问题
`SnapshotCalc.status` 只看 TMDB：没有 tmdbLatest / tmdbTotal 就一律返回 UNKNOWN，
`StatusBadge` 把 UNKNOWN 画成「未同步」。于是没绑 TMDB 的任务，即使盘里已经是最新的，
卡片也永远挂着一个灰标「未同步」——状态从来没去看「这一轮跑成功没、转进来多少集」。

### 口径（用户确认）
- 没绑 TMDB 不谈「缺几集」，只讲这一轮的结果，文案要短：
  `LOCAL_ADDED` → 「已转存」（绿）、`LOCAL_LATEST` → 「已是最新」（绿）、从未跑过仍是「未同步」（灰）。
- 没绑 TMDB 的进度条改画**一条蓝色整条**（不是绿、不是黄）：既不是缺集也不是收齐，
  只表示「跑通且正常」；未同步才留空条。
- 卡片第二行：没 TMDB 时不再画「E12 → E—」，改成「E12 共12集」。
- 底行「未绑定 TMDB」缩成「无 TMDB」，避免把整行挤出卡片。
- 集数行判断绑没绑 TMDB 改用 `tmdbLatest > 0 || tmdbTotal > 0`，与引擎一致。

### 代码
- `SnapshotEntity` 加 `addedEpisodes` 列 + 两个状态常量 `STATUS_LOCAL_ADDED / STATUS_LOCAL_LATEST`。
- `AppDatabase` 9 → 10 + `MIGRATION_9_10`（只给 snapshots 加 `addedEpisodes INTEGER DEFAULT 0`）。
- `SnapshotCalc.status(...)` 增 `addedEpisodes` 参数，`build(...)` 同步透传。
- `TaskRepository.finishRun`：无 TMDB 时用「本次集数 - 上次快照集数」算出本轮新增。
- `TaskListScreen`：`StatusBadge` 两个新分支；`TaskColors.local()` 蓝色；
  `EpisodeBar` 无 TMDB 分支改蓝色整条；集数行与底行文案调整。
- 单测 104 → 106 全绿（SnapCalcTest 新增「跑过有集数=已是最新」「有新增=已转存」「一集都没有=未同步」）。

### 验证
- `:app:assembleDebug` 通过，已装机（v10 迁移在真机上跑通，`user_version=10`、`addedEpisodes` 列存在）。
- 真机 `docs/s6.png` / `docs/s7.png`：两张演示卡分别为「已是最新」「已转存」+ 蓝色整条；
  真实任务（缺 1 集 / 转存完）不受影响。
- 演示数据已回滚（`docs/s8.png`）：主页与装之前一致。

## Stage 30 卡片「运行中」改文字阶段提示（去掉转圈）

### 口径
- 跑的时候左侧刷新图标整个不显示，**就在原位置写一句文字**：不加胶囊、不加底色、不去考虑宽度问题。
- 文字：`准备中…` / `读取目录…` / `比对文件…` / `转存 3/20…` / `改名 5/12…` / `统计中…`
- 有数字的两步不加动效（数字自己在跳），没数字的四步把省略号单独拿出来做透明度呼吸
  （0.35 ↔ 1，1.2s 来回，`rememberInfiniteTransition` + `graphicsLayer.alpha`），宽度恒定不会挤动右边元信息。
- 跑完（无论成功失败）都回到原来那个静止的刷新图标，不显示「完成」字样；结果仍由徽标与底行红字表达。
- 多源时文案前面加「源2 」，计数按源各自从头算。

### 代码
- `TreeSyncEngine`：新增 `videoTotal` / `renameTotal` 分母；`syncFiles` 开始时上报 `比对文件`，
  `restoreChunk` / `syncFile` 每落地一个文件上报 `转存 n/总`，每改成一个上报 `改名 n/总`。
- `TaskRunner.run(..., onPhase)`：`准备中` → 每源 `读取目录` → 引擎各阶段 → 收尾 `统计中`；`withSource()` 加源前缀。
- `TasksViewModel`：`_running` 由 `Set<Long>` 改为 `Map<Long, String>`（任务 id → 当前文案），
  `runOnce` / `runDraft` 通过 `onPhase` 实时更新；`running[x]` 取文案、`in running` 判是否在跑。
- `TaskListScreen`：新增 `RunPhaseText(text)` 组合函数；`TaskRow` 增 `runText` 参数；
  运行分支由 `CircularProgressIndicator` 换成该文字。

### 验证
- `:app:assembleDebug` + 单测通过。
- 真机抓到的中间态：`docs/r1.png`「准备中…」、`docs/r3.png`「比对文件…」、`docs/r5.png`「转存 6/7…」；
  同时另一张卡（未在跑）仍是静止刷新图标。

## Stage 31 运行态文字配色：正常蓝 / 成功绿 / 失败红

### 问题
阶段文字原来用 `colorScheme.primary`，而本主题的 primary 是橙棕色，看起来像报错红字。

### 口径
- 正常进度（准备中 / 读取目录 / 比对文件 / 转存 n/总 / 改名 n/总 / 统计中）**一律蓝色**（复用 `TaskColors.local()` 的蓝，深浅色各一套）。
- 跑完在同一个位置闪一句结果：成功绿「完成」、失败红「失败」，1.5 秒后自动收回成静止刷新图标。
- 失败的具体原因仍由底行那行红字「上次失败 · 原因」表达，不重复。

### 代码
- `TasksViewModel`：新增 `_finished: Map<Long, Boolean>`；`runOnce` 的 `finally` 里写入结果并 `delay(1500)` 后清除。
- `TaskListScreen`：`RunPhaseText(text, color, animated)` 接受颜色；运行时传蓝、完成态传绿/红且不做呼吸动画；
  `TaskRow` 增 `justFinished` 参数，屏幕收集 `finished` 流。

### 验证
- `:app:assembleDebug` 通过，已装机。
- 真机截屏这次没取到：手机处于 doze（`mWakefulness=Dozing`），HyperOS 拦截 `input keyevent WAKEUP`（INJECT_EVENTS），
  应用内的 wakelock 这次也没点亮屏幕。等手机解锁后再补运行态与完成态的截图。

## Stage 32（2026-11-23）修复「改名全部撞成同一张」

**现象**：真机跑「仙剑奇侠传三」，耗时 43s、新增 26、改名 1，运行日志刷出几十行
`改名失败：Sword.and.Fairy.3.2025.S01E0X...mp4 -> 仙剑奇侠传三.S01E03.mp4`——所有集都指向同一个目标名。

**根因**：`rename/FileNameContext.kt` 的 `episodeOf()` 把 6 种集号格式塞进**一条 alternation 正则**再用 `Regex.find()`。
Kotlin 的 `find` 取的是「全文最靠前的匹配」，不是「最靠前的候选分支」：
`Sword.and.Fairy.**3**.2025.S01E02...` 里片名那个「3」（第三部）位置比 `S01E02` 更靠前，
被最后那条宽松的「孤立数字」分支抢中 → 26 个文件的集号全等于 3 → 全部改名到 `S01E03.mp4`，
第一个（真 E03）成功，其余因同名互撞被服务器拒绝。

对照：`core/model/EpisodeParser.kt`（算「盘里已有多少集」）本来就是分条按优先级 `find`，所以集数统计没受影响，
只有改名路径中招——这也解释了「新增 26 是对的、改名全错」。

**改动**
1. `FileNameContext.episodeOf()` 重写为**按优先级逐个全文查找**：`SxxExx` → `第N集` → `EP/E 前缀` → 开头数字 → 分隔符包裹数字 → 孤立数字兜底；每个候选都校验 `< 1000`（排除年份/分辨率）。
2. `TaskRunner.buildRenamePlan()` 加**同目标去重**：同一轮里两个文件算出同一个新名时，只保留第一个，其余保留原名不发请求——避免解析再出错时又刷一屏「改名失败」。
3. 新增 `FileNameContextTest`（5 例），直接用真机那批真实文件名断言集号与「通用视频命名」的输出各不相同；单测 106 → 111 全绿。

**验证**：装机后重新对该任务跑一次即可自愈——盘里已是原名的那批文件会走「本地已有原名 → 就地改名」分支，
不需要重新转存。

**提交**：`e688ec4`

## Stage 33（2026-11-23）「运行一次」的三层反馈 + 草稿表单不丢

**问题**：点「运行一次」几乎没有反馈——按钮可连点、页面不显示阶段、跑完也没结论；
`runDraft` 只把「正在跑一次…」推进 `toast`，而添加页根本没订阅 toast；表单状态全在 `remember` 里，
切到别的栏目再回来一片空白。

**改动**
1. `TasksViewModel` 新增 `draftRunning / draftPhase / draftResult` 三个状态：
   - 跑的时候 `draftRunning=true`，`onPhase` 同步写进 `draftPhase`（准备中 → 读取目录 → 比对文件 → 转存 n/总 → 改名 n/总 → 统计中）；
   - 跑完在 `finally` 收尾，结果按 `TreeSyncEngine.Report` 拼成一行：成功「完成 · 新增 X · 改名 Y（· N 项失败）」，失败「失败：第一条原因」。
2. 添加页动作行上方新增 `DraftRunText`：不带胶囊、不带底色，同一个位置换字。
   跑中蓝色（`TaskColors.local()`）+ 无数字的步骤让省略号做透明度呼吸；跑完成功绿 / 失败红，**常驻到下次再跑**。
3. 跑的过程中「运行一次」与「保存」一起置灰（避免重复点、也避免跑到一半把参数存下去）。
4. `DraftForm` 草稿快照：新建页所有字段（名称 / 多行链接 / 网盘 / 保存位置 / 季度 / 规则 / TMDB / 播出时刻 / 更新日模式）随改随存进 ViewModel，
   切 tab 回来按快照回填；保存落库后清掉快照。只存内存，退出 App 不留。

**验证**：单测 111 全绿，编译通过，已装机。真机验收待用户手动点一次「运行一次」（HyperOS 拦 adb 注入）。

**提交**：见本次 commit

## Stage 34（2026-11-23）转存+改名提速

**耗时在哪**（26 集的仙剑任务 43 秒，逐项核对）：
1. 改名串行：一个文件一次 HTTP 请求，26 集就是 26 个来回；
2. 每改名成功一次就 `destCache.remove(destFid)` —— 下一次判断又要**重新列一遍整个目录**，几十集就是几十次多余的分页列表请求；
3. 光鸭转存后轮询任务状态一律 `delay(1000)`，目录 diff 也是 1 秒一次，每批至少白等 1~2 秒。

**改动**
1. `TreeSyncEngine` 目标目录改成**内存索引**（`文件名 → DriveFile`），同一次同步同一目录只列一次；
   改名成功后直接在索引里换键，不再清缓存、不再重列。转存落盘的文件也按原名先进索引，改完名再换键——整个「比对 → 转存 → 改名」链路几乎不再产生额外列表请求。
2. 新增 `renameMany()`：改名请求按 `RENAME_CONCURRENCY = 4` 并发发出（`Semaphore` 限流，`Dispatchers.IO`），失败计数与日志仍按顺序结算，不会多协程抢计数。
   三处都改走它：① 盘里已有原名的批量就地改名；② 每批转存后 8 个文件的改名；③ 新目录里的深层改名。
3. 光鸭轮询改为「先快后慢」：任务状态前 5 轮每 400ms 查一次、之后 1 秒；目录 diff 前 5 轮 300ms、之后 1 秒。
4. 就地改名失败现在也会写进 failures（以前是静默跳过），日志里能看到是哪一集。

**预期**：同一任务从「6 次目录列表 + 26 次串行改名」降到「1~2 次列表 + 7 轮 4 并发改名 + 更短的等待」，几十集的任务普遍能省掉一半以上时间。

**验证**：新增单测 `一批已有原名的文件 只读一次目录 并发改名全部生效`（12 个文件断言 `listCalls <= 2`、改名 12 个、结果名各不相同），单测 111 → 112 全绿；已装机。

## Stage 35（2026-11-23）文件名排序口径：数字 → 汉字 → 字母

**问题**：转存后目录里的顺序看着乱。根因是旧的自然序实现先比文字段、再比数字段，
且文字段按字符码序——汉字（U+4E00 起）会整体排到字母后面，中文剧集的目录被拆得七零八落。

**新口径**（`core/model/ShareNode.kt` 的 `NaturalOrder`，同时决定「转存/改名的先后」）：
1. 数字开头的排最前，数字按数值大小（`1-200` < `2-4K` < `19-4K` < `219-4K`）；
2. 汉字开头其次（按字典序，`第1季` < `第2季` < `第10季`）；
3. 字母开头再其次（`EP1` < `EP10`，不会出现 EP10 排在 EP2 前）；
4. 同一串里数字段永远排在文字段前面；前导零不影响数值序（`01` 与 `1` 相邻）。

这条例既用在目录列表（保存位置选择器、分享树扫描），也决定每批转存的发起顺序，
所以网盘里文件的创建顺序会跟着它走——网盘前端若按时间排，看到的就是顺序整齐的目录。

**验证**：新增 `NameOrderTest` 6 例（数字类、EP 类、数字/汉字/字母三类混排、中文剧名+集号、季目录、前导零），
单测 112 → 118 全绿；已装机。

## Stage 36（2026-11-23）绑定 TMDB 时任务名带年份

**需求**：绑定 TMDB 后，任务名要带年份，这样目录名与文件名也都会带年份。

**改动**：`NewTaskScreen` 选择 TMDB 结果时，任务名取 `TmdbSearchResult.title()`（即「官方名 (年份)」），
不再只取 `name`。文件名模板里的 `{TASKNAME}` 用的就是任务名，所以
目录 `征途 (2026)/`、文件 `征途 (2026).S01E01.mp4` 一起带上年份；同名翻拍不会混在同一个保存位置里。
年份为空（TMDB 无首播日期）时自动回落成纯剧名。

**验证**：编译通过，单测 118 全绿，已装机。

## Stage 37（2026-11-23）改名失败原因落日志 + 自动重试

**日志现象**（征途 (2026)：37s / 新增 28 / 改名 8）：目标名已经是对的、每集都不同
（`Spark of the Long March (2026) - S01E01 … -> 征途 (2026).S01E01.mkv`），但仍有一批「改名失败」。
集号解析这次没问题，问题在请求被拒。

**判断**：28 集只成功 8 个，且前面几批能过、后面开始失败——典型的光鸭**改名限流**
（一轮里连续改名十几个之后开始拒绝）。之前失败日志只写「旧名 -> 新名」，看不到服务器原话，没法确认。

**改动**
1. `renameWithRetry()`：单个改名最多尝试 3 次，失败后退避 1s / 2s 再发；
   仍失败才记一条 `改名失败：旧名 -> 新名（服务器原因）`，把真实原因带进运行日志。
2. 四处改名（就地改名 / 批内改名 / 兜底单转存 / 深层改名）全部改走带重试的路径。

**下一步**：重跑一次，如果日志里还出现红色失败行，看括号里的原因就能定位是限流还是文件名不合法。

**验证**：单测 118 全绿，编译通过，已装机。

## Stage 38（2026-11-24）改名限流自适应 + 断网不再刷屏

**依据**：用户两部连跑的真实日志。
- 尚公主（40 集）：`重命名失败：操作过于频繁，请稍后再重试`，成功 39、失败 1 —— 光鸭**改名限流**确认。
- 征途（28 集）：`Unable to resolve host "api.guangyapan.com"` 满屏 —— 跑到一半断网（WiFi 切 5G），
  原来每批失败会退回逐个转存，于是 20 个文件各刷一条同样的错。

**改动**
1. 改名并发 4 → 2，降低撞限流概率；遇到「操作过于频繁」类原因按尝试次数冷却 2.5s / 5s / 7.5s，
   普通失败退避 1s / 2s / 3s，最多 4 次。
2. 转存加 `restoreWithRetry()`：网络类错误（DNS 解析失败 / 超时 / 连接失败）退避 1.5s、3s 重试，
   共 3 次；仍失败则置 `aborted`，整轮中止只记一条「网络中断，本轮中止：原因」，不再逐文件刷错。
3. 判错工具 `isThrottled()` / `isNetworkError()`（中英文关键词都认）。

**验证**：新增单测「断网时只记一条失败并中止，不会逐个文件刷错」（16 文件断言 failures 只有 1 条、
转存请求恰好 3 次后停）；单测全绿，APK 已构建并装机。

## Stage 39（2026-11-24）深度卡在 3 导致「分享里没有视频」+ 失败信息补全

**现象**：蓝色星球3 (2026) 任务 3 秒失败，只有一句「分享中没有找到可转存的视频文件」。

**判断**：分享树默认只扫 3 层。光鸭常见结构「剧名 / 第 3 季 / 2160p / 视频」已经到第 4 层，
深度卡在 3 就会被判成「没有视频」——链接本身和令牌都正常（否则报的是「分享不存在 / 需要提取码」）。

**改动**
1. `TreeSyncEngine.DEFAULT_MAX_DEPTH` 3 → 5（= 接口允许上限），只多几条列表请求，避免漏掉深层视频。
2. 找不到视频时把实际看到的写进错误里：`分享中没有找到可转存的视频文件（根下 N 项：蓝色星球3/、…；共 M 个目录，最深 K 层）`，
   这样下次再遇到能直接判断是分享空了、深度不够、还是扩展名不认。

**验证**：单测全绿，编译通过，已装机。

## Stage 40（2026-11-24）「分享里没有视频」真因：季度填错，报错补上季号提示

**实测（直接调光鸭接口读了一遍这个分享）**：
`https://www.guangyapan.com/s/1964336750546718721_ad9c8o8EdLN_2FYg#/share`
结构三层——`Blue Planet III (2026) {tmdb-208929}/` → `Season 1/` → 6 个 `.mkv`（共 48GB）。链接、令牌、深度都没问题。
再读手机库：任务「蓝色星球3 (2026)」的 **`seasonNo = 3`**，而分享里只有 `Season 1`，
于是 `collectVideos` 把唯一的季整棵跳过 → 3 秒判空。

**改动**：找不到视频时先看「季号对不对得上」，对不上就直接指出来：
`只取第 3 季，但分享里只有 第 1 季；把任务里的「季度」改成 1 再跑`。
对得上才退回结构描述（根下 N 项 / 共 M 个目录 / 最深 K 层）。

**Stage 39 的深度 3→5 保留**（真实资源里四层结构常见），但它不是这次的原因。

**验证**：单测全绿，编译通过，已装机（lastUpdateTime 07:46:54）。

## Stage 41（2026-11-24）季度改成 5 个按钮：平铺 / Season 1 / Season 2 / 自定义 / 多季

**需求**：季度选项重排为固定 5 个按钮，并把「多季」变成真能用的模式。

**口径**
- `0` 平铺：视频直接放 `剧名/` 下（原「不选」）。
- `1` / `2`：`剧名/Season 1/`、`剧名/Season 2/`（原「第 N 季」，但只保留 1、2 两个快捷按钮）。
- `>2`：自定义，按钮文字显示「第N季」，弹框输入（原逻辑保留）。
- `-1` 多季：**按每个文件自身的季号分组**，各自进 `剧名/Season N/`；季号解析不出来的散文件留在剧名根下。
  改名模板里的 `{SXX}` 一直取自文件名，因此 S02E01 写成 `…S02E01.mkv`，不会错成 S01E01。

**改动**
1. `TaskEntity`：加 `SEASON_FLAT = 0` / `SEASON_MULTI = -1` 常量并注释三种取值语义（DB 无需迁移，seasonNo 仍是 Int）。
2. `NewTaskScreen`：季度行改为 5 个 `SmallChoice`（平铺 / Season 1 / Season 2 / 自定义 / 多季），
   自定义按钮的选中条件从 `>3` 改为 `>2`（1、2 已被固定按钮占位）。
3. `TreeSyncEngine.syncFlat(..., multiSeason)` + 新增 `Session.runFlatMulti()`：
   收集全部视频（不做季过滤）→ `groupBy(EpisodeParser.seasonOf(name) ?: 0)` → 每季 `ensureFolder(destRootFid, "Season N")` 后按季 `syncFiles`；
   多季时先报一行「共 N 季」。空分享仍走 `noVideoMessage`。
4. `TaskRunner`：`seasonNo == SEASON_MULTI` 时不再预先建 `Season N`（交引擎按季建），并把 `multiSeason` 传下去。
5. 测试：`FakeDriveAdapter` 补 `ensureFolder`（存在即复用、否则新建）；新增单测
   「多季模式按文件季号各进 Season N 且改名保留各自季号」（4 个文件 S01×2 / S02×2，断言根下只有两个季目录、
   每季 2 个文件且文件名季号与目录一致）。单测 119 → 120，全绿。

**验证**：`testDebugUnitTest` 120/120 通过，`assembleDebug` 通过，已装机（lastUpdateTime 08:20:59）。

## Stage 42（2026-11-24）季度 0 = 特别版（Season 0），与「没选季度（平铺）」彻底分开

**用户纠正**：`0` 在季号里是**真实存在的一季**（TMDB 的 Season 0 = 特别篇），不能拿 0 当「不选季度」。
否则会出现两个错：① 想收特别版时表达不了；② 多季分享里的 `Season 0` / `Specials` 目录被当「无季」平铺到剧名根下。

**新增/改动的取值语义**
- `SEASON_FLAT = -2`：没选季度 → 视频直接平铺在 `剧名/` 下（老库里的 0 全部是这层意思）。
- `SEASON_SPECIALS = 0`：特别版 / Season 0 → 放 `剧名/Season 0/`，正常当一季转存。
- `1..99`：指定季 → `剧名/Season N/`。
- `SEASON_MULTI = -1`：多季 → 按文件自身季号分组。

**改动**
1. `TaskEntity`：`seasonNo` 默认值改 `SEASON_FLAT`，常量注释写清三种/四种取值。
2. `AppDatabase` 版本 10 → 11，新增 `MIGRATION_10_11`：`UPDATE tasks SET seasonNo = -2 WHERE seasonNo = 0`
   （老数据里的 0 都是「平铺」，迁移后语义不变）。
3. `SeasonParser`：新增特别版识别 —— `Season 0`、`Specials`/`Special`、`SP`/`SPs`、`S00`、`特别篇`、`特别版`、`花絮` → 0；
   放在常规季之后判断，`S01 SP04` 这种仍解析成 1，不会被误判成特别版。
4. `TreeSyncEngine`：
   - `syncFlat(seasonNo)` 的过滤条件从 `>0` 改 `>=0`：选 0 时只取特别版目录；
   - 新增 `collectVideosWithSeason()`：文件名季号优先，解析不到就用**最近一层能解析出季号的目录名**兜底
     （`Season 2/xx.mkv` 这类文件不带季号也能归到 Season 2）；
   - `runFlatMulti()` 分组：`Season 0`/`Specials` 正常建 `Season 0` 目录，**只有季号完全解析不出的文件才留根**；
   - 找不到视频的提示区分人话：`只取特别版（Season 0），但分享里只有 第 1 季…`。
5. `TaskRunner`：建季目录的条件 `>0` 改 `>=0`（0 也建 `Season 0`）；多季时仍交引擎逐季建。
6. `NewTaskScreen`：自定义按钮在 `seasonNo == 0` 时显示「Season 0」并保持选中；自定义弹框允许输入 0，
   底部加一行说明「0 = 特别版（Season 0）；不选季度请用「平铺」」。
7. `TasksViewModel`：`seasonNo` 默认值 `0` → `SEASON_FLAT`。

**测试**：`SeasonParserTest` 加「特别版目录名解析成 0」「带 SP 字样的常规季不误判」；
`TreeSyncEngineTest` 加「多季模式把特别版当成正常一季 进 Season 0」（Season 1 / Season 2（文件名不带季号）/ Specials 混合，
断言根下只有三个季目录、没有散文件）。单测 120 → 123，全绿。

**验证**：`testDebugUnitTest` 123/123 通过，`assembleDebug` 通过。

## Stage 43（2026-11-24）中文季名补全：第一/二/三季、第零季、第一部、番外/OVA/OAD

**问题**：季目录不一定写英文。中文季名如果解析不出来，多季分组会把它当「无季」平铺到剧名根下，
单季任务也筛不到它（等于整季丢失）。

**改动（`SeasonParser`）**
1. 数字季支持「季 / 部 / 篇 / 辑」四种量词：`第 2 季`、`第2部`、`第三篇`。
2. 中文数字补「零」：`第零季` → 0（特别版）；原有 `第一季`/`第二季`/`第三季`/`第十二季`/`第二十一季` 覆盖不变，
   带分辨率后缀的 `第二季 2160p HDR` 也认。
3. 英文大小写统一走 `(?i)`：`season 3`、`SEASON 3`、`s02` 都认（原来只认首字母大写的 `Season` 和 `S02`）。
4. 特别版中文/外文补齐：`特别篇`、`特别版`、`花絮`、`番外(篇)`、`OVA`、`OAD` → 0，
   再加上原有的 `Specials`/`SP`/`S0`/`Season 0`。
5. 判定顺序保持：常规季（数字/英文/短格式/纯数字）→ 中文数字季 → 特别版。
   这样 `S01 SP04` 仍解析成第 1 季，不会被「SP」抢成特别版。

**为什么中文季名一定要认**：季目录解析出来的季号，同时供三处使用 ——
① 单季任务的目录过滤；② 多季模式的分组落盘（`剧名/Season N/`）；③ 「季号对不上」时的报错文案。
任一处解析失败，用户看到的就是「分享里没有视频」或「特别版被平铺到根目录」。

**测试**：`SeasonParserTest` 新增 4 条（中文第一至第三季 / 第零季·第一部·第三篇 / 番外 OVA OAD / 小写 season 与 s02）。
单测 123 → 127，全绿。

**验证**：`testDebugUnitTest` 127/127 通过，`assembleDebug` 通过。（手机离家，本轮未装机）

## Stage 44（2026-11-24）季号冲突处理：文件名优先、目录名兜底、冲突弹窗确认

**用户经验（本轮落地口径）**：`S02E01` 这种文件名本身就说明是第二季，**优先级高于文件夹名**。
真实分享很不规范，只有一侧有季号的情况大量存在，所以判定表按「静默能判、冲突才问」来做。

**判定表（每个视频一条）**
| 文件名季号 | 目录季号 | 处理 | 弹窗 |
|---|---|---|---|
| 有 | 无 | 用文件名，日志注明「按文件名」 | 不弹 |
| 无 | 有 | 用目录名，日志注明「按目录名」 | 不弹 |
| 都有且一致 | 一致 | 直接用 | 不弹 |
| 都有但冲突 | 冲突 | 默认按文件名；新建/编辑页「运行一次」先弹窗确认 | 弹 |
| 都没有 | — | 落剧名根目录，日志注明「无季号 n 个」 | 不弹 |

**新增 `Transfer/SeasonResolver.kt`（纯函数，无网盘依赖）**
- `SeasonResolver.resolve(tree, maxDepth)` → `SeasonPlan`（每个视频的 `fileSeason` / `dirSeason` / 证据目录名 / 来源 FILE|DIR|BOTH|NONE）。
- 目录季号沿树继承（`Season 2/2160p/xxx.mkv` 这种中间夹一层 4K 目录也能拿到 Season 2）；更深一层能解析出季号则覆盖。
- `SeasonPlan.groups(prefer)` 供引擎直接建季目录；`summary(prefer)` 生成一行可追溯日志；
  `conflictExamples()` 给弹窗展示证据。

**引擎**（`TreeSyncEngine`）
1. `runFlatMulti(tree, destRootFid, prefer)` 改为走 `SeasonResolver`，不再自己边走边猜；
   `syncFlat(..., seasonPrefer = SeasonPrefer.FILE)` 把偏好透传下去。
2. `Report` 新增 `notes`（「不是错、但要让人看到」的结论，如季判定一行、冲突已按文件名）；
   `failures` 与 `notes` 分开，避免把说明当成失败把任务标红。
3. 多季的深度超限提示改由 `SeasonPlan.depthSkipped` 汇总，口径不变。

**任务执行**（`TaskRunner` / `TaskRepository`）
- 每源的 `notes` 合并成整轮 notes（多源加「源K」前缀），`finishRun` 写运行说明时把 notes 一起写进 `msg`；
  卡片失败文案不受影响。
- 新增 `precheckSeasons(task, draftLinks)`：多季任务只读扫一遍树，有冲突返回 `SeasonConflictInfo`，
  任何异常都返回 null（预检是增强，不能挡住正常转存）。

**UI**
- 新建/编辑页「运行一次」：多季任务先预检，冲突则弹确认框
  「季号对不上，需要你确认 —— 共 N 个视频，其中 M 个文件的季号和所在目录不一致」+ 最多 3 条证据；
  按钮「按文件名」/「取消」。选按文件名 → 用同一批参数继续跑（跳过预检）。
- 卡片上的「运行」没有弹窗时机，直接按文件名处理，并把「N 个季号冲突（已按文件名）」写进日志说明。

**测试**：新增 `SeasonResolverTest` 7 例——文件名有目录无、目录有文件名无、两边一致、
两边打架（默认文件名的分组 / 切目录名的分组 / summary 文案）、中文季目录 + 特别版（`第一季/第二季/特别篇/Specials`）、
完全无线索落根、深层季目录中间夹 4K 目录仍继承。单测 127 → 134，全绿。

**验证**：`testDebugUnitTest` 134/134 通过，`assembleDebug` 通过（APK 12.5MB，09:07）。手机离家，本轮未装机。

## Stage 45（2026-11-24）季度收敛成 3 个按钮 + 季度/命名规则加一行结构说明

**需求**：季度从 5 个按钮改为 3 个（平铺 / 输入季数 / 多季），点击后在按钮下方用一行小黑字说明保存后的目录结构；
命名规则同样加一条结构说明。

**文案（用户确认版）**
| 季度选项 | 说明 |
|---|---|
| 平铺 | `剧名/视频文件` |
| 输入季数 | `剧名/Season N/视频文件`（填完显示实际，如 `剧名/Season 3/视频文件`；填 0 即特别版） |
| 多季 | `剧名/Season 1/…、Season 2/…` |

| 命名规则 | 说明 |
|---|---|
| 默认 | `原名原目录` |
| 通用命名 | `剧名/……/剧名.S01E01.扩展名` |
| tmdb id | `剧名 tmdb-12345/……` |

**改动**
1. `NewTaskScreen` 季度行：删掉 `Season 1` / `Season 2` / 动态「第N季」按钮，改为固定三项；
   「输入季数」的选中条件是 `seasonNo >= SEASON_SPECIALS`（0..99），点开时把已填的季数带进输入框。
2. 新增 `FieldHint(text)` 组件：`bodySmall` + `onSurfaceVariant`、左对齐、`padding(top = 6.dp)`、最多 2 行省略，
   季度与命名规则共用（说明随选择即时切换，不淡出、不弹泡）。
3. 命名规则行下加说明；`tmdb id` 的说明只在真正开启时显示（未开启沿用原来的「先选通用命名 / 先绑定剧集」两句原因提示，两者不冲突）。

**验证**：`testDebugUnitTest` 134/134 通过，`assembleDebug` 通过，已装机（lastUpdateTime 10:11:25）。

## Stage 46（2026-11-24）命名规则下的提示全部去掉，节省一屏空间

**需求**：命名规则那行下面不要任何说明文字（含原来那两句「选「通用命名」后才能开启 tmdb id」「先在名称框右侧搜索绑定剧集…」）。

**改动**：`NewTaskScreen` 删掉命名规则下方的 `FieldHint`（`原名原目录` / `剧名/……/剧名.S01E01.扩展名` / `剧名 tmdb-12345/……`）
和 `!canUseTmdbFolder` 时那两行原因提示，命名规则区只剩三个按钮一行。
`tmdb id` 不可用时仍按原逻辑置灰（`enabled = canUseTmdbFolder`），点不动但不解释——用户口径是省空间优先。
`FieldHint` 组件保留，季度那行继续用它。

**验证**：单测 134/134 通过，`assembleDebug` 通过，已装机（lastUpdateTime 10:25:43）。

## Stage 47（2026-11-24）命名规则的结构说明改回来

**需求修正**：Stage 46 把命名规则下的说明全删了，用户要求把**结构说明**留着，只去掉那两句原因提示。

**现状（本轮）**：命名规则三个按钮下方恢复一行 `FieldHint`
- 默认 → `原名原目录`
- 通用命名 → `剧名/……/剧名.S01E01.扩展名`
- tmdb id（已开启）→ `剧名 tmdb-12345/……`

原先的「选「通用命名」后才能开启 tmdb id」「先在名称框右侧搜索绑定剧集…」两句继续保持删除（置灰按钮即提示）。

**验证**：单测 134/134 通过，`assembleDebug` 通过，已装机（lastUpdateTime 10:40:36）。

## Stage 48（2026-11-24）TMDB 搜索弹窗标题改为「匹配 TMDB 剧集」

**需求**：弹窗标题原来叫「搜索剧集」，容易和 App 自己的剧集/任务混淆，改为「匹配 TMDB 剧集」，明确这一步是在绑 TMDB 条目。

**改动**：`NewTaskScreen` 里 `TmdbSearchDialog` 的 `title` 文案改为「匹配 TMDB 剧集」；搜索框 placeholder 仍是「剧名」，按钮仍是「关闭」。
名称框右侧的搜索图标 `contentDescription` 未改（无障碍描述里保留「搜索剧集」语义）。

**验证**：`assembleDebug` 通过，已装机（lastUpdateTime 11:17:32）。

## Stage 49（2026-11-24）应用自更新（不经过应用商店）

**需求**：照 `D:\work buddy\fnphoto\APP 更新说明方案.md` 移植自更新 —— 把 APK 挂到 Gitee / GitHub Release，APP 启动后读 Release 列表，发现更高版本就弹窗，用户同意后下载 APK 交给系统安装器覆盖安装。无服务器、无应用商店、无 token。

**版本口径（必须与 build.gradle 一字不差）**
- `appVersionName` 改为 **0.8.0**，`appVersionCode` 由公式算出：`major*10000 + minor*100 + patch` → 800
- 客户端 `UpdateRules.parseVersionCode` 用同一公式；单测钉死一致性，防「提示降级 / 永远提示有新版」
- 装机核对：`versionCode=800`、`versionName=0.8.0-debug`

**新增 `update/` 包（7 个文件）**
- `UpdateInfo.kt`：`UpdateInfo` 模型 + `UpdateRules.parseVersionCode` / `pickLatest`（纯函数，可单测）
- `UpdatePrefs.kt`：独立 `update_prefs.xml`，只存 `lastCheckMs` / `dismissedCode`，24 小时节流常量（`START_DELAY_MS=5000`、`INTERVAL_MS=24h`）
- `UpdateSource.kt`：`UpdateSource` 接口 + `DefaultUpdateSource`（Gitee + GitHub 并发请求，独立干净 OkHttpClient 8s/20s/20s；跳过 `prerelease` / `draft`；只认 `.apk` 附件；`browser_download_url` 取直链；同版本优先 Gitee）
- `UpdateManager.kt`：状态机（延迟 5 秒检测 → 24h 节流 → 等主页面 → 下载 → FileProvider 交系统安装器）；`currentVersionCode` 从 PackageManager 读；下载两道校验（Content-Length 对得上、≥1KB），失败删半截文件；`canRequestPackageInstalls` 判断 + 跳「允许安装未知应用」设置页
- `UpdateDialog.kt`：三态弹窗（确认「发现新版本 vX.Y.Z / 当前版本」+ 取消/更新；下载中「正在下载… N%」+ 进度条、无按钮；失败「下载失败，检查网络后再试」+ 取消/重试），`RoundedCornerShape(24.dp)` 与现有弹窗一致，默认动作给「取消」
- `UpdateRulesTest.kt`（新增单测 7 例：公式一致、两位版本补零、剥离 v 前缀/后缀、脏数据拒绝、无更高版本不弹、跨端取最高、同版本 Gitee 优先）

**改动**
- `AndroidManifest.xml`：加 `REQUEST_INSTALL_PACKAGES`；加 `FileProvider`（authority `${applicationId}.fileprovider`，导出 false，grantUriPermissions true）
- `res/xml/file_paths.xml`（新）：`<files-path name="update" path="update/" />`
- `AppScreens.kt` 的 `AppRoot`：`UpdateManager` 随组合创建 + `autoCheck()`；弹窗只在 `screen == Screen.TASKS` 时出现（不在看图/设置时打断）；点「更新」时若未授权「安装未知应用」先跳系统设置

**撤销（上午误按「后台定期自动更新」方案写了一半的代码）**
用户明确本次要做的是「应用自更新」，不是后台自动同步，故整份撤回：删除 `sync/` 下 `AutoSyncPrefs` / `AutoSyncScheduler` / `AutoSyncPolicy` / `AutoSyncNotify` / `AutoSyncWorker` 五个文件，回退 `build.gradle.kts` 的 `work-runtime-ktx` 依赖、`MainActivity` 的 ensure/补跑代码、`TaskRepository.lastSyncAt()`。方案文档 `docs/auto-sync-plan.md` 保留，等真要后台同步时再按它继续。

**发布侧约定（发版时必须照做）**
1. Release tag 用 `vX.Y.Z`（三段数字），`name` 与 tag 同名
2. `prerelease` / `draft` 必须是 false
3. 必须上传 APK 作为附件（建议 `zhuancun-miao-vX.Y.Z.apk`）
4. 发出的包必须与已装包**同包名、同签名**（本项目 release 复用 debug 签名，debug/release 都落在 `com.ashchan.cloudsaver`，可互相覆盖）
5. Gitee 建 Release 时参数只能走 URL query（JSON body 会 401）

**未做**
- Gitee 仓库坐标暂留空（`DefaultUpdateSource.GITEE_REPO = ""`，为空自动跳过），只走 GitHub
- 发布正文（Release body / 更新说明）刻意不在弹窗里展示

**验证**：单测 141/141 通过（新增 7 例），`assembleDebug` 通过，已装机（`versionCode=800`，lastUpdateTime 11:33:35）。端到端「检测 → 弹窗 → 下载 → 安装」还需一个高版本 Release 才能验（仓库现有最高 v0.6.0=600，低于 800，不会误弹）。

## Stage 49 发布记录（2026-11-24）v0.8.1

- `appVersionName` 抬到 **0.8.1**（`versionCode=801`），`assembleRelease` 出正式包 2,182,278 字节（`app-release.apk`，R8 后约 2.1MB）
- GitHub Release 已建：`v0.8.1`，`prerelease=false`、`draft=false`，附件 `zhuancun-miao-v0.8.1.apk`（2,182,278 字节，state=uploaded）
  地址：https://github.com/ASH-C776/zhuancun-miao/releases/tag/v0.8.1
- 签名核对：release 包证书 `CN=Android Debug`（与已装 debug 包同一 keystore），可覆盖安装、数据不丢
- **真机全链路验证通过**：先装 debug 800 → 清 `update_prefs.xml` → 重启 App → 5 秒后弹「发现新版本」→ 点更新跳「允许安装未知应用」授权页 → 下载 → 系统安装器「安装成功」→ 现装机为 `versionCode=801 / versionName=0.8.1 / lastUpdateTime=11:45:52`（已从 debug 包平滑升到 release 包，数据未丢）
- 截图留档：`docs/upd1.png`（授权页）、`docs/upd2.png`（安装成功）

## Stage 50（2026-11-24）双端发布：Gitee 同名镜像 + 一键发版脚本

**需求**：在 Gitee 建一个与 GitHub 同名的公开仓库，以后每次发版两边都要同步发。

**做了什么**
- Gitee 新建公开仓库 `kijeye/zhuancun-miao`（默认分支 `dev`，已与 GitHub 同名同结构）；空仓库不允许直接设为公开，先推一次 `dev` 再改公开
- 本地加 `gitee` 远端并把 `dev` 推上去；Gitee 上同步建 `v0.8.1` Release（附件应是同一份 2,182,278 字节 APK）
- `DefaultUpdateSource.GITEE_REPO` 从空串改成 **`kijeye/zhuancun-miao`** —— 客户端现在双端并发检测，同版本优先用 Gitee（国内下载快）
- 新增 `tools/publish-release.ps1`：读 `appVersionName` → `assembleRelease` → 同一份 APK 同时建 GitHub + Gitee Release 并上传附件。令牌优先取环境变量 `GITHUB_TOKEN` / `GITEE_TOKEN`，其次读 `~/.git-credentials`（同站点有多条时按用户名 `ASH-C776` / `kijeye` 挑，避免拿到手机号那条密码）
- 版本抬到 **0.8.2**（versionCode 802），用脚本双端发布，GitHub / Gitee 都已有 `v0.8.2` + `zhuancun-miao-v0.8.2.apk`

**验证**
- Gitee API 免 token 可读（客户端无需任何凭据）：`releases` 返回 v0.8.1 / v0.8.2，`prerelease=false`、`draft=false`；附件里除 .apk 还有 Gitee 自动生成的 .zip/.tar.gz，客户端按扩展名只认 .apk
- 直接下 Gitee 的 `browser_download_url`，字节数 2,182,278 与本地产物一致
- `aapt2 dump badging`：`versionCode='802' versionName='0.8.2'`
- 已装机 0.8.2（`versionCode=802`、lastUpdateTime 11:54:46）；顺手确认 0.8.1 正式包跑起来任务列表、状态徽标正常
- 单测仍 141/141

**注意**：正式包已不可 `run-as`（非 debuggable），以后清 `update_prefs.xml` 验节流只能靠「卸载重装」或等 24 小时；调试图标入口同理。

## Stage 51（2026-11-24）卡片底行去掉 TMDB 状态字段

**需求**：任务卡底行原来在「周几 · 网盘」后面还挂一段状态（`自动` / `待推断` / `无 TMDB`）。用户判定这段没用且与「后台自动更新」撞词，要求整段去掉。

**做了什么**
- `TaskListScreen.kt` 的 `metaLeft()`：删掉 `tmdbId == null → 无 TMDB`、`updateMode == TMDB → 待推断 / 自动` 两段分支，底行只留 `Weekdays.label(weekdays)` + `driveLabel(driveType)`，多源再追加 `· N 源`
- 同步删掉 `metaLeft()` 的文档注释里「自动 / 待推断 / 未绑定 TMDB」描述，以及卡片 Row 里「如『周二 · 光鸭 · 自动』」的示例注释
- **数据结构不动**：`TaskEntity.updateMode`、`tmdbId`、`TaskRunner` 里按 TMDB 推断更新日并写回的逻辑全部保留，只去掉展示层，将来做后台自动更新时还要用
- 更新日来源仍可在「编辑任务」页看到，卡片上不再承载这个信息

**验证**
- 单测 141/141 通过（无新增/删除用例）
- `assembleRelease` 通过，产物 2,182,278 字节（与 0.8.2 一致，仅展示层改动，不影响体积）
- 本次未升版本号（仍是 0.8.2 / 802），未装机：无线 ADB 离线（`192.168.31.144` / `192.168.31.158` 均超时，mdns 无服务），需手机端开无线调试后补装核对界面

## Stage 52（2026-11-24）后台自动刷新：P1-P5 落地

**口径（用户拍板）**：定时点人工设定、到点必跑、不受冷却约束；插电触发受固定 3 小时冷却；不做 Wi-Fi 约束；不做「立即运行一次」。

**新增 `sync/` 包（6 文件，独立成块，可整体摘除）**
- `AutoSyncConfig`：时段校验 / 规范化（最多 4 条、去重排序）、下一次触发时刻（跨零点、正好到点取明天）、3 小时冷却判定。纯 Kotlin，零 Android 依赖
- `AutoSyncPrefs`：独立 `auto_sync_prefs`（开关 / 时段 / 充电 / 通知 / 上次运行 / 24h 计数 / 跳过原因），**不碰 Room**，DB 仍 v11
- `AutoSyncScheduler`：唯一入口 `requestRun` / `scheduleSlot` / `rescheduleAll` / `cancelAll`；`ExistingWorkPolicy.KEEP` 保证同一时刻只有一轮
- `AutoSyncWorker` + `AutoSyncEngine`：自建 repo/runner，调既有 `TaskRunner.runAll(tasks, TRIGGER_BACKGROUND)`；汇总新增集数与失败
- `AutoSyncNotifier`：独立渠道 `auto_sync`，只在「有新增 / 有失败」时发，空转静默
- `ChargingReceiver`：`ACTION_POWER_CONNECTED` → 统一入口（清单静态注册，白名单广播）

**调度算法**
- 每个 `HH:mm` 一条唯一命名的 OneTimeWork，`initialDelay` 到下一个该时刻；Worker 跑完自续下一次（不用 PeriodicWork，避免 15 分钟粒度漂移）
- 定时点不受冷却；插电受 `lastRunStartAt + 3h` 约束（入口判一次、Worker 再判一次，覆盖 Doze 延迟）

**UI（`ui/AutoSyncSection.kt`，设置页「外观」段后插入一行调用）**
- 总开关 → 每天刷新（时段 chip 带 × 删除 + 「+ 添加」对话框）→ 充电时刷新 → 跑完发通知 → 状态卡
- 状态卡：上次（几点 / 新增几集）、最近 24h 次数、下次时刻、冷却提示（「距上次 1 小时 20 分，插电将跳过」）、超 3 天未运行的橙字告警
- 首次开启自动刷新时申请 `POST_NOTIFICATIONS`（Android 13+）

**隔离落实**
- `TaskRunner` / `TreeSyncEngine` / `TasksViewModel` / `TaskListScreen` / `RunLogScreen` 一行未改；日志页靠既有 `triggerLabel` 显示「自动同步」
- 数据库零迁移；配置全部在 `auto_sync_prefs`
- 一律不用 `AlarmManager` 精确闹钟

**依赖与打包**
- `androidx.work:work-runtime-ktx:2.9.1`（本地缓存原先没有，从镜像拉到）
- R8 加 `-keep class com.ashchan.cloudsaver.sync.AutoSyncWorker`（WorkManager 反射实例化）
- release 包 2,282,928 字节（+100KB，主要为 WorkManager）

**验证**
- 单测 **160/160**（新增 `AutoSyncConfigTest` 19 例：格式、规范化、跨零点/月末、正好到点、多时段取最近、冷却边界 2h59m / 3h00m / 负值）
- `assembleDebug` / `assembleRelease` 均通过
- 已装机 `versionCode=802 / versionName=0.8.2 / lastUpdateTime=22:06:50`；设置页 UI 尚未截图核对（截图时手机前台是微信，未抢前台）

**待办（P6/P7）**
- 真机开开关后核对：WorkManager job 存在且时段正确、划掉 App 后按时跑、插电 CD、通知、一晚耗电
- 电池优化白名单引导（当前仅状态卡橙字，未做跳转按钮）

## Stage 53（2026-11-24）运行日志：状态可视化 + 来源筛选

**问题**：日志卡只有「成功 / 失败」二元 + 一行灰字，新增 3 集与空跑一轮长得一模一样。

**状态盘点（6 大类 / 10 种，全部由既有字段推导，不加表、不加字段）**
- 新增（`restoredFiles>0`）→ 绿
- 整理（只改名或建目录）→ 蓝
- 无变化（全零空跑）→ 灰、卡片背景更淡、整卡退后
- 部分失败（ok 但 message 带失败原因，多源常见）→ 琥珀
- 提示（季判定等 notes）→ 灰
- 失败再细分：登录失效 / 被限流 / 网络异常 / 分享无内容 / 其它 → 红

**UI**
- 左上角状态胶囊直接写结论（「新增 3 集」「无变化」「登录失效」），替换原来的「成功/失败」
- 卡片左侧 4dp 竖色条，与胶囊同色，一屏能扫出红条
- 主行（新增/改名数量）用状态强调色加粗；耗时与跳过缩到最小号灰字
- 失败原因/提示放独立浅色块，不再与正文同色
- 标题下加 `全部 / 手动 / 自动` 筛选 chip（纯内存过滤，「自动」仅在开了自动刷新时出现；全部检测归入「手动」）
- 筛选后为空时提示「这个分类下没有记录，切到全部看看」

**实现**
- 新增 `ui/tasks/RunLogStatus.kt`：纯分类逻辑（enum + 关键词细分），可单测
- `RunLogScreen.kt` 重写 `RunRow`；`summary()` 拆成 `mainText()` / `metaText()`
- 颜色本地定义，按背景亮度切深/浅两套强调色（绿 `2E7D32` / 蓝 `2C6E8F` / 琥珀 `9A6400`；深色用亮版）

**验证**
- 单测 **173/173**（新增 `RunLogStatusTest` 13 例：各类计数组合、部分失败、关键词细分、失败优先）
- `assembleDebug` / `assembleRelease` 通过；release 2,299,312 字节
- 已装机（22:42:30），但截图时手机通知栏处于展开状态（`mCurrentFocus=NotificationShade`），日志页实拍待补

**注意（构建踩坑）**
- 第一次 `assembleRelease` 后 APK 时间未更新（`packageRelease` 未真正重打包），装上去仍是旧界面；重新跑一次 `:app:assembleRelease` 才产出 2,299,312 的新包。以后核对装机必须同比 APK 的 LastWriteTime 与 `lastUpdateTime`

## Stage 54（2026-11-24）命中即转存 + 源优先级 + 分季统计（0.8.3）

**问题**：卡片数字对不上（凡人修仙传「已收 124 集却缺 87」、仙逆「E265 却缺 53」），盘里出现没改名的文件，同一集还存了两份（重置版 / V2 并排）。根因是统计把各季集号混在一个集合里，转存端「解析不出集号也照转」。

**新增纯逻辑（4 个文件，全部可单测）**
- `data/EpisodeKey.kt`：单个文件的「季+集」可信度判定。强证据 `S01E05` / `第5集` / `EP05`，中等证据首尾独立数字，弱证据只认全名唯一数字；抹掉分辨率/码率/帧率/编码/年份/季标记等噪声；剧场版整体标记为电影；多个候选数字判歧义。**判不出、歧义、剧场版一律不算命中。**
- `transfer/TransferClassifier.kt`：把树切成 `MAIN / MOVIE / UNMATCHED`；同集多版本按「重置/修正 > V2/V3 > 高分辨率 > 名字靠前」只留一个；再按命中集合裁剪目录树（空目录摘掉），交给引擎——引擎一行未改。
- `transfer/SourceQuality.kt`：源质量打分 = 可解析率 40 + 命名规范度 20 + 集号连续度 15 − 同集多版本（每个 5，上限 15）− 歧义（每个 5，上限 20）。
- `data/EpisodeStats.kt`：分季统计。指定季只数该季；多季只数 TMDB 对应那一季；平铺按第一季（与 `剧名.S01E01.后缀` 命名一致）；Season 0 不混进正片。

**TaskRunner 改动**
1. 先预扫描所有源（解析链接 → 拿令牌 → 拉目录树 → 打分），**多源按分数降序执行**，同分保持原链接顺序；日志写「命名质量优先：源2 91分 > 源1 74分」。
2. 命中裁剪后才转存，且是**两道**：先按「集号能不能定」裁树（剧场版/未命中/歧义不转、同集多版本只留一个），再按「命名规则是否真正匹配、占位符是否都能解析」裁一次 —— 转存数 = 改名数，规则没匹配上的文件不落盘（日志写「规则未匹配 N 个未转存」）。未选命名规则的任务沿用老行为（全转）。
3. 多源时每跑完一个源就重算缺口，已补齐（TMDB 绑定且缺 0）就不再跑后面的源，日志写「源1 之后缺口已补齐，后面的源不再执行」。
4. `countLocal` 改为收集「目录链 + 文件名」，按 `EpisodeStats` 分区统计；`TmdbLatest` 增加 `countSeason`。
5. 每个源都写一行命中结论：`命中 12 集（转存 12 个文件）；同集多版本 2 个只留 1 个；未命中 3 个未转存；剧场版 1 个跳过`。

**验证**
- 单测 **196/196**（新增 23 例：`EpisodeKeyTest` 9、`TransferClassifierTest` 6、`SourceQualityTest` 3、`EpisodeStatsTest` 5）
- `assembleRelease` 通过，装机会核对 versionCode=803

**已知未做**
- 改名失败后的「转存回滚」：两个适配器都没有删除接口，本轮不做；改名重试仍是 4 次。
- 多季卡片显示仍是单一缺口数字（`S1 12/12 · S2 5/13` 这种分区展示留到下一轮）。

## Stage 55（2026-11-25）剧场版误杀修复 + 统计诊断打点（0.8.4）

**现象**：分享「F 凡人修.XZ 4K 附剧场版」转存率异常；凡人修仙传卡片仍显示「已收 124 集、缺 87 集」，但盘里实际 201 个文件。

**根因（第一条已确认）**
- 剧场版判定把**所有祖先目录**都扫了一遍，只要目录链里出现过「剧场版」三个字，整棵树的每个文件都被判成 MOVIE。这个分享的顶层文件夹名正好是「F 凡人修.XZ 4K 附剧场版」→ 整批 211 个文件全部被判电影，命中 0。
- 修法：只看文件名 + **紧邻父目录**，且父目录必须是「纯剧场版语义」（`剧场版`、`剧场版 1080p`、`Movie`）；`凡人修仙传 剧场版合集` 这种带剧名的容器目录不再算电影目录。

**统计诊断**
- `EpisodeStats.Result` 增加诊断字段：扫到几个视频、入桶几个、同集重复几个、其它季几个、解析失败几个（含歧义数）、剧场版几个、季分布、失败样例最多 8 个。
- 每次统计写一条 logcat：`EpisodeStats ... → 集数 / 末集 / 扫到 / 入桶 / 重复 / 其它季 / 解析失败 / 剧场版 / 季分布 / 样例失败`。下次数字再不对，一条日志就能分清是漏扫、重复、还是解析失败。

**验证**
- 单测 **197/197**（新增 1 例：剧名里带「剧场版」的容器目录不误杀正片）
- `assembleRelease` 通过，装机 versionCode=804

## Stage 56（2026-11-25）两套口径分离：追剧进度 vs 完整度缺口（0.8.6）

**口径（用户拍板）**
- 卡片 + 进度条 = **追剧**：盘里最大集号 vs TMDB 最新已播集，只看“追没追上最新一集”，中间断档不影响；徽标「已最新 / 待更新 / 全剧收齐」。
- **缺多少集** = TMDB 总集数 − 盘里已有集数，是独立字段，**只写进运行日志**，不在卡片上显示。

**改动**
- `data/EpisodeSegments.kt`：入参由「盘里集数」改为「盘里最大集号」，绿条长度 = 最大集号 / 总集数；补注释说明断档不改变绿条。
- `data/SnapshotCalc.kt`：新增 `Gap`、`gap(localEpisodes, tmdbTotal, fromEpisode = 1)`、`gapText(...)`；`missing()` 改为调用 `gap()`，口径固定为总数 − 已收。`fromEpisode` 预留给下一步「从第 N 集开始转存」。
- `transfer/TaskRunner.kt`：统计完成后把 `盘里 114 集 / 共 211 集，缺 97 集` 追加进运行日志 notes。
- `ui/tasks/TaskListScreen.kt`：卡片第二行恢复 `E201 → E201（共211）`；徽标改为按追剧判定（已最新 / 待更新 / 全剧收齐），不再显示「缺 N 集」；`EpisodeBar` 改用 `localLastEpisode`。

**验证**
- 单测 **201/201**（`SnapshotCalcTest` 新增缺口 3 例；`EpisodeSegmentsTest` 入参改名 + 新增「中间缺集不影响绿条」1 例）
- `assembleRelease` 通过，装机 versionCode=806

**真机预期（凡人修仙传）**
- 卡片：`E201 → E201（共211）` + 徽标「已最新」；进度条绿 201/211、黄 0、粉 10。
- 运行日志：`盘里 114 集 / 共 211 集，缺 97 集`。


## Stage 57（2026-11-25）命中率修复：4K / v2 不再被当成集号（0.8.7）

**现象**：源里每集都有，命中率却偏低，日志「未命中 N 个」偏多；用户截图里的文件是 `第58话 再别天南12.mp4` 这类命名。

**根因（用光鸭 API 枚举整个分享 + 单测复现确认）**
- `第N话` 命名本身能命中（实测 `第58话 再别天南12.mp4` → 58，同名文件此前也确实改成了 `S01E132`）。
- 真正没命中的是另一批文件名：`171 4K.mp4`、`172 4K(v2).mp4`、`177(v2) 4k.mp4`、`凡人修仙传.219-4K.mp4`。
  `EpisodeKey.stripTechnical()` 没有抹掉 `4K` 和 `v2`，于是 `4` / `2` 被当成候选集号 →
  - `171 4K.mp4`：候选 [171, 4]，尾号 4 ≠ 头号 171 → 判歧义 → 未命中；
  - `凡人修仙传.219-4K.mp4`：候选 [219, 4]，尾号 4 被认成集号 → 错成第 4 集。

**改动**
- `data/EpisodeKey.kt`：TECH 噪声表新增 `\b[248]k\b`（4K/2K/8K）与 `\b(v|ver|version)\s*\.?\s*\d{1,2}\b`（v2/ver2）。抹掉后这些文件名只剩一个集号，按「开头独立数字」中等证据命中。

**验证**
- 单测 **204/204**（新增 3 例：`171 4K`→171、`172 4K(v2)`→172 且不歧义、`凡人修仙传.219-4K`→219；`第58话...`→58 仍命中）
- `assembleRelease` 通过，装机 versionCode=807

## Stage 58（2026-11-25）根因修复：分享目录只读第一页 50 个文件（0.8.8）

**现象**：同一个源目录（如「001-100集」100 个文件、「101-180集」87 个文件）只有前一段被转存，后面整段消失。
凡人修仙传实测：E001–050 有、E051–100 没有；E131–133 有、E134–170 没有、E171+ 又有。

**根因（调光鸭接口逐页验证）**
- `get_share_page_files_list` **不认 `page`**：`page=1/2/3 + pageSize=50` 三次返回完全相同的前 50 条。
- 但**认 `pageSize`**：同一目录 `pageSize=100` / `200` 一次就返回 100/100、87/87 全量。
- `GuangYaAdapter.fetchShareChildren()` 原来固定 `pageSize=50` 并用 `page` 自增翻页 → 第二页 fid 全重复 → `added==0` 直接 break → 每个源目录永远只拿到前 50 个文件。这条与命名解析无关，纯读取截断。

**改动**
- `drive/guangya/GuangYaAdapter.kt`：`fetchShareChildren()` 改为「一页开大 + 按 total 补一次」：先 `pageSize=200`，若 `total > 已拿条数` 再用 `pageSize=total`（上限 1000）重取一次；仍拿不全则打 `GuangYaDebug` 告警 `分享目录被截断：total=… 只拿到 …`，不再静默丢文件。新增常量 `SHARE_PAGE_SIZE=200`、`MAX_SHARE_PAGE_SIZE=1000`。

**验证**
- 接口层实测：`001-100集`（total=100）`pageSize=200` 返回 100 条（第1话…第100话）；`101-180集`（total=87）返回 87 条（含第134–170话）。
- 单测 204/204 通过；`assembleRelease` 通过，装机 versionCode=808。

**预期**：同一任务再跑一次，E051–100、E134–170 都能被看到并转存，缺口应显著缩小。

## Stage 59（2026-11-25）前台服务保活：熄屏 / 切后台不再掐断任务（0.8.9）

**现象**：手动任务跑在 `AppViewModel.viewModelScope` 上，熄屏或切后台后 App 变 cached 进程，CPU/网络被冻结，长任务中断。

**改动**
- 新增 `sync/TaskRunService.kt`：`foregroundServiceType=dataSync` 的前台服务承载任务，自带 `SupervisorJob + Dispatchers.IO`，与界面解耦。
  - 常驻通知「转存喵 · 任务进行中」+ 阶段文案 + 「取消」按钮；完成后另发完成/失败通知。
  - `PARTIAL_WAKE_LOCK`（60 分钟超时兜底，finally 释放）+ 仅 Wi-Fi 下 `WIFI_MODE_FULL_HIGH_PERF` 锁；结束/失败/取消都在 `finally` 释放，空闲零常驻。
  - 单飞：已有任务在跑时忽略新的启动请求。
- 新增 `TaskRunBus`（阶段表 + 完成事件），界面侧只订阅，不驱动。
- `ui/tasks/TasksViewModel.kt`：`runTask` / `runAll` 改为 `TaskRunService.startTask / startAll`；`running` 改由 `TaskRunBus.phases` 订阅，`finished` 由事件流驱动；一键全部结束自动收掉整体进度文案。构造新增 `appContext`（`MainActivity` 已同步）。
- `AndroidManifest.xml`：注册 `<service … stopWithTask="false">`，新增 `ACCESS_WIFI_STATE` 权限。

**修复（同日秒闪）**
- 现象：0.8.9 首次装机后「打开 App 秒闪」。debug 堆栈指向 `TasksViewModel.<init>` 里的 `init` 块 `emit`。
- 根因：Kotlin 按声明顺序初始化属性，`init {}` 写在 `_running/_finished/_progress` 声明之前 → 订阅回调触发时这三个 `MutableStateFlow` 还是 null，`_running.value = …` 直接 NPE。
- 修法：把 `init` 块整体挪到三个 StateFlow 声明之后，并加注释说明顺序约束；逻辑一行未改。

**验证**
- debug / release 双版本均启动不闪退（`pidof` 存活、`mCurrentFocus` 为转存喵）。
- `assembleRelease` 通过，装机 versionCode=809。
- 真机待测：点运行 → 立即熄屏 5 分钟 → 亮屏看日志是否连续；再测切后台、划掉最近任务、通知栏取消。

---

## v0.9.0 · 长按菜单 + 统一重命名 + 置顶（2026-11-25）

### Stage 60 播出时间字段整体移除

- 新建 / 编辑页删除「播出时刻」整块 UI 与时间选择弹窗（`NewTaskScreen`），表单快照 `DraftForm` 与 `createTask/saveEdit/runDraft` 不再带 `airTime` 参数。
- `TaskEntity.airTime` 列保留但不再读写（执行链路本来就没用它），避免额外一次删列迁移。
- 顺手清掉 `DateRange` / `TimePicker` / `rememberTimePickerState` / `Locale` 无用 import。

### Stage 61 长按菜单 + 置顶

- 卡片长按不再直接弹删除确认，改为贴卡片浮出的纵向纯文字菜单：**置顶（已置顶显示「取消置顶」）→ 统一重命名 → 分隔线 → 删除任务（橘红）**；单击卡片仍是进入编辑页，菜单不再放「编辑任务」。
- `tasks` 表新增 `pinned` 列，Room 版本 11 → 12（`MIGRATION_11_12`，默认 0），老任务顺序不变。
- 列表排序改为「置顶优先、其余 id 升序」，只影响显示顺序，不参与执行顺序与调度。

### Stage 62 统一重命名（只改文件名，不动目录）

- 新增 `transfer/RenamePass.kt`：扫描任务已转存目录（指定季只扫该季、平铺扫剧名目录、多季扫各季子目录），按任务当前命名规则重跑一遍文件名。
- 硬边界：**不移动、不新建目录、不覆盖**；目标名已存在或同目录两文件撞同一新名 → 记冲突跳过；解析不出集号 / 规则不匹配 → 跳过并计数。
- 季号以文件所在目录为准：`{SXX}` 在计算前就替换成目录季号，避免文件名里的季号与目录冲突时改错位置（只改名字，位置不动）。
- 改名并发 2、重试 4 次、光鸭限流（「频繁」/ code 127）退避 2.5s×次数，与转存链路同一套口径。
- 接入 `TaskRunService` 的 `RUN_RENAME` action：同样前台服务 + 唤醒锁跑，切后台/熄屏不中断；完成后发通知。
- 卡片原位文案：`扫描目录` → `识别集号 N` → `重命名 37/201` → `收尾`；跑完闪一句「已改名 87 项」/「无需改名」。
- 运行日志新增 `trigger = rename`（标签「统一重命名」），结论形如：`检查 201 个 · 改名 87 · 已正确 112 · 冲突 1 · 失败 1`；不改任务快照集数。

**验证**
- `:app:testDebugUnitTest` 通过，`:app:assembleRelease` 成功，装机 versionCode=900，启动 `pidof` 存活。
- 待真机验收：长按菜单三项、置顶排序、统一重命名（含冲突/无法识别）、日志与卡片文案、播出时间已消失。

---

## v0.9.1 · 转存 diff 按集号兜底（2026-11-25）

- 现象：把盘里某一集手动改成 `001.mp4` 后跑「统一重命名」，001 还在、同时又多出一份规范命名的同一集。
- 根因：多出来的那份不是重命名产生的。`RenamePass` 只列本地目录 + 调 `renameFile`，没有任何转存调用；真正补集的是 **检测转存** 的目录 diff —— 它只按文件名比对，`001.mp4` 既不是分享原名也不是规范名，于是被判「缺这一集」重新从分享拉了一份。
- 修法：`TreeSyncEngine.syncFiles` 增加「集号兜底」：目标目录里没有规范名时，按文件名解析集号再看盘里是否已有同一集。
  - 唯一一份（不管叫什么）→ 直接就地改成规范名，不重复转存；
  - 多份 → 不重复转存，并在日志写「第 N 集盘里已有 K 个文件，未重复转存（可先跑统一重命名清理）」。
- 同时修正日志缺口文案：`盘里 X 集 · 已播 Y 集 · 共 Z 集` + `已播的还有 M 集未收到` + `还有 W 集待播`，补充说明逐条换行。
- 版本 0.9.1（versionCode 901），已装机。

**待确认**：已产生的重复文件（001 与规范名各一份）不会被自动删除——删除是不可逆动作，需要你手动删或后续单独做「重复集清理」功能。
