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
