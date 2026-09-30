MockSync: local stand-in for the MOSIP server
=============================================

Answers the calls the MOSIP Registration Clients make for first login, initial sync, operator
onboarding, face authentication and packet upload, so the real clients run on one computer
without MOSIP's hosted servers. It also serves every face liveness setting
(mosip.registration.liveness.*), which the clients receive through their normal config sync.

Contents
  mock-sync.jar             the server (Spring Boot executable jar)
  data\                     reference data (MOSIP master data and config snapshots, MPL-2.0)
  config\application.properties   package settings (device trust certificates in certs\)
  certs\                    MockMDS root certificates served to the clients as device trust anchors
  runtime\                  Java 21 runtime (only in the -win-x64 zip)
  start-mocksync.bat / .sh  start it in the foreground
  set-config.ps1 / .sh      change a served setting, e.g. a liveness threshold
  docs\MOCKSYNC.md          full documentation (API, users, machine registration, limits)
  docs\TEST-DATA.md         demo logins (synthetic, valid only on MockSync)

Start:   start-mocksync.bat          (Linux/macOS: bash start-mocksync.sh)
Ports:   HTTPS 8443 (desktop client), HTTP 8080 (admin API; Android debug build via adb reverse)
State:   work\ next to this file (keys, HTTPS certificate, registered machines, overrides, packets).
         Delete work\ to start over; every client must then run its MockSync setup again.
Check:   http://127.0.0.1:8080/admin/machines answers with a JSON list.
Users:   officer1, officer2 (officers), supervisor1 (supervisor); passwords in docs\TEST-DATA.md.
Change a liveness setting (clients pick it up at their next login or sync):
         set-config.ps1 -Set @{ 'mosip.registration.liveness.passive.threshold' = '85' }
