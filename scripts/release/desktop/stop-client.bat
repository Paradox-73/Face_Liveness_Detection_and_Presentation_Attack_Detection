@echo off
rem Closes the Registration Client started with start-client.bat.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\desktop-run.ps1" -RunDir "%~dp0." -Stop
