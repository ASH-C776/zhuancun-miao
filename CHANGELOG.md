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
