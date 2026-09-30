@echo off
rem Starts MockMDS (face, finger and iris SBI 0.9.5 device simulator) in this window.
rem It listens on 127.0.0.1, first free port from 4501. Close the window or press Ctrl+C to stop it.
rem Settings: application.properties in this folder (face frame source, faults, certification level).
rem Java: runtime\ in this folder, else %%JAVA_HOME%%, else java on PATH (Java 21 or newer).
rem Set MOCKMDS_PURPOSE=Auth for an authentication device instead of a registration device.
setlocal
cd /d "%~dp0"
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%~dp0runtime\bin\java.exe" set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not defined MOCKMDS_PURPOSE set "MOCKMDS_PURPOSE=Registration"
if not defined MOCKMDS_JAVA_OPTS set "MOCKMDS_JAVA_OPTS=-Xmx256m"
"%JAVA_EXE%" %MOCKMDS_JAVA_OPTS% -cp "mock-mds.jar;lib\*" io.mosip.mock.sbi.test.TestMockSBI "mosip.mock.sbi.device.purpose=%MOCKMDS_PURPOSE%" "mosip.mock.sbi.biometric.type=Biometric Device"
