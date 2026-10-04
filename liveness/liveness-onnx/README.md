# liveness-onnx: ONNX Runtime adapters for the liveness core

Java 11 library that implements the model contracts of `liveness-core` with ONNX Runtime (ORT). The same jar runs
on the desktop registration client and on the Android registration client. Licence: MPL-2.0.

| Core contract | Implementation | Models |
|---|---|---|
| `FaceAnalyzer` | `io.mosip.liveness.onnx.OnnxFaceAnalyzer` | YuNet (faces and five landmarks); MediaPipe Face Mesh V2 (478 landmarks) |
| `PassivePad` | `io.mosip.liveness.onnx.pad.MiniFasPassivePad` | MiniFASNetV2 (crop scale 2.7) and MiniFASNetV1SE (crop scale 4.0) |
| `FaceEmbedder` | `io.mosip.liveness.onnx.embed.SFaceEmbedder` | SFace (128 values) |

The model files, their licences, hashes, input and output shapes, and exact preprocessing are listed in
[`models/SOURCES.md`](../../models/SOURCES.md).

## Constraints

- **Java level:** compiled with `--release 11`.
- **No platform classes:** no `java.awt`, ImageIO, JavaFX or Android classes, so the jar runs on both clients.
  - `ArchitectureTest` enforces this.
  - animal-sniffer checks every Java API call against the Android API 28 signatures (`gummy-bears-api-28`).
  - `Buffer.flip()` and similar calls go through `java.nio.Buffer`, because the Java 9+ overrides are missing on
    older Android runtimes.
- **ORT scope:** ORT is a `provided` dependency. Each client adds its own:
  - Desktop: `com.microsoft.onnxruntime:onnxruntime:1.20.0`. Newer versions fail on the Zulu 11.0.8 JRE the
    desktop client ships (1.21 gives `UnsatisfiedLinkError`, 1.30 crashes in `msvcp140.dll`).
  - Android: `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`, the same release as the desktop, so both
    clients run the models on one runtime version (docs/test-report/interoperability.md). Versions 1.29 and later
    would also add a telemetry provider and the `INTERNET` permission.
  - Both expose the same `ai.onnxruntime` API. The main sources also compile unchanged against the
    `onnxruntime-android` 1.20.0 `classes.jar`.
  - Only `io.mosip.liveness.onnx.runtime` touches `ai.onnxruntime` (checked by `ArchitectureTest`).
- **Telemetry:** switched off with `OrtEnvironment.setTelemetry(false)` when the first model loads.
- **Javadoc:** every public element has Javadoc; the compiler runs with `-Xlint:all -Xdoclint:all/protected`
  and reports no warnings.

## Packages

| Package | Contents |
|---|---|
| `io.mosip.liveness.onnx` | `OnnxFaceAnalyzer`; `OnnxModelSet`, which loads everything from a verified registry; `OnnxLivenessEngines` (engine factory), `OnnxLivenessEngine`, `OnnxLivenessEngineProvider` (service-loader entry) |
| `...onnx.model` | `ModelRegistry` (signed bundle, fail closed); `ModelManifest`, `ModelEntry`, `ManifestSignature` (detached RS256 JWS); `ModelTrustAnchors` (built-in public key); `ModelSources` (class path, directory); `SemanticVersion`, `ModelIds`, `Hex` |
| `...onnx.runtime` | `OrtModel` (session from verified bytes; float tensor in, named float arrays out); `OnnxRuntimeOptions` (threads, and a hook to add an execution provider such as XNNPACK) |
| `...onnx.image` | `Rgb8Image` (3-byte working image, zero-copy for RGB888/BGR888); `ImageOps` (bilinear resize, clamped crop, affine warp, grey, tensor normalisation); `AffineTransform` (with the five-point similarity estimate); `TensorFormat` (pinned preprocessing per model) |
| `...onnx.detect` | `YuNetFaceDetector` (decoding identical to OpenCV `FaceDetectorYN`; greedy NMS) |
| `...onnx.mesh` | `FaceMeshLandmarker`; `FaceMeasures` (eye aspect ratio, mouth ratio, gaze, inter-eye distance); `HeadPoseEstimator` |
| `...onnx.quality` | `FaceQualityMeter` (brightness, Laplacian-variance sharpness) |
| `...onnx.pad` | `MiniFasModel` (upstream crop and preprocessing); `MiniFasPassivePad` (ensemble) |
| `...onnx.embed` | `FaceAligner` (OpenCV 112x112 five-point template); `SFaceEmbedder` |
| `...onnx.tools` | `ModelSigner`, a command-line tool: `keygen`, `update`, `sign`, `verify`, `digest` |

Dependencies between sub-packages have no cycles (checked by `ArchitectureTest`).

## Usage

```java
// Desktop: the bundle is in liveness-onnx-<version>-models.jar on the class path.
// Android: ModelSource reading assets/models/.
ModelRegistry registry = ModelRegistry.builder(ModelSources.classpath(getClass().getClassLoader()))
        .minimumVersion(config.get(LivenessConfigKeys.MODEL_MIN_VERSION)
                .orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION))
        .open();                                   // checks manifest.jws and the minimum version
try (OnnxModelSet models = OnnxModelSet.load(registry, OnnxRuntimeOptions.defaults())) {
    FaceObservation obs = models.faceAnalyzer().analyze(image, previousSubjectBox);  // hint may be null
    DetectedFace subject = obs.primary().orElse(null);
    PadScore pad = models.passivePad().score(image, subject.box());
    FaceEmbedding e = models.faceEmbedder().embed(image, subject.box());
    List<ModelInfo> forAudit = models.models();    // id, version, SHA-256 per loaded model
}
```

- **Failures:** every failure is an `EngineException`.
  - `MODEL_MISSING`, `MODEL_INTEGRITY` and `MODEL_VERSION` mean the bundle failed verification; the engine audits
    `REG-EVT-LV14` and fails closed.
  - `MODEL_LOAD` means ORT itself is missing or could not load a model.
  - `INFERENCE` means a run failed.
- **Lighter set:** `OnnxModelSet.builder(registry).withMesh(false)` gives the detector-only fallback: head turns
  and looks, five-point pose, no blink, smile or gaze.
- **Per-frame choice:** `models.detectorOnlyAnalyzer()` shares the detector session but skips Face Mesh, for frames
  that need no eye, mouth or gaze measures.
- **Threading:**
  - Each model's `run` is synchronised, and each adapter reuses its tensor buffers under that lock, so the
    adapters may be shared between threads (calls are serialised).
  - Create one `OnnxModelSet` per process and `close()` it at shutdown.

### Ready engine: `OnnxLivenessEngines`

Clients normally do not wire the adapters themselves. `OnnxLivenessEngines` verifies the bundle, loads the models
and returns an `OnnxLivenessEngine` (a `DefaultLivenessEngine` with `OnnxFaceAnalyzer`, `MiniFasPassivePad` and
`SFaceEmbedder`, plus the ORT sessions it owns):

```java
OnnxLivenessEngine engine = OnnxLivenessEngines
        .builder(ModelSources.classpath(getClass().getClassLoader()))   // Android: ModelSource over assets
        .minimumVersion(config.get(LivenessConfigKeys.MODEL_MIN_VERSION)
                .orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION))
        .imageDecoder(clientImageDecoder)
        .build();                         // blocking (about 1-2 s): not on the UI thread
LivenessSession session = engine.open(request, listener);
engine.close();                           // at shutdown, after the last session
```

- The same engine is found through `ServiceLoader.load(LivenessEngineProvider.class)`:
  `OnnxLivenessEngineProvider`, engine id `mosip-onnx-default`, registered in
  `META-INF/services/io.mosip.liveness.core.api.LivenessEngineProvider`.
- Detection parameters: defaults, then the signed manifest's `engineParams` (`ModelManifest.engineParams()`), then
  `Builder.parameterOverrides(map)` for calibration runs. Invalid signed values fail with `MODEL_INTEGRITY`,
  invalid overrides with `CONFIG_INVALID`. The current bundle has no `engineParams`.
- Threads: `open` is safe from any thread. All sessions share the ORT sessions, whose calls are serialised, so
  parallel sessions are correct but take turns.
- Full option list: [../README.md](../README.md#creating-the-default-engine-liveness-onnx).

## Model bundle and key handling

- **Contents of `models/`:**
  - the model files;
  - `manifest.json`: per model, the id, file, version, SHA-256, size, licence, source and params;
  - `manifest.jws`: a detached RS256 JWS over the exact manifest bytes;
  - `model-signing-dev.pub.pem`.
- **Bundle version:** 1.3.0, re-signed on 2026-10-04 to add the signed engine parameter
  `integrity.loop_window_frames=300` (next to `quality.min_sharpness=40`). The default `model.min_version`
  (`LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION` and MockSync's `mock-sync-defaults.properties`) is also 1.3.0,
  so bundles signed earlier with the development key (1.0.0, 1.0.1, 1.1.0) are refused as rollbacks.
- **Load sequence (`ModelRegistry`):**
  1. Read `manifest.json` and `manifest.jws`.
  2. Verify the JWS against the trust anchors. Only `RS256` is accepted, `crit` is refused, and keys must have at
     least 2048 bits.
  3. Parse the manifest.
  4. Check `version >= model.min_version`, which blocks rollback.
  5. For each model: read the bytes, check the size and SHA-256, and only then create the ORT session from those
     same bytes. Model files are never read by path by ORT.
- **Trust anchor:**
  - The development public key (RSA 3072, `kid` `sha256:99538367853153c2`) is compiled into
    `ModelTrustAnchors.DEVELOPMENT_KEY_PEM`.
  - `ModelRegistryTest` checks that it equals `models/model-signing-dev.pub.pem`.
  - A deployment passes its own key with `ModelRegistry.Builder.trustAnchor(PublicKey)`. Once any key is passed,
    the development key is no longer trusted.
- **Private key:**
  - It never enters the repository. `ModelSigner keygen` refuses to write a private key inside a git working tree
    unless `--allow-in-repo` is given.
  - `models/.gitignore` ignores `*.private.pem`, `*.pk8` and `*.p12`.
  - The development private key was generated outside the repository. Whoever holds it re-signs after any
    manifest change. Anyone else generates their own pair and replaces the public key in `ModelTrustAnchors` and
    in `models/`.
- **Signer commands:** from `liveness/`, after `mvn -f liveness/pom.xml package`. On Git Bash, use `;` as the class
  path separator on Windows.

  ```sh
  CP="liveness-core/target/liveness-core-1.0.0-SNAPSHOT.jar;liveness-onnx/target/liveness-onnx-1.0.0-SNAPSHOT.jar"
  java -cp "$CP" io.mosip.liveness.onnx.tools.ModelSigner keygen --private ~/keys/model-signing.private.pem --public ../models/model-signing-dev.pub.pem
  java -cp "$CP" io.mosip.liveness.onnx.tools.ModelSigner update --bundle ../models
  java -cp "$CP" io.mosip.liveness.onnx.tools.ModelSigner sign   --bundle ../models --private ~/keys/model-signing.private.pem
  java -cp "$CP" io.mosip.liveness.onnx.tools.ModelSigner verify --bundle ../models
  ```

- **Packaging:** the build produces two jars.
  - `liveness-onnx-1.0.0-SNAPSHOT.jar`: classes only, about 105 KB.
  - `liveness-onnx-1.0.0-SNAPSHOT-models.jar`: the bundle under `io/mosip/liveness/onnx/models/`, about 23.6 MB.
    The desktop client puts both on its class path. The Android client uses the classes jar and copies `models/`
    to `assets/models/`.

## How the measures are computed

- **Face order:** faces are ranked by area, discounted by distance from the image centre. With a hint (the
  subject's previous box), the face with the highest IoU of at least 0.3 comes first, so the session keeps
  following the same person while still seeing every face.
- **Face Mesh region:**
  - A square of 1.5 times the longer side of the YuNet box, rotated so the eye line is horizontal (MediaPipe
    face landmarker).
  - Mesh measures are used only when the presence probability is at least 0.5; otherwise the five-point
    fallback applies.
- **Eye aspect ratio:** Soukupova and Cech 2016, on MediaPipe points.
  - Right eye: 33, 160, 158, 133, 153, 144.
  - Left eye: 362, 385, 387, 263, 373, 380.
  - About 0.29 for the open eyes of the MockMDS face.
- **Mouth width ratio:** distance between mouth corners 61 and 291, divided by the distance between the iris
  centres 468 and 473.
- **Gaze:** iris offset from the midpoint of the eye corners.
  - Horizontal: along the corner line, relative to half the eye width.
  - Vertical: perpendicular to it, relative to half the lid opening.
  - Averaged over both eyes and clamped to -1..1. Positive means the subject's own left, or up.
- **Head pose:** no camera model is needed.
  - The measured points are fitted to a reference 3-D face: 30 rigid points of the MediaPipe canonical face
    model (Apache-2.0), embedded in `CanonicalFace`.
  - Mesh: a 3-D least-squares rotation (Horn's quaternion method) on x, y and the mesh's relative depth z.
  - Five detector points only: a weak-perspective fit (linear least squares for the 2x3 projection, rows
    orthonormalised).
  - The rotation is split as `Ry(yaw) Rx(-pitch) Rz(-roll)`. Signs follow `HeadPose`: yaw is positive when the
    subject turns to their own left, pitch when looking up, roll when the head tilts to the left shoulder.
  - Synthetic tests recover known poses within 0.01° from the mesh and within 1° from the five points.
  - The image must not be mirrored.
- **Quality:**
  - The face box, clamped to the image, is resized to 128x128 grey (BT.601).
  - Brightness is the mean grey level. Sharpness is the variance of the 4-neighbour Laplacian.
  - The inter-eye distance comes from the iris centres, or from the YuNet eye points without a mesh.

## Build and test

```sh
. /e/Projects/MOSIP/toolchain/env.sh
mvn -f liveness/pom.xml verify                     # both modules; needs a full JDK 11+ (JDK 21 works)
mvn -f liveness/pom.xml -pl liveness-onnx verify   # this module only, against the installed liveness-core
mvn -f liveness/pom.xml -pl liveness-onnx test -Djacoco.skip=true -Dtest=PerformanceBenchmarkTest -Dliveness.benchmark.frames=500
```

- `PerformanceBenchmarkTest` only prints timings (100 frames by default); it asserts nothing about time, because
  other builds on a shared machine distort it. `-Dliveness.benchmark.frames=0` skips it.

- **Tests:** 79 JUnit 5 tests; coverage is reported in `target/site/jacoco/`.
  - Unit tests: image operations against OpenCV-equivalent values; the similarity estimate against the matrix
    OpenCV computes; JSON and manifest; semantic versions; signature and bundle tampering (tampered model or
    manifest, missing or foreign signature, `alg: none`, `crit`, rollback, unsafe paths); the signer tool;
    pose; eye, mouth and gaze measures; quality; MiniFASNet crop; NMS; architecture rules.
  - Golden tests (`OnnxGoldenTest`) run the real models on the MockMDS images in `src/test/resources/faces`.
    The expected values come from an independent Python pipeline (OpenCV 5.0 plus ORT 1.30).

- **Golden results** (Zulu 11.0.8, ORT 1.20.0, MockMDS stream frame 490x360):

  | Check | Result | Python reference |
  |---|---|---|
  | YuNet, full resolution | 1 face; box and landmarks within 1 px of the reference; score 0.92 | 192.08, 98.96, 95.27, 119.97; 0.918 |
  | Face Mesh | iris centres within 1.5 px; EAR right/left 0.289 / 0.292; mouth ratio 0.882; pose yaw -3.8°, pitch 0.0°, roll 1.2°; presence ≈ 1.0 | EAR 0.288 / 0.291; mouth 0.881 |
  | MiniFASNetV2 / V1SE live probability | 0.881 / 0.216; ensemble 0.548, argmax class 1 (live) | 0.889 / 0.220; 0.554 |
  | MiniFASNetV2 with `/255` input | class 2 at 0.993, live 0.007: attack, so the preprocessing is pinned | 0.993 |
  | SFace cosine, same image / same person / different person | 1.000 / 0.585 / 0.054 | 1.000 / 0.585 / 0.045 |

- **Latency** at 640x480 on this machine (Intel Alder Lake laptop, 12 logical CPUs; other builds were
  sometimes running at the same time). `PerformanceBenchmarkTest` with `-Djacoco.skip=true`, 60 warm-up frames,
  then 300 frames on Zulu 11.0.8 and 150 on JDK 21:

  | Stage (p50 / p95) | Zulu 11.0.8, 1 thread | Zulu 11.0.8, 2 threads | JDK 21, 2 threads |
  |---|---|---|---|
  | JPEG decode (ImageIO) | 4.0 / 5.6 ms | 6.3 / 7.0 ms | 3.9 / 5.0 ms |
  | Analyse (YuNet at 320, Face Mesh, quality) | 13.7 / 15.7 ms | 14.3 / 17.3 ms | 13.0 / 15.0 ms |
  | Passive PAD (both MiniFASNets) | 5.2 / 6.2 ms | 6.2 / 7.5 ms | 4.9 / 5.9 ms |
  | **Per frame, decode + analyse + PAD** | **23.1 / 26.3 ms** | 26.8 / 31.1 ms | 21.8 / 25.1 ms |
  | SFace (a few times per session) | 19.2 / 21.6 ms | 15.9 / 20.7 ms | 11.6 / 15.6 ms |

  - Raw ORT inference per call on Zulu 11.0.8, 1 thread: YuNet 320x256 2.1 ms, Face Mesh 8.7 ms, MiniFASNetV2
    2.3 ms, MiniFASNetV1SE 2.8 ms, SFace 18.5 ms.
  - YuNet at the full 640x480 input takes 14.7 ms, which is why the detector input is capped at 320 px.
  - A second intra-op thread did not help on this machine for these small models.
  - These numbers need a quiet machine. While other builds ran, the same test measured p50 104 ms and p95 291 ms
    per frame (JaCoCo on).
  - The desktop target (p95 of 20 ms or less including decode, test T-PERF-01 in `docs/traceability.md`) is **not
    met** when every model runs on every frame: p95 is 25-31 ms here.
  - The engine should skip models on some frames. Estimates from the stage times
    above (Zulu 11.0.8, 1 thread), not measurements:
    - PAD on every third frame: about 19-20 ms per frame on average.
    - Also skipping Face Mesh in the passive phase, with `OnnxModelSet.detectorOnlyAnalyzer()`: about 10 ms per
      frame.

## Design choices and open points

- **Trust anchor location:** the bundle's public key is built into `liveness-onnx` (`ModelTrustAnchors`), not
  into `liveness-core`, because the core has no crypto code.
  - Suggested core change: none required. The engine passes a configured key to `ModelRegistry.Builder`.
- **Signer location:** the signer is `io.mosip.liveness.onnx.tools.ModelSigner` in this module, not a separate
  `tools/model-signer` folder.
- **Model conversions:** Face Mesh and SFace ship with weights stored as float16 (`models/tools/fp16_weights.py`).
  - Face Mesh outputs are identical to the original's. SFace embeddings have cosine 0.999998 or more to the
    original's.
  - This keeps the bundle at 24.5 MiB (target about 25 MB) instead of 45.2 MiB with the original files.
  - The published int8 SFace was about 8 times slower on x86, so it was not used.
- **MiniFASNet score on the MockMDS image:** 0.548, not the 0.72 of the research notes of 2026-09-26. Those
  notes cut the crop at the image border; the upstream training crop shifts it instead. Both variants give
  class 1 (live).
  - As a score of 55, the MockMDS frame lies between the default `pad.reject_threshold` (20) and both passive
    thresholds (80 for resident workflows, 95 for authentication). Under the passive-to-active flow
    (`docs/design/decision-flow.md`) it would therefore go on to active challenges; the frozen-frame check should
    stop it anyway.
- **`FaceEmbedder` output:** embeddings are scaled to unit length, and the embedding model id is `sface@<version>`,
  so embeddings from different model versions are never compared.
- **`smileScore`:** not set. There is no blendshape model in the bundle, so SMILE uses `mouthWidthRatio`.
- **Image orientation:** images must be upright and not mirrored. A client that mirrors its preview mirrors only
  the display.
