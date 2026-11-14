@echo off
cd /d "%~dp0"
echo Starting DSAssist PC Agent...
echo Keep this window open while using the phone.
echo.
echo Command execution is disabled by default.
echo To enable it, run: DSAssistAgent.exe -allow-exec
echo.
DSAssistAgent.exe
pause
