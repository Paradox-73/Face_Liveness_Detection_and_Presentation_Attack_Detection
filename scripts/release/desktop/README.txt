MOSIP Registration Client (desktop) with face liveness and presentation attack detection
======================================================================================

This folder is a ready-to-run copy of the MOSIP desktop Registration Client 1.2.0.2 with the
team's face liveness module. It is built to talk to MockSync (a local stand-in for the MOSIP
server) at https://localhost:8443. Windows 10/11, 64-bit. 4 GB free RAM.

Contents
  jre\                 Zulu 11.0.8 JRE with JavaFX (the runtime MOSIP ships with the client)
  lib\                 client jars, liveness engine (liveness-core, liveness-onnx), signed ONNX
                       models (liveness-onnx-*-models.jar), ONNX Runtime 1.20.0, face-match SDK
                       (face-match-sdk), MOSIP mock SDK (mock-sdk.jar, finger and iris), logback.xml
  start-client.bat     starts the client in the background (logs in logs\registration.log)
  stop-client.bat      closes it
  run.bat              MOSIP's standard launcher (same command line as the official reg-client.zip)
  setup-mocksync.bat   trusts MockSync's HTTPS certificate and registers this computer with MockSync
  tools\               machine-key-tool.jar, used by setup-mocksync.bat
  scripts\             the PowerShell scripts behind the .bat files
  BUILD-INFO.txt       client version, server host, source commit
  docs\TEST-DATA.md    demo logins (synthetic, valid only on MockSync)
  licenses\            licence texts, model sources and licences

Quick start (full steps: docs/release/INSTALL.md in the source repository)
  1. Start MockSync and MockMDS from their own packages.
  2. setup-mocksync.bat       (once per MockSync installation)
  3. start-client.bat         -> login screen after about 1-2 minutes
  4. Log in as officer1 (password in docs\TEST-DATA.md). The first login runs the initial sync and
     ends with "Restart Application"; run start-client.bat again and log in again.

The client keeps its state next to this file: db\ (database), .mosipkeys\ (machine keys) and
logs\. Delete them (start-client.bat -Reset) to start as a fresh install.
