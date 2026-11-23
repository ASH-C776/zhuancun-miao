# 真机安装 / 无线 ADB 固定流程（2026-11 更新）

两个脚本的正本在 `F:\下载\`，项目内 `tools\` 下有备份。**以后装机不要手写 adb install，用这两个 bat。**

## 安装 APK

`F:\下载\安装apk到手机.bat`

- 用法：把 `app-debug.apk` 拖到 bat 图标上（bat 参数 `%~1` 就是 apk 路径）
- 定位手机的三种尝试，依次降级：
  1. `adb devices` 里直接找 `:5555` 且状态 `device` 的条目
  2. mDNS 自动发现：`adb mdns services` 过滤 `adb-K7ROP7N7ZDTO7XAU`，拿到地址后 `adb connect`（重试 3 次）
  3. 读同目录 `last_ip.txt` 当作无线地址再 `adb connect` 验证
- 找不到就打印排障提示（同一 WiFi / 无线调试是否被关 / 息屏掉线 / 改用 USB 跑恢复脚本）
- 安装失败会按 errorlevel 码给中文解释（UPDATE_INCOMPATIBLE / VERSION_DOWNGRADE / TEST_ONLY 等）

## 恢复无线 ADB

`F:\下载\手机恢复无线ADB.bat`

- 流程：先看是否已有 `:5555` 在线（有就直接成功）→ 否则 USB 校验 `adb -s K7ROP7N7ZDTO7XAU get-state` → `adb tcpip 5555` → mDNS 自动找地址（失败回落 `last_ip.txt`）→ `adb connect` → 写回 `last_ip.txt`
- 结论：**IP 是浮动的，脚本自己做发现，不要再记 192.168.31.x 这种固定地址**

## 环境事实

- 序列号 `K7ROP7N7ZDTO7XAU`（Xiaomi Civi 3，USB）
- `adb` 现在**已在 PATH**（`D:\Android\Sdk\platform-tools\adb.exe`），bat 里直接写 `adb` 即可
- 实测当前两台同时在线：USB `K7ROP7N7ZDTO7XAU` 与无线 `192.168.31.145:5555`（`last_ip.txt` 记录值）
- HyperOS 仍拒绝 `adb shell input tap/keyevent/swipe`（`INJECT_EVENTS`），只能用 `am start` extras + 截图 / 读库
