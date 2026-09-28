# liveness: shared face liveness engine

Java 11 library used by both MOSIP registration clients (desktop JavaFX client and Android client) for face
liveness and presentation attack detection (PAD). Licence: MIT.

| Module | Contents | Status |
|---|---|---|
| `liveness-core` | Public API, value types, configuration keys, audit IDs, UI state and message keys, plus the platform-neutral engine (session state machine, passive and active checks, frame integrity, planarity, capture binding, policy resolution, MJPEG reader). No runtime dependencies. | API and engine logic implemented; detection thresholds still to be calibrated on real frames |
| `liveness-onnx` | ONNX Runtime adapters (YuNet, Face Mesh, MiniFASNet, SFace) and the signed model bundle loader | not yet created |
| `face-match-sdk` | SFace-based `IBioApiV2` implementation | not yet created |

## Constraints on `liveness-core`

- Compiled for Java 11 (`maven.compiler.release=11`).
- Zero runtime dependencies. The jar is copied into the Android client as it is.
- Must run on Android API 28 (the Android client's `minSdk`). Two checks enforce this on every build:
  - `animal-sniffer-maven-plugin` checks every API call against the Android API 28 signatures
    (`com.toasttab.android:gummy-bears-api-28`). The check fails for APIs that Android 28 lacks
    (for example `ProcessHandle`).
  - `ArchitectureTest` (ArchUnit) forbids `javafx`, `android`, `java.awt`, `javax.imageio`, `javax.swing`,
    `java.net`, `java.sql` and `java.nio.file`, any non-JDK dependency, and cycles between sub-packages.
- Every public type and member has Javadoc; the compiler runs with `-Xdoclint:all/protected`.
- The jar is reproducible (`project.build.outputTimestamp`), so the Android build can pin it by SHA-256.

## Packages

| Package | What it holds |
|---|---|
| `io.mosip.liveness.core` | Shared vocabulary: `Workflow`, `ChallengeType`, `SessionState`, `Decision`, `FailureReason`, `ReasonCode`, `Clock`, `RandomSource`, `EngineException` / `EngineErrorCode` |
| `...core.api` | Engine and session contract: `LivenessEngine`, `LivenessEngineProvider`, `EngineContext`, `ModelSource`, `ImageDecoder`, `EngineInfo`, `ModelInfo`, `SessionRequest`, `LivenessSession`, `LivenessListener`, `LivenessPassToken`, `BindingRequest`, `BindingVerdict`, `LivenessResult`, `ChallengeRecord`, `PacketEvidenceKeys` |
| `...core.device` | Device adapter contract: `FaceCaptureDevice`, `FaceFrameSource`, `FrameSink`, `FaceFrame`, `StreamRequest`, `StreamStats`, `CaptureRequest`, `PadRequest`, `SignedFaceCapture`, `DeviceDescriptor`, `CertificationLevel`, `DeviceStatus`, `DeviceException` / `DeviceError` / `DeviceErrorCode`, `EndReason` |
| `...core.image` | `ImageBuffer` (decoded pixels, byte or int backed) and `PixelFormat` |
| `...core.vision` | Model adapter contract: `FaceAnalyzer` -> `FaceObservation` / `DetectedFace` (box, 5 landmarks, optional 478-point mesh, head pose, eye aspect ratio per eye, mouth ratio, gaze, quality), `PassivePad` -> `PadScore`, `FaceEmbedder` -> `FaceEmbedding` |
| `...core.config` | `LivenessConfigKeys` (every key, default, floor and range), `LivenessPolicy` (typed per-workflow policy), `PolicyMode`, `OnMaxFailure`, `NoStreamPolicy`, `ConfigSource` |
| `...core.audit` | `AuditEventId` (`REG-EVT-LV01..17`), `LivenessAuditEvent` (payload serialised to identical JSON on both clients), `AuditAttributes`, `AuditSink`, `DiagnosticRecord`, `DiagnosticSink` |
| `...core.ui` | `LivenessUiState` (what both clients render), `UiPhase`, `Severity`, `PromptIcon`, `MessageKeys` (the 40 message keys) |
| `...core.engine` | The default engine. Public: `DefaultLivenessEngine` (builds sessions from injected model adapters), `EngineParameters` / `EngineParameter` (detection thresholds), `LivenessTransaction` (retry counting), `LivenessUiStateMapper`, `AuditPayloadBuilder`, `LivenessMetrics`, `DiagnosticLog`. Package-private: the session state machine and every algorithm (`QualityGate`, `PassivePadAggregator`, `DecisionPolicy`, `ChallengeScheduler`, `ChallengeObserver`, `FrameIntegrityChecker`, `PlanarityChecker`, `IdentityTracker`, `CaptureBinder`) |
| `...core.stream` | `MjpegFrameReader` (SBI MJPEG stream to frames), `FrameHandOff` (bounded reader-to-worker hand-off that drops the oldest frame), `FrameRateMeter` |
| `...core.policy` | `PolicyResolver` (configuration keys to `LivenessPolicy`, with defaults, per-workflow overrides, floors and fallbacks), `PolicyResolution`, `ConfigIssue` |

The contract packages (root, `api`, `config`, `audit`, `ui`, `vision`, `device`, `image`) never depend on
`engine`, `stream` or `policy`, so a vendor engine can replace the default one; `ArchitectureTest` checks this.

## How a client uses it

1. At start-up, find the engine: `ServiceLoader.load(LivenessEngineProvider.class)`, pick the one whose
   `engineId()` equals `mosip.registration.liveness.engine`, and call `create(EngineContext)` with the client's
   `ConfigSource`, `ModelSource`, `ImageDecoder`, `AuditSink` and `DiagnosticSink`.
2. Per attempt, resolve the policy with `new PolicyResolver(config).resolve(workflow).policy()`, keep one
   `LivenessTransaction` per workflow and subject, and open a session:
   `engine.open(SessionRequest.builder(policy, subjectRef, device.describe()).attempt(tx.nextAttempt()).build(), listener)`.
3. `session.start()`, then open the device stream and pass frames to the session on one worker thread through a
   `FrameHandOff.latestOnly()` (it keeps only the newest frame and drops the rest). On the desktop the stream is
   read with `MjpegFrameReader` on the reader thread. Call `session.tick()` at least every 100 ms (the loop in
   the `FrameHandOff` Javadoc does both).
4. Render every `LivenessUiState` from `listener.onUi`; store every `LivenessAuditEvent` from `listener.onAudit`.
5. When `listener.onStateChanged(..., CAPTURE_READY)` fires, read `session.passToken()`, call
   `session.captureStarted()` and take the signed capture (with a `PadRequest` carrying the token nonce when the
   device has `padCapability`).
6. Decode the face image of the capture and call `session.bind(BindingRequest.from(capture, image, token))`.
7. Read `session.result()`; write `PacketEvidenceKeys.of(result)` into the BIR `others` map;
   `tx.record(result)` decides between Retry, the supervisor override and the lock; `session.close()`.

Callers never choose challenges: the session draws them with `RandomSource.secure()`.

## Conventions

- Units: durations in milliseconds, scores and similarity 0-100, probabilities 0-1, sizes in pixels, angles in
  degrees.
- Left and right always mean the subject's own. `HeadPose` yaw is positive when the subject turns to their own
  left, pitch is positive when looking up.
- Privacy: no type can put images, embeddings or raw scores into an audit. `toString()` of frames, images,
  embeddings, tokens and captures never prints pixel data, vectors, nonces or subject references.
- Pixel arrays in `FaceFrame`, `ImageBuffer` and `SignedFaceCapture` are shared, not copied, for speed; the
  owner may call `wipe()` at session end.

## Engine algorithms (`io.mosip.liveness.core.engine`)

The engine runs on top of three injected model adapters (`FaceAnalyzer`, `PassivePad`, optional `FaceEmbedder`)
and the client's `ImageDecoder`. `liveness-onnx` supplies the adapters; tests supply scripted ones. Every step
uses the session `Clock` and `RandomSource`, so a session is fully repeatable in tests.

### Session state machine (design section 4.5)

| State | What happens | Leaves when |
|---|---|---|
| `IDLE` | created | `start()`: LV01 audit; policy `OFF` or a non-streaming device with `CAPTURE_ONLY` go straight to `CAPTURE_READY`; a non-streaming device with `BLOCK` fails `DEVICE/NO_STREAM` |
| `OPENING` | waits for the first decodable frame | first frame; no frame in 3 s: `FAILED(DEVICE, FIRST_FRAME_TIMEOUT)` |
| `ACQUIRING` | quality gate on every frame; PAD scored on good frames | 5 good frames in a row; `passive.timeout_ms` from the first frame: `FAILED(QUALITY)` with `PASSIVE_TIMEOUT` and the last problem (`NO_FACE`, `MULTI_FACE`, ...) |
| `PASSIVE` | collects `passive.min_frames` PAD scores (the acquisition frames count) | decision below; same timeout |
| `CHALLENGE_WAIT` | random wait U(400,1500) ms, then a neutral face held 300 ms; same-person check on that frame | prompt; no neutral face within the wait + `challenge_timeout_ms`: `FAILED(TIMEOUT, NEUTRAL_TIMEOUT)` |
| `CHALLENGE_OBSERVE` | prompt shown (LV04 with a SHA-256 prefix of a fresh nonce); action detection; PAD every 2nd frame | pass: LV05; failure: LV06 (and LV07 for a timeout) |
| `CHALLENGE_PASSED` | "Action detected" for 600 ms | next challenge, or the final checks |
| `CAPTURE_READY` | pass token issued; frames are ignored | `captureStarted()`; `captureFailed()` or token expiry: `FAILED(DEVICE)` |
| `CAPTURING` / `BINDING` | waits for `bind()`, then runs the capture binder | `PASSED` (LV10) or `FAILED(BINDING or PAD)` (LV09, plus LV08 for PAD) |

In every observing state: no frame for 2 s fails `DEVICE/STALLED`; an integrity violation fails `INTEGRITY`
at once (LV08); 3 analyses in a row slower than 500 ms fail `ENGINE/INFERENCE_TIMEOUT`; an adapter exception
fails `ENGINE` (LV13, plus LV14 for model-integrity codes). The session only moves forward. A counted failure on
the last attempt also writes LV11. `cancel()` works from any thread and takes effect at the next call on the
owning thread (at once when called on it); `close()` cancels and overwrites the kept capture frame.

### Passive stage (sections 4.5 and 4.6)

- **Quality gate** (`QualityGate`), first failed check wins, missing measures are skipped: face count 0
  (`NO_FACE`) or more than 1 (`MULTI_FACE`); detector confidence (`POOR_QUALITY`); box more than 10% outside the
  image (`FACE_OUT_OF_AREA`); inter-eye distance below `quality.min_ied_px` (`FACE_TOO_SMALL`); yaw above
  `quality.max_yaw_deg`, pitch above 20 or roll above 25 degrees (`OFF_POSE`); mean grey level below 50
  (`TOO_DARK`) or above 225 (`TOO_BRIGHT`); sharpness below the minimum (`BLURRY`). Each code maps to one of the
  PS04 guidance messages.
- **Score** (`PassivePadAggregator`): `S = round(100 * median(p))` over the last `passive.min_frames` live
  probabilities; a trim fraction below 0.5 gives a trimmed mean instead.
- **Decision** (`DecisionPolicy`, table-tested at threshold - 1, threshold and threshold + 1):
  `S < pad.reject_threshold` fails `PAD`; `PASSIVE_ONLY` passes at `S >= passive.threshold`, else
  `BELOW_THRESHOLD`; `PASSIVE_THEN_ACTIVE` passes at the threshold, else escalates (LV03 `BELOW_THRESHOLD`) when
  `active.enable=Y` and at least one challenge can run on this stream, else `BELOW_THRESHOLD`; `ALWAYS_ACTIVE`
  always escalates (LV03 `POLICY`). A passive pass writes LV02.

### Active stage (section 4.7)

- **Draw** (`ChallengeScheduler`, package-private): the pool is `active.challenge_types` minus types the
  analyser cannot measure, BLINK below 10 analysed fps, GAZE below 60 px inter-eye distance, and types the engine
  does not list. `min_challenges` types are drawn with no immediate repeat; with planarity on and at least 5
  analysed fps the sequence contains a head turn. Sequences are drawn uniformly from the no-repeat sequences and
  rejected until they meet the head-turn rule, so all allowed sequences are equally likely (chi-squared test over
  the 14 sequences of the default pool with k = 2; 68 sequences with k = 3).
- **Action level** (`ActionLevels`) per frame, relative to the mean of the neutral frames before the prompt, 1.0
  at the trigger: TURN_LEFT / TURN_RIGHT = yaw change / 20 degrees (positive yaw is the subject's own left, so a
  mirrored preview does not flip it); LOOK_UP / LOOK_DOWN = pitch change / 15 degrees; SMILE = relative mouth-width
  increase / 12% (or smile probability / 0.5); BLINK = eye-aspect-ratio drop / 35% of the neutral value;
  GAZE_* = horizontal gaze / 0.35.
- **Rules** (`ChallengeObserver`): the uninterrupted run above half the trigger that leads to the trigger must
  start at least `min_onset_ms` after the prompt (`ONSET_TOO_FAST`); the action must stay at the trigger for
  `hold_ms` and 2 frames; a blink must close and reopen within 80-500 ms; any other detectable action at its
  trigger on 2 frames in a row fails with `WRONG_ACTION`; no success within `challenge_timeout_ms` fails with
  `TIMEOUT`, or `NOT_HELD` when the action reached its trigger but was never held.
- **Continuous PAD**: every 2nd analysed frame; the median of the last `passive.min_frames` scores below the
  reject threshold fails `PAD` at once.
- **Final checks** after the last challenge: median PAD over the whole active phase at least the reject
  threshold; every head turn's planarity residual at least the minimum; the chosen capture frame is still the
  reference person.

### Frame integrity and planarity (section 4.8)

- `FrameIntegrityChecker`: sequence number or timestamp not increasing (`TIMESTAMP_REGRESSION`); source frame
  rate (from sequence numbers, so frames dropped before the session count) below 90% of `integrity.min_fps` after
  2 s (`INVALID_FRAMES`); 10 undecodable frames in a row (`INVALID_FRAMES`); more than
  `integrity.max_frozen_frames` identical frames in a row, identical meaning equal SHA-256 of the JPEG bytes or a
  mean absolute difference below 0.5 grey levels between point-sampled 64x64 thumbnails (`FROZEN`); the last 6
  frames repeating an earlier run of the last 30 by 64-bit difference hash, with real motion inside the run
  (`LOOP`).
- `FaceTracker`: consecutive boxes must overlap (IoU at least 0.3) and be at most 500 ms apart (2.5 frame
  intervals on slower streams). A broken track restarts score collection in the passive phase; during challenges
  it triggers a same-person check (`FACE_SWITCH` when similarity drops below `binding.min_similarity`), or fails
  `TRACKING_LOST` when there is no embedder.
- `IdentityTracker`: the reference is the mean unit embedding of 3 neutral passive frames; similarity is
  `round(100 * max(0, cosine))`. It is checked before each prompt, after a broken track, at the final check and
  on the signed capture.
- `PlanarityChecker`: on each head turn, the rigid landmarks of the last neutral frame are mapped onto the most
  turned frame with a least-squares homography (31 Face Mesh points) or an affine map (the 5 detector points).
  Residual RMS divided by the inter-eye distance below `planarity.min_residual` fails `PLANAR`. On the synthetic
  head model in the tests, 15-35 degree turns give 0.042-0.107 for a 3-D head and 0.000-0.014 for a flat one
  (measured; real-camera values are still to be calibrated).

### Capture binding (section 4.10)

`CaptureBinder` checks, and reports every failed check: token present, unused, equal to the one issued (session,
workflow, subject, device, nonce, times) and not expired; capture signed by the streaming device; echo of the
token nonce for `padCapability` devices; capture time within
`[start - 2 s, start + binding.max_gap_ms + capture timeout]`, where start is the pass for resident workflows and
the capture start (after device Ready) for authentication workflows; no device PAD FAIL; exactly one face in the
captured image; similarity at least `binding.min_similarity`; capture PAD score at least the reject threshold.
Device PAD or capture PAD failures fail as `PAD`, everything else as `BINDING`. Image checks are skipped for
policy `OFF` and `CAPTURE_ONLY`.

### UI, audit, metrics and diagnostics

- `LivenessUiStateMapper` implements the section 11.2 rules: PAD, integrity and binding failures show
  `liveness.fail.pad`; below-threshold, challenge and timeout failures show `liveness.fail.liveness` plus guidance
  from the quality problems seen; the last counted attempt shows `liveness.retry.max_resident` (with the override
  flag only for `SUPERVISOR_OVERRIDE` and a non-attack failure) or `liveness.retry.max_auth`.
  `resolveText` applies a synced `guidance.<messageKey>.<lang>` text before the bundle text.
  **Convention:** when Retry is offered, `liveness.retry.available` appears as a guidance key and clients format
  it with the state's `attempt` and `maxAttempts`.
- The session writes LV01-LV13 itself through `LivenessListener.onAudit`. Clients build LV15 (config applied,
  `PolicyResolution.configAppliedEvent`), LV16 (override) and LV17 (diagnostic mode) with `AuditPayloadBuilder`.
  Payloads carry buckets, reason codes, types and timings only.
- `LivenessMetrics` (when `metrics.enable=Y`): counters for sessions, retries, passes, passive passes, active
  triggers, failures per reason, completed challenges and analysed frames; mean total, passive, challenge and
  per-frame processing times; retry and failure rates. Names are fixed strings plus enum names.
- `DiagnosticLog` (when `diagnostic.enable=Y`): one `DiagnosticRecord` per state change with PAD score, flags,
  planarity residual, similarity, fps and model versions; never subject references, images or embeddings.

### MJPEG reader (`io.mosip.liveness.core.stream`)

`MjpegFrameReader` parses `multipart/x-mixed-replace` as MockMDS writes it (`--BoundaryString`,
`Content-Length:N` without a space) and also standard forms (HTTP envelope with `boundary=`, headers in any case,
parts without `Content-Length`). Invalid parts (not a complete JPEG, wrong type, wrong or unparsable length, over
4 MiB) are counted and skipped; after an over-declared length it resynchronises on the boundary inside the bytes
it read. It makes exactly one terminal call: `END_OF_STREAM` at end of stream (no busy loop, unlike the stock
`Streamer`), `CLOSED` after `stop()`, `STREAM_TIMEOUT`, or an error `STALLED` (read timeout), `DISCONNECTED`
(I/O error), `FIRST_FRAME_TIMEOUT` (no valid frame in 3 s) or `INVALID_STREAM` (25 invalid parts in a row).

## Tunable parameters

Operator settings are the section 4.16 keys, resolved by `PolicyResolver`: per-workflow key, else global key,
else the workflow default. Out-of-range numbers are clamped (floors: passive threshold 50, reject threshold 5)
and reported for LV15; unparsable values fall back to the default and are reported as diagnostics; `enable=N`
turns every workflow `OFF`; the reject threshold never exceeds the passive threshold. Ranges applied:
`passive.min_frames` 1-60, `passive.timeout_ms` 1,000-60,000, `min_challenges` 1-5, `challenge_timeout_ms`
2,000-15,000, `min_onset_ms` 0-2,000, `hold_ms` 0-3,000, `max_retries` 1-10, `binding.max_gap_ms` 500-120,000,
`stream.timeout_ms` 1,000-600,000, `quality.max_yaw_deg` 0-90, `integrity.max_frozen_frames` 1-100,
`integrity.min_fps` 1-60.

Detection thresholds are not operator settings: they are `EngineParameter`s, which a model bundle may set
through its signed `engineParams` (`EngineParameters.fromMap`; unknown keys and out-of-range values are
rejected). Defaults:

| Key | Default | Meaning |
|---|---|---|
| `acquire.consecutive_frames` | 5 | good frames in a row that end acquisition |
| `quality.min_face_confidence` / `max_pitch_deg` / `max_roll_deg` | 0.5 / 20 / 25 | acquisition gate |
| `quality.min_brightness` / `max_brightness` / `min_sharpness` | 50 / 225 / 10 | acquisition gate (sharpness to calibrate per analyser) |
| `quality.out_of_area_tolerance` | 0.1 | share of the box allowed outside the image |
| `passive.trim_fraction` | 0.5 | 0.5 = median; lower = trimmed mean |
| `identity.reference_frames` | 3 | embeddings in the reference face |
| `active.delay_min_ms` / `delay_max_ms` | 400 / 1,500 | random wait before a prompt |
| `active.neutral_hold_ms` / `neutral_max_yaw_deg` / `neutral_max_pitch_deg` | 300 / 10 / 12 | neutral face before a prompt |
| `active.passed_display_ms` | 600 | "Action detected" time |
| `active.turn_yaw_deg` / `look_pitch_deg` | 20 / 15 | head-turn and look triggers |
| `active.onset_fraction` | 0.5 | share of the trigger that starts the onset run |
| `active.blink_close_ratio` / `blink_open_ratio` | 0.65 / 0.85 | closed / reopened eye aspect ratio, as a share of neutral |
| `active.blink_min_closed_ms` / `blink_max_closed_ms` | 80 / 500 | blink duration |
| `active.eyes_open_min_ear` | 0.15 | eyes-open check of the neutral face |
| `active.smile_ratio_increase` / `smile_score_threshold` | 0.12 / 0.5 | smile triggers (to calibrate) |
| `active.gaze_threshold` | 0.35 | gaze trigger |
| `active.contrary_min_frames` | 2 | frames of a non-requested action that fail a challenge |
| `active.pad_stride` | 2 | PAD on every n-th frame during challenges |
| `active.head_turn_min_fps` | 5 | analysed fps from which a head turn is required |
| `integrity.frozen_mean_abs_diff` | 0.5 | identical-frame limit in grey levels (to calibrate) |
| `integrity.loop_window_frames` / `loop_match_frames` / `loop_hash_max_distance` / `loop_min_motion_bits` | 30 / 6 / 3 / 12 | loop detection |
| `integrity.tracking_min_iou` / `tracking_max_gap_ms` | 0.3 / 500 | face tracking |
| `integrity.fps_window_ms` / `fps_tolerance` / `max_corrupt_frames` | 2,000 / 0.1 / 10 | frame-rate and decode checks |
| `device.no_frame_timeout_ms` / `first_frame_timeout_ms` | 2,000 / 3,000 | stream watchdogs |
| `engine.inference_slow_ms` / `inference_slow_count` | 500 / 3 | inference watchdog |
| `planarity.enabled` / `planarity.min_residual` | 1 / 0.02 | planarity check (residual to calibrate) |
| `binding.capture_timeout_ms` / `clock_skew_ms` / `capture_frontal_max_deg` | 10,000 / 2,000 / 5 | token lifetime, device clock tolerance, stored-frame pose |

## Tests

JUnit 5, deterministic: a manual clock, a seeded random source (test code only), scripted model outputs and
synthetic faces from a small 3-D head model (no face images). 264 tests; JaCoCo line coverage of
`liveness-core` 93.2% (branches 79.3%). They cover the design's test groups T-CORE-SM (state machine), DEC
(decision table), CH (draw rules and uniformity), ACT (detectors, including the sign convention), INT, PLN, Q,
MJ, POL, UI, TX, MET, AUD and BND, plus end-to-end scenarios (bona fide passive pass, escalation and correct
actions, wrong action, timeout, flat-photo head turn, frozen stream, maximum retries).

## Build

```sh
mvn -f liveness/pom.xml verify           # compile, Android API 28 check, tests, JaCoCo report
mvn -f liveness/pom.xml install          # also installs io.mosip.liveness:liveness-core:1.0.0-SNAPSHOT locally
mvn -f liveness/pom.xml -pl liveness-core verify   # the core only
```

- Needs a full JDK 11 or newer and Maven 3.9. With JDK 12 or newer, `--release 11` keeps the Java 11 API.
- Output: `liveness-core/target/liveness-core-1.0.0-SNAPSHOT.jar`; coverage report in
  `liveness-core/target/site/jacoco/index.html`.
- Tests use JUnit 5 and ArchUnit (test scope only).

## Differences from the design document

- `ImageDecoder` (not named in the design) is a core interface, because the core cannot decode JPEG or PNG
  without ImageIO or Android classes; each client implements it.
- `FaceCaptureDevice.openStream` throws `DeviceException` with `NO_STREAM` instead of returning a
  `NoStreamSource`.
- `FaceEmbedder.embed` returns a `FaceEmbedding` (vector plus model ID) instead of a bare `float[]`, so
  embeddings of different models cannot be compared by mistake.
- `LivenessSession` adds `start()`, `tick()`, `captureStarted()`, `captureFailed()` and `captureFrame()`, and
  takes the capture as one `BindingRequest`; the design's `bind(...)` had four parameters.
- `FailureReason.BINDING` does not allow a supervisor override (the design names only PAD and integrity
  failures).
- The `YUV420` frame format of the design is `PixelFormat.NV21`, a specific YUV 4:2:0 layout.

Engine behaviour that differs from, or makes precise, the design:

- Spontaneous blinks never fail another challenge (people blink every few seconds; counting them would fail
  many genuine attempts), and gaze is not checked during head movements (the eyes lead the head). All other
  detectable actions are exclusive.
- The onset time is the start of the uninterrupted run above half the trigger that leads to the trigger, so one
  noisy pose estimate right after the prompt does not fail a genuine user.
- PAD scores of the 5 acquisition frames count towards `passive.min_frames`; otherwise a 3 fps stream could not
  finish acquisition plus passive scoring within the 4 s `passive.timeout_ms`.
- Integrity violations fail as soon as they are detected, not only at the passive decision. A broken track in
  the passive phase restarts score collection instead of moving back to `ACQUIRING` (states only move forward).
- The frame-rate check allows 10% below `integrity.min_fps` for timing jitter, and uses the source's sequence
  numbers so frames dropped by the single-slot hand-off still count. `INVALID_FRAMES` is an integrity failure
  and shows the generic PAD message; device-side stalls and invalid streams show `liveness.error.frames_invalid`.
- The loop check also requires motion inside the repeated run and a match much closer than the frame-to-frame
  change, so a still person is never reported as a loop. The tracking gap limit grows to 2.5 frame intervals on
  slow streams (one missed detection at 3 fps is already a 667 ms gap).
- Planarity uses an affine fit when only the 5 detector landmarks exist (a homography needs at least 8 points).
- `ALWAYS_ACTIVE` ignores `active.enable` (that key only controls escalation); when no challenge type can run on
  the stream it fails `ENGINE/UNSUPPORTED`, which does not use an attempt.
- The capture window is `[start - 2 s, start + binding.max_gap_ms + capture timeout]` (the token lifetime), with
  a 2 s tolerance for the device clock. A token that expires in `CAPTURE_READY` or `CAPTURING` ends the session
  as `DEVICE/CAPTURE_TIMEOUT`. `captureFailed()` is also accepted in `CAPTURE_READY`, because authentication
  flows can fail while waiting for device Ready, before the capture starts.
- A challenge timeout writes both LV06 (with the reason) and LV07 (with the phase). `BELOW_THRESHOLD` and
  cancelled sessions have no event of their own in section 4.17, so the session writes none; the result carries
  the reason.
- Frames are not wiped when processed or dropped, because the preview may share them; `close()` wipes the frame
  the session kept for storage (callers must copy `captureFrame()` before closing).
- An exception thrown by the listener is caught so a faulty screen cannot stop the state machine half-way.
- `DiagnosticRecord` gained an optional `detail` text (at most 256 characters, no identifiers) so configuration
  fallbacks can be logged.
