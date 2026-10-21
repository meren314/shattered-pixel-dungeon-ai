@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0ai-probe\Start-Probe.ps1"
if errorlevel 1 pause
