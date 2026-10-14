# Liveness engine Java API

- **Artifacts:** `io.mosip.liveness:liveness-core:1.0.0-SNAPSHOT` (contract and default engine, no runtime
  dependencies) and `io.mosip.liveness:liveness-onnx:1.0.0-SNAPSHOT` (ONNX adapters and factory), plus
  `liveness-onnx:models` (classifier jar holding the signed model bundle). Build and install:
  `mvn -f liveness/pom.xml install -Dliveness.benchmark.frames=0`.
- **Runtime needs:** Java 11+ or Android API 28+; ONNX Runtime supplied by the client
  (`com.microsoft.onnxruntime:onnxruntime:1.20.0` or `onnxruntime-android:1.20.0`, the same release on both clients).
- **Authentication:** none; an in-process library. Trust comes from the signed model bundle
  (`ModelTrustAnchors`) and from the client's own device trust checks.
- **Threading:** one engine per process, `open()` from any thread; each session is used by one thread only.

Design background: [../design/engine-interface.md](../design/engine-interface.md).

## 1. Create the engine

### 1.1 Direct factory

```java
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.liveness.onnx.OnnxLivenessEngine;
import io.mosip.liveness.onnx.OnnxLivenessEngines;
import io.mosip.liveness.onnx.model.ModelSources;

OnnxLivenessEngine engine = OnnxLivenessEngines
        .builder(ModelSources.classpath(getClass().getClassLoader()))   // desktop: liveness-onnx-...-models.jar
        .minimumVersion(config.get(LivenessConfigKeys.MODEL_MIN_VERSION)
                              .orElse(PolicyKey.MODEL_MIN_VERSION.defaultValue()))
        .imageDecoder(clientImageDecoder)      // io.mosip.liveness.core.api.ImageDecoder (ImageIO / BitmapFactory)
        .build();                              // blocking, about 1-2 s: call it off the UI thread
```

Builder options: `trustAnchor(PublicKey)` (production signing key; default is the built-in development key),
`runtimeOptions(OnnxRuntimeOptions)`, `detectorOptions(YuNetFaceDetector.Options)`, `withEmbedder(false)` (no SFace:
no same-person or binding similarity checks), `parameterOverrides(Map)` (calibration and tests only),
`metrics(LivenessMetrics)`. Shortcuts: `OnnxLivenessEngines.create(ModelSource, ImageDecoder)`.
Android: `ModelSources.directory(File)` or the module's `AssetModelSource`.
At shutdown, after the last session, `engine.close()` closes the engine and its ONNX Runtime sessions.

### 1.2 Through the service-provider interface

```java
EngineContext context = EngineContext.builder()
        .config(configSource)                          // ConfigSource: key -> Optional<String>
        .models(ModelSources.classpath(loader))
        .imageDecoder(clientImageDecoder)
        .auditSink(auditSink).diagnosticSink(diagnosticSink)
        .build();
String wanted = configSource.get(LivenessConfigKeys.ENGINE).orElse(PolicyKey.ENGINE.defaultValue());
LivenessEngine engine = null;
for (LivenessEngineProvider p : ServiceLoader.load(LivenessEngineProvider.class)) {
    if (p.engineId().equals(wanted)) {
        engine = p.create(context);                    // OnnxLivenessEngineProvider: "mosip-onnx-default"
    }
}
```

`engine.info()` returns `mosip-onnx-default@1.0.0` (the engine ID and version; the model bundle is 1.3.0) with the five models (ID, version, SHA-256) and all eight
challenge types.

## 2. Run one attempt

```java
// 1. Policy for the workflow, from the synced configuration.
PolicyResolution resolution = new PolicyResolver(configSource).resolve(Workflow.REGISTRATION);
resolution.configAppliedEvent(Instant.now()).ifPresent(auditSink::emit);     // LV15 when keys changed or clamped
LivenessTransaction tx = new LivenessTransaction(resolution.policy());         // one per workflow and person

// 2. Session.
SessionRequest request = SessionRequest.builder(resolution.policy(), rid, device.describe())
        .attempt(tx.nextAttempt()).build();                                    // RandomSource.secure() by default
LivenessSession session = engine.open(request, listener);
session.start();

// 3. Frames: the device pushes into a latest-only hand-off; this worker thread owns the session.
FrameHandOff handOff = FrameHandOff.latestOnly();
FaceFrameSource source = device.openStream(StreamRequest.builder()
        .streamTimeoutMs(resolution.policy().streamTimeoutMs()).build());
source.start(handOff);
while (!session.state().isTerminal() && session.state() != SessionState.CAPTURE_READY && !handOff.isEnded()) {
    handOff.deliverNext(session, 100);   // at most 100 ms wait
    session.tick();
}

// 4. Signed capture and binding.
if (session.state() == SessionState.CAPTURE_READY) {
    LivenessPassToken token = session.passToken().get();
    session.captureStarted();
    SignedFaceCapture capture = device.capture(CaptureRequest.builder().timeoutMs(10_000).build());
    ImageBuffer face = imageDecoder.decode(isoFaceImageBytes(capture.isoRecord()));  // client-specific ISO decode
    BindingVerdict verdict = session.bind(BindingRequest.from(capture, face, token));
}

// 5. Result, evidence, retry decision.
LivenessResult result = session.result().get();
Map<String, String> evidence = PacketEvidenceKeys.of(result);   // into the BIR "others" map when it passed
LivenessTransaction.Status status = tx.record(result);           // then tx.canRetry(), isOverrideAllowed(), isLockRequired()
session.close();                                                 // wipes the kept frame
source.close();
```

Listener (called on the worker thread; forward to the UI thread yourself):

```java
LivenessListener listener = new LivenessListener() {
    @Override public void onUi(LivenessUiState s)        { screen.render(s); }
    @Override public void onAudit(LivenessAuditEvent e)  { auditSink.emit(e); }
    @Override public void onStateChanged(SessionState from, SessionState to) { /* e.g. start capture at CAPTURE_READY */ }
    @Override public void onResult(LivenessResult r)     { /* final result */ }
};
```

### 2.1 The shared session driver (what both clients use)

The loop above is what `io.mosip.liveness.core.run.LivenessSessionDriver` does for the desktop and the Android client:
attempts in one `LivenessTransaction`, the worker thread, retries, the `REG-EVT-LV11` maximum-attempt event, the capture
hand-off and the errors before a session opens. A client builds it with `LivenessSessionDriver.builder(policy, subjectRef)`,
supplying `.engine(...)`, `.device(...)`, `.audit(...)`, a `Listener` (`onUiState`, `onAttemptEnded(AttemptOutcome)`) and a
`CaptureHandler`, and continues earlier windows with `.priorFailures(counted, suspectedAttack, lowLightRetries)`.
`.trackLowLight(true)` turns on the dim-light ending (`QUALITY` / `LOW_LIGHT`, not counted, up to
`passive.low_light_max_uncounted`, 3, then it counts); both clients and the scenario runner turn it on.
`AttemptOutcome.isSuspectedAttack()` and `lowLightRetries()` are what a client stores for the next window. Full API:
`liveness/README.md`, section on the session driver.

## 3. What comes back

**`LivenessUiState`** (what the screen draws); accessor values during the first of two challenges:

```text
phase=CHALLENGE, messageKey=liveness.challenge.smile, args=[], guidanceKeys=[],
promptIcon=SMILE, stepIndex=1, stepCount=2, progress=0.5, attempt=1, maxAttempts=3,
retryAvailable=false, overrideAvailable=false, severity=INFO
```

**`LivenessResult`** accessors: `isPass()`, `failureReason()` (`Optional<FailureReason>`), `method()`
(`PASSIVE`, `PASSIVE_ACTIVE`, `ACTIVE`, `NONE`), `reasonCodes()`, `bindingSimilarity()`, `passiveMs()`,
`totalMs()`, `engine()`, `engineError()`, `attempt()`, `sessionId()`, `workflow()`.

**`PacketEvidenceKeys.of(result)`** for a resident who answered two challenges (real packet from the desktop
end-to-end run):

```json
{"LIVENESS_DECISION":"PASS","LIVENESS_METHOD":"PASSIVE_ACTIVE","LIVENESS_SCORE":"74",
 "LIVENESS_CHALLENGES":"SMILE:OK,BLINK:OK","LIVENESS_ENGINE":"mosip-onnx-default@1.0.0",
 "LIVENESS_MODEL_SHA":"ebafce4e,db4b7684,b32929ad,ebab7f90,d2899b3e",
 "LIVENESS_SESSION":"f2bfc93f5552bf822eea793b67b9487f","LIVENESS_BINDING":"90-100","LIVENESS_DEVICE_LEVEL":"L0"}
```

**`LivenessAuditEvent.descriptionJson()`**, for example LV01:

```json
{"workflow":"registration","sessionId":"f2bfc93f5552bf822eea793b67b9487f","attempt":"1",
 "deviceSerial":"2345678901","certification":"L0","engine":"mosip-onnx-default@1.0.0"}
```

## 4. Errors

| Where | Type | Codes |
|---|---|---|
| `OnnxLivenessEngines.build()`, `LivenessEngineProvider.create()`, `LivenessEngine.open()` | `io.mosip.liveness.core.EngineException` with `EngineErrorCode` | `MODEL_MISSING`, `MODEL_INTEGRITY`, `MODEL_VERSION` (bundle rejected: audit LV14 and fail closed), `MODEL_LOAD`, `CONFIG_INVALID`, `NOT_READY` (open while loading) |
| Inside a session | never thrown; the session ends `FAILED` with a `FailureReason` and `ReasonCode`s | see [../design/errors.md](../design/errors.md) |
| Calls in the wrong state | ignored (no exception) | — |
| Device adapter | `DeviceException` with `DeviceErrorCode` | see [device-adapter.md](device-adapter.md) |

## 5. Use case: plug in a vendor PAD engine

A vendor has a certified passive PAD model and wants to reuse the session, challenges, binding and audits. It
implements `PassivePad` and registers a provider:

```java
package com.example.pad;

public final class AcmeEngineProvider implements LivenessEngineProvider {
    @Override public String engineId() { return "acme-pad"; }

    @Override public LivenessEngine create(EngineContext ctx) throws EngineException {
        OnnxModelSet open = OnnxModelSet.load(ModelRegistry.open(ctx.models()), OnnxRuntimeOptions.defaults());
        PassivePad acme = (image, face) -> PadScore.of(AcmeSdk.liveProbability(image, face));   // 0..1
        EngineInfo info = EngineInfo.builder("acme-pad", "2.1.0").vendor("Acme")
                .supportedChallenges(EnumSet.allOf(ChallengeType.class)).requiresStream(true).build();
        return DefaultLivenessEngine.builder(info)
                .faceAnalyzer(open.faceAnalyzer())       // YuNet + Face Mesh from the signed bundle
                .passivePad(acme)                         // the vendor's PAD instead of MiniFASNet
                .faceEmbedder(open.faceEmbedder())        // SFace for identity and binding checks
                .imageDecoder(ctx.imageDecoder())
                .parameters(EngineParameters.defaults())
                .build();
    }
}
```

Then:
1. List the class in `META-INF/services/io.mosip.liveness.core.api.LivenessEngineProvider` inside the vendor jar.
2. Put the jar on the client class path (desktop `lib/`, Android module dependency).
3. Sync `mosip.registration.liveness.engine=acme-pad`. Sessions, screens, audits (`LIVENESS_ENGINE=acme-pad@2.1.0`)
   and packets work unchanged.

(`AcmeSdk` is a placeholder for the vendor's own code; the rest are real classes of `liveness-core` and
`liveness-onnx`. A production provider must also close the ONNX sessions it opened when the engine closes, as
`OnnxLivenessEngine` does.)

## 6. Tests to copy from

- `liveness/liveness-core/src/test/java/io/mosip/liveness/core/engine/SessionHarness.java`: drives a session with
  a fake clock, scripted models and synthetic faces.
- `liveness/liveness-onnx/src/test/java/io/mosip/liveness/onnx/OnnxLivenessEnginesTest.java`: builds the real engine
  from the bundle and runs a session.
- `tools/eval/scenario-runner`: headless runner that plays frame packs through the real engine.
