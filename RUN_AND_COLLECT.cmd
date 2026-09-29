@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo HEAT NETWORK LAB - ITERATION 001
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0local_iteration\run.ps1"
set "RC=%ERRORLEVEL%"
echo.
echo Runner exit code: %RC%
echo Send the new ITERATION_001_LOGS_*.zip from this folder.
pause
exit /b %RC%
