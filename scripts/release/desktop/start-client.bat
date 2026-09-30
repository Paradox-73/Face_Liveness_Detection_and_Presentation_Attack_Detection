@echo off
rem Starts the Registration Client from this folder in the background.
rem Options are passed on to scripts\desktop-run.ps1, e.g. -Console or -Reset.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\desktop-run.ps1" -RunDir "%~dp0." %*
