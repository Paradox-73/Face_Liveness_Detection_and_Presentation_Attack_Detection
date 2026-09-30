# 0004. Build MockSync, a local stand-in for the MOSIP server

- Status: Accepted
- Date: 2026-09-28

## Context

Both Registration Clients refuse to do anything useful before a first online login and a full initial sync:
machine registration, encrypted login, configuration, master data, users, CA certificates. MOSIP's hosted test
environment was not available to the team for automated runs, and the PS04 configuration requirement ("any
configuration ... updates ... shall be securely synchronized when the Registration Client is online") has to be
shown with real sync messages, not a patched client.

## Decision

`tools/mock-sync`: one Spring Boot 2.7 application (Java 11) that answers the calls the unmodified clients make.

- HTTPS on 8443 for the desktop client, plain HTTP on 8080 for the Android debug build (`adb reverse`).
- Responses signed and encrypted exactly as MOSIP's keymanager 1.2.0.1 expects (TPM, LOCAL and ANDROID key wraps),
  implemented with the JDK, BouncyCastle and TSS.Java; the test suite checks the output with the real keymanager
  classes (test scope only).
- Reference data converted from `mosip-data` 1.2.0.1 and `mosip-config` v1.2.0.1 (MPL-2.0, kept in `data/`).
- Serves every `mosip.registration.liveness.*` key, the SFace SDK selection, MockMDS's device CA certificates and
  a DEVICE CA for the Android built-in camera.
- Loopback-only admin API: register machines, change configuration live, list and decrypt uploaded packets to
  show their `LIVENESS_*` evidence.

## Consequences

- The real clients complete first login, initial sync, onboarding (with a mock IDA answer), registration, packet
  upload and configuration change by sync on one laptop, offline from the internet. Both end-to-end reports run
  against it.
- Scripted demos can change policy mid-run (`PUT /admin/config`) and verify packets (`GET /admin/packets/{name}/liveness`).
- It is not a MOSIP server: no Keycloak, no biometric matching in IDA (always `authStatus: true`), no packet
  processing, no client-upgrade endpoint. These limits are listed in `tools/mock-sync/README.md`.
- Its keys are generated on first start into `tools/mock-sync/work/` and never committed.

## Alternatives considered

- **MOSIP sandbox (Collab):** needs credentials, network and registered devices; not repeatable in CI.
- **Patching the clients to skip sync:** would not exercise the real config path, and would change upstream code
  for testing only.
- **Loading keymanager in the server:** its Spring Boot 2.0 dependencies conflict with 2.7; used in tests instead.
