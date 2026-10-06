@echo off
chcp 936 >nul
cd /d "%~dp0"
setlocal enabledelayedexpansion
set "FOUND_CMD="
set "FOUND_ARGS="

rem ---- detect a usable Python (needs tkinter + requests) ----
call :try "py" "-3"
if defined FOUND_CMD goto run
call :try "python" ""
if defined FOUND_CMD goto run
for %%V in (314 313 312 311 310) do (
    call :try "%LOCALAPPDATA%\Programs\Python\Python%%V\python.exe" ""
    if defined FOUND_CMD goto run
    call :try "C:\Python%%V\python.exe" ""
    if defined FOUND_CMD goto run
    call :try "C:\Program Files\Python%%V\python.exe" ""
    if defined FOUND_CMD goto run
)

echo.
echo   [x] 没有找到可用的 Python 环境。
echo.
echo   本工具需要 Python 3.8 以上，并安装 requests：
echo       1. 安装 Python 官方版本（勾选 tcl/tk 与 Add to PATH）
echo       2. 命令行执行： pip install requests
echo.
echo   下载地址： https://www.python.org/downloads/
echo.
pause
exit /b 1

:run
start "" %FOUND_CMD% %FOUND_ARGS% "%~dp0muc_traffic.pyw"
exit /b 0

:try
if defined FOUND_CMD exit /b 0
%~1 %~2 -c "import tkinter, requests" >nul 2>&1
if !errorlevel! equ 0 (
    set "FOUND_CMD=%~1"
    set "FOUND_ARGS=%~2"
)
exit /b 0
