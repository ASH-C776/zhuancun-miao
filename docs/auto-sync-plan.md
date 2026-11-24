# 转存喵 · 后台定期自动更新方案（讨论稿 v1）

> 目标：App 不在前台时，按设定的周期自动跑「更新」（转存 + 改名），跑完能知道结果。
> 前提不变：单机、无服务器、无 Docker、只做夸克 + 光鸭、单账号。

## 一、现状盘点（先对齐事实）

| 项 | 现状 |
|---|---|
| 自动同步代码 | **完全没有**。Stage 12 只做了「关闭启动自动同步」，没有任何后台调度 |
| 记录触发方式 | `RunRecordEntity` 已有常量 `TRIGGER_MANUAL("manual")` / `TRIGGER_ALL("all")` / `TRIGGER_BACKGROUND("background")`，后者**至今未被使用**，正好复用 |
| 权限 | `INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS` **都已在清单里声明**，不用再加 |
| 依赖 | 没有 WorkManager；有 coroutines、Room、Retrofit、security-crypto |
| 执行入口 | `TaskRunner.runAll(...)` 已经串行跑全部启用任务，`runLock` 互斥、目录 diff 幂等、断网中止、改名限流退避都有了 |
| 数据库 | 无迁移需求（设置项走 SharedPreferences） |

结论：**引擎和权限都够用，缺的是「调度器 + 通知 + 设置项」这一层。**

## 二、技术选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **WorkManager（PeriodicWorkRequest）** | 官方推荐、进程被杀/重启后自动恢复、支持网络与充电约束、Doze 结束后补跑 | 周期最短 15 分钟，**实际触发时间不准**（系统排期 + 厂商限制），可能延后数小时 | ✅ 采用 |
| AlarmManager 精确闹钟 | 时间准 | 需 `SCHEDULE_EXACT_ALARM`，国内 ROM 仍可能拦；耗电高、Doze 里也受限 | Phase 3 可选「精确定时」实验 |
| 常驻前台服务 | 定时最准 | 常驻通知栏、耗电、被杀后不恢复 | ❌ 不做 |
| 进程内 Handler | 实现最简单 | App 一被杀就失效 | ❌ 不做 |
| 服务器 / 推送触发 | 最可靠 | 违背「无服务器」口径 | ❌ 不做 |

**WorkManager 版本**：`androidx.work:work-runtime-ktx:2.9.1`（Kotlin 2.0 / AGP 8.7 兼容）。

## 三、触发规则

### 3.1 频率档位（全局一个，不做每任务单独周期）

| 档位 | repeatInterval | 说明 |
|---|---|---|
| 每 6 小时 | 6h | 默认 |
| 每 12 小时 | 12h | |
| 每天一次 | 24h | 首次触发时间按「用户设置的钟点」推算（默认 08:00） |

- 加 **±15 分钟抖动**（`setInitialDelay` + 随机偏移），避免整点集中打接口。
- 开启自动更新的**首轮不等一个整周期**：延迟 `interval × 0.5` 先跑一次，让用户当天就能看到效果。
- 修改频率/开关 → `enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)` 覆盖旧排期。

### 3.2 每轮跑什么

- 只跑 `enabled = true` 的任务。
- 加**单任务最小间隔**：距该任务上次成功同步 < 5 小时就跳过，避免「每 6 小时把所有任务全量跑一遍」造成无谓请求。
- 多源、季判定、改名限流全部沿用现有引擎逻辑，不做第二套。

### 3.3 约束

| 约束 | 默认 | 可关 |
|---|---|---|
| 网络可用（`NetworkType.CONNECTED`） | 开，且不可关 | — |
| 仅 Wi-Fi（`UNMETERED`） | 关 | 可开 |
| 仅充电（`requiresCharging`） | 关 | 可开 |

失败重试：WorkManager `Result.retry()` + 指数退避（初始 15 分钟，最多 3 次）；**连续失败 3 轮自动暂停并通知一次**，避免无意义地反复撞接口。

## 四、可靠性：国内 ROM 是最大变量

HyperOS 的「自启动 / 省电策略」会让 WorkManager 从「每 6 小时」变成「你打开 App 才跑」。三层应对：

1. **补跑（必做，Phase 1）**：App 每次回到前台时检查「上次自动运行时间」，若 `now - lastRun > 2 × interval`，异步补跑一次（`trigger = background`，不弹打扰）。
2. **自检面板（Phase 2）**：设置页显示
   `上次自动更新：11-24 08:20（已完成）`、`最近 24 小时实际触发 2 次`；
   连续 24 小时 0 次 → 就地给一条引导：`系统可能限制了后台运行 → 去开启「自启动」和「无限制省电」`（附跳转按钮，能跳就跳，跳不了就给步骤文字）。
3. **精确闹钟实验（Phase 3，可选）**：只在用户选「每天固定时刻」时提供开关，用 `AlarmManager.setExactAndAllowWhileIdle` 兜底。

> 说明：我无法保证 100% 准时。这一层必须让用户「看得见实际效果」，而不是只在代码里以为跑过了。

## 五、通知

- 跑完发一条汇总通知，覆盖同一个 notify id（不堆积）：
  - 全部成功：`自动更新完成 · 3 个任务，新增 12 集`
  - 有失败：`自动更新：1 个任务失败（点开查看）`，点击进日志页
  - 需要重新登录：`夸克登录已失效，请打开 App 重新登录`
- Android 13+ 需要运行时申请 `POST_NOTIFICATIONS`：**首次打开「自动更新」开关时申请**，被拒也能跑，只是不通知（设置页写明「通知已关闭」）。
- 跑的过程中**不出常驻通知**（Phase 1）；只有当某轮预计超过 10 分钟（单次任务很多/文件多）才考虑 `setForeground`（Phase 2，清单权限已就绪）。
- 通知开关：设置页「自动更新后通知」[开/关]，默认开。

## 六、设置页（新增一段，放在「更新」区）

```
自动更新            [ 开 / 关 ]
频率                每 6 小时 | 每 12 小时 | 每天 08:00
仅在 Wi-Fi          [ 开 / 关 ]
仅在充电            [ 开 / 关 ]
跑完通知            [ 开 / 关 ]
上次自动更新         11-24 08:20 · 已完成（3 个任务）
```

- 关掉开关 → `cancelUniqueWork`，状态清空，不保留排期。
- 其余页不显示倒计时、不显示「下次几点」——系统本来就不保证，写了反而误导。

## 七、代码改动清单

| 新增/改动 | 内容 |
|---|---|
| `build.gradle.kts` | 加 `androidx.work:work-runtime-ktx:2.9.1` |
| `sync/AutoSyncPrefs.kt`（新） | SharedPreferences：开关、间隔、仅 WiFi、仅充电、通知、上次运行时间/结果 |
| `sync/AutoSyncScheduler.kt`（新） | 纯函数 `plan(interval, dailyAt)` → `(initialDelay, repeatInterval)`（可单测）+ `ensure/cancel` |
| `sync/AutoSyncWorker.kt`（新） | `CoroutineWorker`：读设置 → `TaskRunner.runAll(trigger = background)` → 写上次运行 → 发通知 |
| `sync/NotificationHelper.kt`（新） | 通知渠道（低优先级）+ 汇总文案 + 权限检查 |
| `MainActivity` / `AppRoot` | 启动时若开关开 → `ensure()`（不立即跑）；回前台补跑检查 |
| `SettingsScreen.kt` | 新增「自动更新」段 + 权限申请 |
| `RunLogScreen.kt` | `trigger = background` 显示「自动」 |
| 单测 | `AutoSyncSchedulerTest`（间隔/钟点换算、抖动范围、每天档的 initialDelay）、`AutoSyncPolicyTest`（最小间隔跳过、只跑启用任务、连续失败暂停） |

**明确不做**：常驻前台服务、精确整点（除 Phase 3 可选）、服务器推送、按任务独立周期、跑完后自动刷新卡片（前台已有 Flow，无需）。

## 八、分阶段落地

**Phase 1（可用版，约 1 天）**
WorkManager 周期任务 + 设置页开关/频率/约束/通知 + 日志显示「自动」+ 前台补跑 + 汇总通知。
默认**关闭**，用户自己开。

**Phase 2（可信版，约 1 天）**
每任务最小间隔（只跑到期任务）、连续失败自动暂停、自检面板（上次/最近 24 小时次数/引导文案）、长任务 `setForeground`。

**Phase 3（可选）**
每天固定时刻的精确定时（AlarmManager 兜底）、按任务独立开关。

## 九、风险与对策

| 风险 | 对策 |
|---|---|
| 厂商限制导致不按时 | 前台补跑 + 自检面板 + 引导用户放行（见得着，而不是静默失效） |
| 夸克/光鸭登录过期 | `DriveSession.ensure` 已能自动重登；重登失败 → 通知「请打开 App 重新登录」，本轮不再重试 |
| 与手动运行并发 | 复用 `TaskRunner.runLock`，自动那轮等手动跑完再开始，不并发 |
| 改名限流 | 沿用 Stage 38 的 2 并发 + 冷却退避；自动轮更保守（并发 1？待定） |
| 流量/电量 | 转存是引用不下载，流量极小；默认不带充电约束，长任务才考虑 |
| 日志被量刷 | `run_records` 保留 200 条不变；自动轮写 `trigger=background` 可筛 |

## 十、需要你拍板的 5 件事

1. **频率档位**是否就用「每 6 小时 / 每 12 小时 / 每天 08:00」？默认 6 小时可以吗？
2. **默认开关状态**：默认关闭（推荐，自己开）还是默认开？
3. **通知**默认开还是默认关？
4. **「仅 Wi-Fi」「仅充电」**要不要都做（默认都关）？还是只留「仅 Wi-Fi」？
5. **自动轮是否更保守**：改名并发从 2 降到 1（慢一点但不撞限流），还是保持 2？

确认后我按 Phase 1 先落地，跑一周看实际触发情况，再决定要不要做 Phase 2 的精确兜底。

---

# 后台自动刷新 · v2 定稿（2026-11-24）

> v1 是讨论稿（每 6/12/24 小时档 + 「仅充电时」约束）。后经用户确认，语义改为：**自定义时段表 + 插电即跑 + 不做 Wi-Fi 约束 + 不做「立即运行一次」**。本文为最终方案与推进步骤。

## 一、产品口径（已定）

| 项 | 定稿 |
| --- | --- |
| 触发一：每天定时 | 用户自定义 `HH:mm`，最多 4 条，默认 `09:00` + `20:00`，每条独立每天跑一次 |
| 触发二：充电 | **插电即跑一次**（触发器，不是闸门）；不是「仅在充电时才跑」 |
| 网络 | 不限制、不提示、不做仅 Wi-Fi 开关；只发 API 请求，流量可忽略 |
| 立即运行一次 | 不做；主页右下圆钮「全部检测」已承担该职责 |
| 通知 | 默认开，独立渠道；只在「有新集 / 有失败」时发，纯空转不发 |
| 总开关 | 默认关；关着时零调度、零唤醒、零通知 |
| 设置页位置 | 「外观」段之后新增「自动化」段，顺序与 mockup v3 一致 |

## 二、刷新机制（回答「App 关了还能不能跑」）

- 调度用 **WorkManager**：任务持久化在系统里，**App 进程被划掉、甚至重启手机后仍会被拉起执行**（`BOOT_COMPLETED` 后 WorkManager 自行恢复，不需要额外开机广播）。
- 两类无法保证：用户在系统里对 App 点「强行停止」；HyperOS 的激进省电把后台彻底冻住。对策是设置页给「自检」入口（引导开启「省电策略：无限制」），并在状态卡上给出异常文案（如「已 3 天未运行 · 可能被系统限制」）。
- 电量与占用：转存是**服务端复制**，手机只发 API 请求。空转一轮约几十秒、几十 KB；有新增集时受改名限流（`THROTTLE_DELAY_MS=2500`、并发 2）影响，83 集约 3.5 分钟。日常耗电可忽略，睡眠期由 WorkManager 合并唤醒。

## 三、算法设计

1. **时段链**：每个 `HH:mm` 注册一条**唯一命名的 OneTimeWork**（`auto-sync-slot-{index}`），`initialDelay` = 距离下一个该时刻的时长；Worker 跑完后再排同一时刻的下一次（自续期）。不用 PeriodicWork，避免 WorkManager 15 分钟粒度带来的时间漂移；Doze 下允许延后十几分钟，属预期。
2. **充电触发**：静态注册 `ACTION_POWER_CONNECTED` 广播接收器（该广播在 Android 8+ 的后台广播限制白名单内），收到后交给统一入口，不直接起 Work。
3. **统一入口 `AutoSyncScheduler.requestRun(context, reason)`**：所有触发（时段 / 充电）都必须走它，它负责：
   - 总开关关 → 直接 return
   - 距上次运行 < **3 小时** → return（防抖，所有来源共用同一份节流，避免插电与定时叠着跑）
   - 已在跑（进程内 `AtomicBoolean` + `ExistingWorkPolicy.KEEP`）→ return
   - 通过后投递唯一 Work `auto-sync-run`
4. **执行**：`CoroutineWorker` 里 `TaskRepository(AppDatabase.get(ctx))` + `TaskRunner(repo, CredentialStore, TmdbStore, RuleStore)` 自行组装（和 `MainActivity` 里同样的构造方式，不依赖 ViewModel），取 `repo.tasks().filter { it.enabled }`，调 **`runner.runAll(tasks, RunRecordEntity.TRIGGER_BACKGROUND)`**。这条路径 v0.7 就已存在，只是没人调。
5. **汇总与落盘**：把各任务的 `Report.restoredFiles` 求和、`renameItems` 求和、失败数与原因收集；写 `auto_sync_prefs` 的 `lastRunAt / lastRunOk / lastRunNew / runCount24h`；运行日志本身由 `TaskRunner.run` 内部写 `RunRecordEntity`（trigger=background），日志页无需改动即可看到。
6. **通知**：`新增>0 || 失败>0` 才发一条合并通知；点击回到任务列表。空转不发。
7. **10 分钟上限**：WorkManager 单个 Worker 约 10 分钟会被系统停。对策：先按串行实跑（典型 1 分钟内完成）；若实测有任务群超过 8 分钟，再给 Worker 加 `setForeground`（走前台服务通知）作为兜底，不改并行度。

## 四、隔离红线（避免影响其他模块）

| 红线 | 具体做法 |
| --- | --- |
| 不动数据库 | 配置全部存 `SharedPreferences("auto_sync_prefs")`，**不加表、不加字段、不做迁移**（DB 仍是 version 11） |
| 不动引擎 | `TaskRunner` / `TreeSyncEngine` / `TransferManager` 一行不改，只调它们的公开入口 |
| 不动既有 UI | `TaskListScreen` / `RunLogScreen` / `NewTaskScreen` 一字不改；设置页只在「外观」段后**插入一行**调用新组件，其余段落原样 |
| 不动 ViewModel | 后台路径不碰 `TasksViewModel`（它绑 `viewModelScope`，进程被杀就死）；自建无 UI 引擎 |
| 不引精确闹钟 | 不用 `AlarmManager` 精确闹钟，避免 Android 12+ 权限弹窗与厂商拦截 |
| 单一调度入口 | 所有触发只经 `AutoSyncScheduler`，任何组件都不许自己 `enqueue` |
| 可整体摘除 | 关总开关 = 取消全部 Work + 撤销时段；卸载这个功能只需删 `sync/` 包 + 设置页一行调用 |

## 五、文件清单

**新增（`sync/` 包，7 个文件）**

- `AutoSyncConfig.kt`：时段解析/校验（`HH:mm`→分钟、排序去重、最多 4 条）、下次时刻计算（纯函数，可单测）
- `AutoSyncPrefs.kt`：`auto_sync_prefs` 读写（开关、时段表、充电开关、通知开关、lastRunAt、计数）
- `AutoSyncScheduler.kt`：唯一入口 `requestRun()` / `rescheduleAll()` / `cancelAll()`
- `AutoSyncWorker.kt`：`CoroutineWorker`，执行 + 汇总 + 调 Notifier
- `AutoSyncEngine.kt`：无 UI 执行体（自建 repo/runner，`runAll` 串行，返回汇总结果）
- `AutoSyncNotifier.kt`：独立渠道 `auto_sync`、合并通知、权限检查
- `ChargingReceiver.kt`：`ACTION_POWER_CONNECTED` → `requestRun(REASON_CHARGING)`

**修改（仅 4 处）**

- `app/build.gradle.kts`：加 `androidx.work:work-runtime-ktx`
- `AndroidManifest.xml`：注册 `ChargingReceiver`
- `ui/AppScreens.kt`：`SettingsScreen` 里「外观」段后插入 `AutoSyncSection(...)` 一行
- `ui/AutoSyncSection.kt`（新增 UI 文件，放在 `ui/` 而非 `sync/`，与设置页其他段同层）

> 单测新增 `AutoSyncConfigTest`（时段解析、跨零点下次时刻、最多 4 条上限、防抖判定），既有 141 例不动。

## 六、推进步骤（每步可独立回退）

**P1 配置层（纯逻辑，先测）**
产出：`AutoSyncConfig` + `AutoSyncPrefs` + 单测。
验收：单测全绿（新增 ≥ 12 例）；不接任何 Android 调度，装上去界面无任何变化。
回退：删两个文件 + 一个测试文件。

**P2 调度层（只排程，不干活）**
产出：`AutoSyncScheduler` + `AutoSyncWorker`（先只写一条日志就 return）+ `ChargingReceiver` + 清单 + 依赖。
验收：手动在代码里开开关，用 `adb shell dumpsys jobscheduler | grep zhuancunmiao` 能看到唯一的 job；插电后 3 小时内再插一次不产生第二次；关开关后 job 清空。
回退：删 4 个文件 + 回退依赖与清单两行。

**P3 执行层（真跑）**
产出：`AutoSyncEngine`（接 `runner.runAll(..., TRIGGER_BACKGROUND)`）+ 汇总。
验收：真机上关掉 App（划掉最近任务）→ 等到时段 → 日志页出现 trigger=background 的记录、集数正确增长；连续两次触发间隔 < 3h 时第二次不跑。
回退：`AutoSyncWorker` 里改回「只写日志」，其余保留。

**P4 通知**
产出：`AutoSyncNotifier` + 渠道 + 点击跳转。
验收：有新增集时通知出现且点开回到任务列表；纯空转不发；Android 13 首次开开关时弹通知权限。
回退：`AutoSyncPrefs` 的通知开关默认关即可屏蔽。

**P5 设置页 UI**
产出：`ui/AutoSyncSection.kt`（总开关 / 时段 chips+ `+` / 充电时刷新 / 跑完发通知 / 状态卡）+ 设置页一行插入。
验收：与 mockup v3 逐项对齐；总开关关时整段收起；改动时段立即重排 Work；状态卡数字与 prefs 一致。
回退：删一行调用，界面回到现状，后台仍可用。

**P6 容错与自检**
产出：状态卡异常文案（「今晚未跑 · 未充电」「已 3 天未运行 · 可能被系统限制」）+「去设置省电策略」入口（跳系统页）。
验收：故意断网 / 关充电，状态卡给出可读原因而不是空白。

**P7 联调 + 发版**
验收清单：① App 划掉后按时跑；② 手机重启后仍按时跑；③ 插电即跑且 3h 内不重复；④ 通知内容正确；⑤ 一晚待机耗电 < 2%；⑥ 单测全绿；⑦ 更新 CHANGELOG、发 v0.9.0 双端。

## 七、待你拍板的三点

1. **3 小时最小间隔**（时段触发与充电触发共用）：建议采纳，界面不暴露。
2. **默认时刻** `09:00` + `20:00`：确认即定。
3. **时段触发是否也受 3h 限制**：若你希望「晚上 20:00 一定跑，哪怕 18:00 刚插电跑过」，则时段触发不受限、只有充电触发受限——这种更符合直觉，但会出现一晚两次。默认按「都受限」。

## v3 追加确认（2026-11-24，用户拍板）

- 冷却固定 **3 小时**，不做 3/6/12 档可选，界面不暴露
- 手动「全部检测」**不**写自动刷新时间戳（实现上不加钩子，保持 TasksViewModel 零改动）；表现为手动刚跑完插电仍会再跑一轮，若以后觉得多余，只需在 unAll 里加一行写 prefs
- 关机/深度休眠错过的定时点，恢复后**只补最近一次**，更早的丢弃
- 定时点 **不受** 3 小时约束（人工设定即承诺）；只有插电触发受它约束
