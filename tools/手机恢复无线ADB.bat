@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
set "SERIAL=K7ROP7N7ZDTO7XAU"
title 手机无线ADB 一键恢复

echo ==========================================
echo    手机无线 ADB 一键恢复
echo ==========================================
echo.

rem ---------- 0) 已经在线？直接秒退 ----------
set "TARGET="
for /f "tokens=1,2" %%a in ('adb devices 2^>nul ^| findstr /i ":5555"') do (
    if "%%b"=="device" if not defined TARGET set "TARGET=%%a"
)
if defined TARGET (
    echo [已在线] !TARGET!
    echo        无线 ADB 本来就通，无需操作。
    goto :ok
)

rem ---------- 1) 检查 USB ----------
set "ST="
for /f %%s in ('adb -s %SERIAL% get-state 2^>nul') do set "ST=%%s"
if /i not "!ST!"=="device" (
    echo [失败] USB 连接不可用，当前状态：!ST!
    echo.
    echo   请依次确认：
    echo   1. 数据线插好（要能传文件的线，纯充电线不行）
    echo   2. 手机已解锁，并在「允许USB调试」弹窗点了允许
    echo   3. 开发者选项里「USB调试」已打开
    echo   4. 若手机已能无线连上，本脚本不需要跑
    goto :fail
)

rem ---------- 2) 开 5555 端口 ----------
echo [1/2] 正在开启 5555 端口 ...
adb -s %SERIAL% tcpip 5555 >nul 2>&1
ping -n 4 127.0.0.1 >nul

rem ---------- 3) 自动发现 + 连接 ----------
echo [2/2] 正在自动发现手机无线地址 ...
call :discover
if not defined TARGET (
    echo.
    echo [失败] 端口已开，但发现不了手机的无线地址。
    echo   1. 手机与电脑是否连的同一个 WiFi
    echo   2. 手机「无线调试」是否被关掉
    echo   3. 路由器是否开了 AP 隔离 / 访客网络（会挡住 mDNS）
    goto :fail
)

adb connect !TARGET! >nul 2>&1
adb -s !TARGET! get-state >nul 2>&1
if errorlevel 1 (
    echo.
    echo [失败] 发现 !TARGET!，但实际连不上。
    echo   1. 手机息屏后 WiFi 掉了，点亮屏幕再跑一次
    echo   2. 手机「无线调试」被关闭
    echo   3. 先执行 adb kill-server，再重跑本脚本
    goto :fail
)

:ok
echo.
echo [成功] 无线 ADB 已就绪：!TARGET!
echo        现在可以拔掉 USB 线了。
>"%~dp0last_ip.txt" echo !TARGET!
ping -n 4 127.0.0.1 >nul
exit /b 0

:fail
echo.
echo ── 看完按任意键关闭窗口 ──
pause > nul
exit /b 1

:discover
set "TARGET="
for /l %%n in (1,1,4) do (
    if not defined TARGET (
        for /f "tokens=1,3" %%a in ('adb mdns services 2^>nul ^| findstr /i "adb-%SERIAL%"') do (
            if not defined TARGET set "TARGET=%%b"
        )
        if not defined TARGET ping -n 3 127.0.0.1 >nul
    )
)
if not defined TARGET (
    if exist "%~dp0last_ip.txt" (
        set /p TARGET=<"%~dp0last_ip.txt"
        if defined TARGET (
            adb -s !TARGET! get-state >nul 2>&1
            if errorlevel 1 set "TARGET="
        )
    )
)
exit /b 0
