@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0app\Installer.ps1" -RestoreOnly
if errorlevel 1 pause
