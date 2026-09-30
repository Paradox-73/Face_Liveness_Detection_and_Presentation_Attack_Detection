@echo off
rem Trusts MockSync's HTTPS certificate and registers this computer with MockSync.
rem MockSync must be running. Options go to scripts\setup-mocksync.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup-mocksync.ps1" %*
