# 0003. Pin ONNX Runtime 1.20.0 on the desktop and on Android

- Status: Accepted
- Date: 2026-09-28; amended 2026-10-05 (Android moved from 1.28.0 to 1.20.0)

## Context

The five models (YuNet, Face Mesh, two MiniFASNet, SFace) are ONNX files. The desktop Registration Client ships
with a fixed runtime: Azul Zulu 11.0.8 with JavaFX (`zulu11.41.23-ca-fx-jre11.0.8-win_x64`), which MOSIP bundles and
which this project must not replace. Measured on the team laptop (Windows 11):

| ONNX Runtime Java | On Zulu 11.0.8 |
|---|---|
| 1.20.0 | loads and runs all five models |
| 1.21.x | `UnsatisfiedLinkError` when the native library loads |
| 1.30.x | JVM crash inside `msvcp140.dll` |

On Android, `onnxruntime-android` 1.29 and later merge `ai.onnxruntime.TelemetryInitializer` into the app manifest;
earlier releases declare no permission and no provider.

Until 2026-10-05 Android used 1.28.0, the newest release without the telemetry initializer. Two runtime releases
meant that the same model and the same frame could, in principle, give different scores on the two clients, and
the cross-client parity test (bonus B4) could not tell a client bug from a runtime difference.

## Decision

- Desktop: `com.microsoft.onnxruntime:onnxruntime:1.20.0` (`registration-services/pom.xml`, property
  `onnxruntime.version`; `liveness-onnx` uses it as `provided` for tests).
- Android: `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (`android/build.gradle`,
  `onnxruntimeAndroidVersion`), the same release as the desktop. Its native libraries are 16 KB page aligned
  (arm64-v8a and x86_64 checked), and its arm64-v8a `libonnxruntime.so` is 16 MB against 27 MB for 1.28.0.
- One release on both clients is checked by the parity test: `eval/interop/liveness-parity.json`, run by
  `LivenessParityTest` in the desktop `registration-services` and in Android `livenessmanager`
  ([../test-report/interoperability.md](../test-report/interoperability.md)). The Android unit tests run on the
  build machine's JVM with the JVM package `onnxruntime:1.20.0`; `LivenessParityDeviceTest` runs the committed
  cases on `onnxruntime-android` itself.
- Telemetry off in code: `OrtEnvironment.setTelemetry(false)` once per process.
- Android build guards: `:livenessmanager:verifyNoOnnxTelemetry` fails if any library manifest declares a telemetry
  initializer or ONNX Runtime asks for a permission; `OnnxRuntimeSupplyChainTest` checks the pinned version.
- Only the `io.mosip.liveness.onnx.runtime` package imports `ai.onnxruntime` (ArchUnit rule), so a later upgrade
  touches one package.

## Consequences

- Both clients use the same runtime release and Java API, so `liveness-onnx` is one code base and a score
  difference between the clients points at the client code (decoder, configuration), not at the runtime.
- Android also goes without the fixes of releases 1.21 to 1.28 until the desktop JRE moves; both pins are re-checked
  together.
- The desktop jar is 93 MB with natives for every platform; repackaging to win-x64 only (about 11.6 MB DLL) is not
  done yet.
- Security fixes in newer ONNX Runtime releases are not picked up until the desktop client moves to a newer JRE;
  re-check the pin whenever MOSIP changes the bundled runtime.
- Measured per-frame latency with 1.20.0 is 21-23 ms p50 on the desktop ([../design/engine.md](../design/engine.md)).

## Alternatives considered

- **OpenCV DNN** (`org.opencv.dnn`, already shipped through biometrics-util): kept as a fallback in case 1.20.0 did
  not load in the desktop JRE; not needed once 1.20.0 loaded.
- **LiteRT / MediaPipe on Android:** different API and scores on each platform; rejected for consistency.
- **Replacing the desktop JRE:** out of scope; MOSIP controls the client runtime.
