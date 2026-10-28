@echo off
setlocal
cd /d "%~dp0"
title FyrusLauncher - Mi Box S2
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0app\Installer.ps1" %*
if errorlevel 1 pause
