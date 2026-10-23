@echo off
rem Double-click to undo everything LeanTV changed on the TV (see scripts\restore.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\connect.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\restore.ps1" %*
pause
