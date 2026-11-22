@echo off
powershell.exe -NoProfile -File "%~dp0preview-android.ps1"
if errorlevel 1 pause
