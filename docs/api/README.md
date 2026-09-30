# API documentation

Every interface this project created or relies on, one page each. Each page gives the purpose, the methods or
endpoints, request and response examples, how callers authenticate, the errors, and one worked use case.

| API | Kind | Created or used | Page |
|---|---|---|---|
| Liveness engine (`liveness-core`, `liveness-onnx`) | Java library, in-process | Created | [liveness-engine.md](liveness-engine.md) |
| Face capture device adapter (`FaceCaptureDevice`) | Java interface, in-process | Created | [device-adapter.md](device-adapter.md) |
| Desktop liveness services (`LivenessOrchestrator`, gates) | Java / Spring beans inside the desktop client | Created | [desktop-services.md](desktop-services.md) |
| Android liveness API (Pigeon `LivenessApi`, native `LivenessService`, capture screen intent) | Dart-to-Java bridge and Android intent | Created | [android-liveness-api.md](android-liveness-api.md) |
| Face-match SDK (`IBioApiV2`) | MOSIP biometric SDK interface | Implemented (MOSIP interface) | [face-match-sdk.md](face-match-sdk.md) |
| SBI device calls (`MOSIPDISC`, `MOSIPDINFO`, `STREAM`, `RCAPTURE`) | HTTP on 127.0.0.1:4501-4600 | Used (MOSIP interface) | [sbi-used.md](sbi-used.md) |
| MockMDS admin extensions (`/admin/source`, `/admin/fault`, ...) | HTTP on 127.0.0.1 | Created | [mock-mds-admin.md](mock-mds-admin.md) |
| MockSync (sync, IDA, packet and admin endpoints) | HTTPS 8443 / HTTP 8080 | Created (MOSIP endpoint shapes) | [mock-sync.md](mock-sync.md) |

Reference tables used by several APIs:
- Configuration keys: [../design/configuration.md](../design/configuration.md)
- Audit events and packet evidence keys: [../design/audit-and-evidence.md](../design/audit-and-evidence.md)
- Error codes: [../design/errors.md](../design/errors.md)
- On-screen message keys and texts: `docs/ux/message-catalogue.csv`

Javadoc: every public type and member of `liveness-core`, `liveness-onnx` and `face-match-sdk` has Javadoc (the
compiler runs with `-Xdoclint:all/protected`). Generate the HTML with:

```bash
mvn -f liveness/pom.xml -q javadoc:javadoc      # output in liveness/<module>/target/reports/apidocs/index.html
```

Demo accounts for the mock server are listed in `docs/test-data.md` only.
