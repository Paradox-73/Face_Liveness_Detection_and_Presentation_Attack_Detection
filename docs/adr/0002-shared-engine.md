# 0002. One platform-neutral Java engine shared by both clients

- Status: Accepted
- Date: 2026-09-28

## Context

PS04 asks for "consistent liveness behaviour" and a "consistent Desktop and Android experience". The desktop client
is JavaFX on a bundled Zulu 11 runtime; the Android client is Flutter with native Java modules, `minSdk` 28. Two
separate implementations would drift: different thresholds, different challenge rules, different audit payloads.

## Decision

All liveness logic lives in one Java library, `liveness/liveness-core`, used unchanged by both clients:

- Java 11 bytecode (`maven.compiler.release=11`), zero runtime dependencies.
- Checked on every build against the Android API 28 signatures (`animal-sniffer-maven-plugin` with
  `com.toasttab.android:gummy-bears-api-28`).
- `ArchitectureTest` (ArchUnit) forbids JavaFX, Android, AWT, ImageIO, Swing, `java.net`, `java.sql`,
  `java.nio.file`, any non-JDK import, and cycles between sub-packages.
- Platform needs are interfaces the clients implement: `ImageDecoder`, `ConfigSource`, `AuditSink`,
  `DiagnosticSink`, `ModelSource`, `FaceCaptureDevice`.
- The model adapters (`liveness-onnx`) are a second shared module; the clients supply their own ONNX Runtime
  (desktop `onnxruntime`, Android `onnxruntime-android`), both with the same `ai.onnxruntime` API.
- Screens render only `LivenessUiState` with message keys from `MessageKeys`, so both clients show the same texts
  for the same states.
- The engine is behind an SPI (`LivenessEngineProvider`) so a vendor engine can replace it. Both clients load it by `mosip.registration.liveness.engine` through `ServiceLoader` (Android: `EngineLoader.fromConfig`; the default ID `mosip-onnx-default` uses the built-in engine).
- The attempt loop, worker thread, audit of the maximum-attempt event and the hand-off of the capture are one shared class, `LivenessSessionDriver` in `liveness-core`; each client supplies its device, engine, audit sink and capture handler.

## Consequences

- The same session state machine, thresholds, retry rules (`LivenessTransaction`), audit IDs and payloads
  (`AuditPayloadBuilder`) and packet evidence keys (`PacketEvidenceKeys`) run on both clients. The end-to-end
  reports show the same messages and states (`docs/test-report/android-e2e.md` section 7).
- The engine is testable on a plain JVM with scripted models and synthetic faces (365 deterministic tests in `liveness-core`, run 2026-10-05).
- Android consumes the engine from the local Maven repository, built from this repository by
  `scripts/android-build.sh`; the build must run Maven before Gradle.
- Java 8+ language features that D8 desugars are allowed, but main code avoids Java 9+ collection factories to
  keep the Android check simple.

## Alternatives considered

- **Kotlin Multiplatform or C++ core with bindings:** new toolchains in both clients; rejected for effort and
  review cost.
- **Per-platform engines (for example ML Kit on Android):** inconsistent behaviour; ML Kit is proprietary and sends
  metrics.
- **Server-side liveness:** violates the PS04 offline requirement.
