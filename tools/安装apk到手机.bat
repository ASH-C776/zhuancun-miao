@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
set "SERIAL=K7ROP7N7ZDTO7XAU"
title 安装APK到手机

if "%~1"=="" (
    echo 请把 APK 文件拖到本图标上运行！
    pause > nul
    exit /b
)

echo ==========================================
echo    安装 APK 到手机
echo ==========================================
echo.

if not exist "%~1" goto :bad_file

echo 正在定位手机 ...
set "TARGET="

rem 1) 已有无线连接直接复用
for /f "tokens=1,2" %%a in ('adb devices 2^>nul ^| findstr /i ":5555"') do (
    if "%%b"=="device" if not defined TARGET set "TARGET=%%a"
)

rem 2) mDNS 自动发现并验证
if not defined TARGET (
    for /l %%n in (1,1,3) do (
        if not defined TARGET (
            for /f "tokens=1,3" %%a in ('adb mdns services 2^>nul ^| findstr /i "adb-%SERIAL%"') do (
                if not defined TARGET set "TARGET=%%b"
            )
            if not defined TARGET ping -n 3 127.0.0.1 >nul
        )
    )
    if defined TARGET (
        adb connect !TARGET! >nul 2>&1
        adb -s !TARGET! get-state >nul 2>&1
        if errorlevel 1 set "TARGET="
    )
)

rem 3) 上次成功的地址兜底
if not defined TARGET (
    if exist "%~dp0last_ip.txt" (
        set /p TARGET=<"%~dp0last_ip.txt"
        if defined TARGET (
            adb connect !TARGET! >nul 2>&1
            adb -s !TARGET! get-state >nul 2>&1
            if errorlevel 1 set "TARGET="
        )
    )
)

if not defined TARGET goto :no_device

echo 目标设备：!TARGET!
echo 正在安装 %~nx1 ...
echo.
adb -s !TARGET! install -r "%~1"
if errorlevel 1 goto :install_fail

:ok
echo.
echo [成功] 安装完成：%~nx1
ping -n 2 127.0.0.1 >nul
exit /b 0

:no_device
echo [失败] 没找到手机，APK 没有安装。
echo.
echo   本脚本已依次尝试：现有连接 - mDNS 扫描 - 上次地址
echo.
echo   请检查：
echo   1. 手机「开发者选项 - 无线调试」是否开启
echo   2. 手机与电脑是否连同一个 WiFi
echo   3. 手机是否息屏太久导致 WiFi 掉线（点亮屏幕再拖一次）
echo   4. 都不行就插上 USB，跑一次「手机恢复无线ADB.bat」
goto :fail_stop

:install_fail
echo.
echo [失败] 安装未完成，请对照上面的 adb 报错：
echo.
echo   offline / not found                 手机掉线，跑「手机恢复无线ADB.bat」
echo   INSTALL_FAILED_UPDATE_INCOMPATIBLE  与已装版本签名不同，先卸载旧版
echo   INSTALL_FAILED_VERSION_DOWNGRADE    版本低于已装版本，先卸载旧版
echo   INSTALL_FAILED_ALREADY_EXISTS       包已存在且不允许覆盖
echo   INSTALL_PARSE_FAILED                apk 损坏或不是安卓安装包
echo   INSTALL_FAILED_INVALID_APK          安装包不完整
echo   INSTALL_FAILED_INSUFFICIENT_STORAGE 手机存储空间不足
echo   INSTALL_FAILED_USER_RESTRICTED      手机上需允许「USB 安装」
echo   INSTALL_FAILED_TEST_ONLY            调试包，需加 -t 参数
goto :fail_stop

:bad_file
echo [失败] 文件不存在或路径无效：
echo   %~1
echo.
echo   请确认拖上来的是完整的 .apk 文件（不是 .zip、.apks 或文件夹）。
goto :fail_stop

:fail_stop
echo.
echo ── 看完按任意键关闭窗口 ──
pause > nul
exit /b 1
