# 转存喵 0.7.0 发布说明

纯单机安卓 App，无服务器 / 无 Docker / 无 Python 引擎。只做「追剧转存」：夸克 + 光鸭，单账号。

## 包与安装

| 包 | 路径 | 体积 | 用途 |
| --- | --- | --- | --- |
| 正式包 | `app/build/outputs/apk/release/app-release.apk` | 1.96 MB | 日常使用，R8 混淆 + 资源收缩 |
| 调试包 | `app/build/outputs/apk/debug/app-debug.apk` | 11.62 MB | 排查用：`run-as` 可读数据库、日志更全 |

两个包共用 debug 签名，**可以直接互相覆盖安装，数据不丢**。

> 注意：release 包 `debuggable=false`，`adb run-as` 读不到数据库。要查任务/快照/运行记录，请装 debug 包后用 `python tools/read_tasks.py docs/dbcopy`。

## 功能范围

- **任务列表常驻**：卡片 5 项信息 —— 剧名、盘里集数 → TMDB 最新集、状态（已是最新 / 缺 N 集 / 未同步）、进度条、更新日 · 网盘 · 最近同步时间
- **打开 App 自动同步一轮**，每个页面生命周期只跑一次
- **单条「检测转存」+「全部检测」**，串行执行，进程内互斥
- **任务目录隔离**：每个任务在自己保存位置下建「剧名」目录，集数统计以该目录为边界
- **转存策略沿用原项目**：不扁平化，保留分享里的真实层级（第 N 季 - 分辨率 / 集.mp4）
- **网盘驱动**：夸克（Cookie）+ 光鸭（短信验证码 / refresh token），单账号
- **改名**：深层目录内的集文件按规则批量改名
- **运行日志**：最近 30 条，含任务名、触发方式、耗时、新增/跳过数、错误原文
- **崩溃日志**：未捕获异常落盘 `filesDir/crash.log`，运行日志页可查看/清空

## TMDB（可选，国内需自建反代）

用于算「TMDB 最新集」与自动推断更新日。设置页 → TMDB：

1. 填 API Key（v3）或 Read Access Token（v4），App 自动判别
2. 接口地址默认 `https://api.themoviedb.org` —— **国内直连不通**（实测 TCP 建不起来，PC 与手机一致）
3. 部署 `tools/tmdb-proxy-worker.js` 到 Cloudflare Worker，把 `https://xxx.workers.dev` 填进「接口地址」
4. 点「保存并测试」，绿字才算生效

不填也能用，卡片显示「未绑定 TMDB」，只是缺集数无法计算。

## 已知限制

- 后台定时刷新是二期（P2，走 AlarmManager / WorkManager），当前只有「打开 App 同步一轮」
- 「播出时刻」只做记录，没有提醒
- 集数口径：`SxxEyy` 与「第 N 集」两套编号并存时，集数与最大集号可能不一致（如 214 集 / E216）
- HyperOS 会冻结后台进程，长任务请保持 App 在前台

## 版本

- versionCode 30 / versionName 0.7.0（debug 包实际名 `0.7.0-debug`）
- minSdk 26 / targetSdk 34，compileSdk 34
- 数据库 v3（`MIGRATION_1_2`：run_records.taskName；`MIGRATION_2_3`：snapshots.lastOk）
