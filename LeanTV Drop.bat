@echo off
rem Drag video files onto this file (or its shortcut), or double-click it and drop them on the window.
start "" powershell -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0drop\LeanTVDrop.ps1" %*
