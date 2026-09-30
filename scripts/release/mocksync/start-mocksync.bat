@echo off
rem Starts MockSync in this window: HTTPS on 8443 (desktop client), HTTP on 8080
rem (admin API, Android debug build). Close the window or press Ctrl+C to stop it.
rem Keys, registered machines, config overrides and uploaded packets are kept in work\.
rem Java: runtime\ in this folder, else %%JAVA_HOME%%, else java on PATH (Java 11 or newer).
rem Extra arguments go to Spring Boot, e.g. --mocksync.http-port=0
setlocal
cd /d "%~dp0"
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%~dp0runtime\bin\java.exe" set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not defined MOCKSYNC_JAVA_OPTS set "MOCKSYNC_JAVA_OPTS=-Xmx512m"
"%JAVA_EXE%" %MOCKSYNC_JAVA_OPTS% -jar mock-sync.jar %*
