@echo off
chcp 65001 >nul
:: 使用固定的 Python 路径，避免环境混乱
set PYTHON="C:\Program Files\Python38\python.exe"

if "%~1"=="" (
    echo 用法: run_python.bat [你的脚本.py]
    exit /b 1
)

echo 使用 Python: %PYTHON%
%PYTHON% %*
