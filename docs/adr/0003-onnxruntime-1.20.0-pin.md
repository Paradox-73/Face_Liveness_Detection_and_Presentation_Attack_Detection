# 0003. Pin ONNX Runtime 1.20.0 on the desktop and 1.28.0 on Android

- Status: Accepted
- Date: 2026-09-28

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
1.28.0 declares no permission and no provider.

## Decision

- Desktop: `com.microsoft.onnxruntime:onnxruntime:1.20.0` (`registration-services/pom.xml`, property
  `onnxruntime.version`; `liveness-onnx` uses it as `provided` for tests).
- Android: `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` (`android/build.gradle`,
  `onnxruntimeAndroidVersion`).
- Telemetry off in code: `OrtEnvironment.setTelemetry(false)` once per process.
- Android build guards: `:livenessmanager:verifyNoOnnxTelemetry` fails if any library manifest declares a telemetry
  initializer or ONNX Runtime asks for a permission; `OnnxRuntimeSupplyChainTest` checks the pinned version.
- Only the `io.mosip.liveness.onnx.runtime` package imports `ai.onnxruntime` (ArchUnit rule), so a later upgrade
  touches one package.

## Consequences

- Both runtimes share the same Java API, so `liveness-onnx` is one code base.
- The desktop jar is 93 MB with natives for every platform; repackaging to win-x64 only (about 11.6 MB DLL) is not
  done yet.
- Security fixes in newer ONNX Runtime releases are not picked up until the desktop client moves to a newer JRE;
  re-check the pin whenever MOSIP changes the bundled runtime.
- Measured per-frame latency with 1.20.0 is 21-23 ms p50 on the desktop ([../design/engine.md](../design/engine.md)).

## Alternatives considered

- **OpenCV DNN** (`org.opencv.dnn`, already shipped through biometrics-util): kept as fallback F2 in the plan; not
  needed once 1.20.0 loaded.
- **LiteRT / MediaPipe on Android:** different API and scores on each platform; rejected for consistency.
- **Replacing the desktop JRE:** out of scope; MOSIP controls the client runtime.
