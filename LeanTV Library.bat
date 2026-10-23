@echo off
rem Shares your video folders with the TV (Videos tile). Lives in the tray; right-click the icon for folders.
start "" powershell -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0drop\LeanTVLibrary.ps1"
