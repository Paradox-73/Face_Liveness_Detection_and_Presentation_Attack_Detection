# Android liveness API (Pigeon bridge, native service, capture screen)

Three layers on the Android Registration Client (ARC):

| Layer | Where | Caller |
|---|---|---|
| Pigeon `LivenessApi` (Dart to Java) | definition `clients/android/pigeon/liveness.dart`; host `android/app/.../api_services/LivenessApi.java`; generated `lib/pigeon/liveness_pigeon.dart` and `.../model/LivenessPigeon.java` | Flutter screens |
| Native `LivenessService` | `android/livenessmanager/.../LivenessService.java` (Dagger, `LivenessModule`) | ARC Java code |
| Capture screen `LivenessCaptureActivity` | `android/livenessmanager/.../ui/LivenessCaptureActivity.java`, started for result with request code 6 | `LivenessService.newCaptureIntent`; adb in debug builds |

- **Authentication:** in-process calls. Face authentication additionally checks, before the camera opens, that the
  user is not locked, has an enrolled face, and holds `REGISTRATION_SUPERVISOR` for `eod_auth` / `exception_auth`.
- **Offline:** every call works without a network connection.
- Generated Pigeon files are not committed; `scripts/android-build.sh` regenerates them (`pigeon.sh`).

Design: [../design/android-liveness.md](../design/android-liveness.md).

## 1. Pigeon `LivenessApi`

```dart
class LivenessOutcome {
  final String status;        // PASSED, FAILED, BLOCKED, CANCELLED or NOT_REQUIRED
  final String? errorCode;    // face authentication: FACE_MISMATCH, NOT_SUPERVISOR, MAX_ATTEMPTS, ...
  final String? message;      // text in the display language from the engine's message catalogue
  final int attemptsUsed;
  final int maxAttempts;
}

@HostApi()
abstract class LivenessApi {
  bool isEnforced(String workflow);
  bool isFaceAuthAvailable(String userId, String workflow);
  void setLanguage(String languageCode);                 // ARC codes: eng, fra, ara, hin, kan, tam
  LivenessOutcome getLastCaptureOutcome(String fieldId);
  @async
  LivenessOutcome authenticateFace(String userId, String workflow);
  @async
  ModelBundleOutcome importModelBundle(String path);     // offline model update (supervisor)
  ModelBundleOutcome getModelBundle();
  @async
  ModelBundleOutcome updateModelBundle();                // online model update (supervisor)
  bool isModelUpdateConfigured();
}
```

| Method | Behaviour |
|---|---|
| `isEnforced(workflow)` | Master switch on and the workflow's policy not `OFF` (synced configuration) |
| `isFaceAuthAvailable(userId, workflow)` | Liveness on, account not locked, supervisor role where needed, face enrolled at onboarding |
| `setLanguage(code)` | Language of the capture screen and of `message` |
| `getLastCaptureOutcome(fieldId)` | Outcome of the last resident / onboarding face capture of that field; `NOT_REQUIRED` when no liveness ran |
| `authenticateFace(userId, workflow)` | Liveness (capture screen), then 1:1 SFace match against the user's onboarding face (`user_biometric`, type `FACE`), threshold `mosip.registration.liveness.face_auth.match_threshold` (40). `workflow` must be an authentication workflow (`login_auth`, `packet_auth`, `exception_auth`, `eod_auth`, `onboard_auth`), else `FAILED` / `NOT_ENFORCED` |

Model update calls. `ModelBundleOutcome` carries `status` (a `ModelBundleImporter.Status` name: `IMPORTED`,
`NOT_ALLOWED`, `BUSY`, `NOT_SUPPORTED`, `NOT_FOUND`, `INVALID`, `TOO_OLD`, `LOAD_FAILED`), `bundle`, `version`,
`previousVersion` and `fromStore`, and no file content.

| Method | Behaviour |
|---|---|
| `importModelBundle(path)` | Verifies the signed bundle at `path` (folder or `.zip`; empty for `Android/data/<app>/files/liveness-models`), stores it and switches the engine. Signed-in user must be a supervisor or administrator |
| `getModelBundle()` | The bundle the engine runs (`IMPORTED`), or `NOT_FOUND` |
| `updateModelBundle()` | Downloads the bundle `.zip` from the synced `mosip.registration.liveness.model.update_url` and imports it exactly like `importModelBundle`. `NOT_FOUND` when the key is empty or the download failed (HTTPS required except to a loopback host, no redirects, 30 s timeouts, 10 minute limit, 512 MB cap); the running models stay active |
| `isModelUpdateConfigured()` | `true` when `mosip.registration.liveness.model.update_url` is set; the Flutter panel shows "Check for model update" only then |

`errorCode` values for `authenticateFace` (`FaceAuthStatus`): `NOT_ENFORCED`, `CANCELLED`, `FACE_NOT_ENROLLED`,
`NOT_SUPERVISOR`, `USER_LOCKED`, `LIVENESS_FAILED`, `MAX_ATTEMPTS`, `FACE_MISMATCH`, `ENGINE_UNAVAILABLE`, `BUSY`.

Related Pigeon call, `AuthResponseApi.loginWithFace(String username)` (`pigeon/auth_response.dart`): opens the
session after a passing `login_auth` authentication, using a one-time grant valid 60 s; without it it answers
`REG_FACE_NOT_VERIFIED`.

### Example responses

Pigeon messages are binary, so these are the field values as Flutter receives them, built from the code path
(`FaceAuthenticator`, `LivenessApi.toPigeon`) for the situations of the Android end-to-end runs.

Face sign-in of an enrolled operator (Android run A2):

```json
{"status":"PASSED","errorCode":null,"message":"Good, face captured successfully","attemptsUsed":1,"maxAttempts":3}
```

A live person who is not the user (run A3; liveness passed, match score 13 against 40):

```json
{"status":"FAILED","errorCode":"FACE_MISMATCH","message":null,"attemptsUsed":1,"maxAttempts":3}
```

(The Flutter panel then shows its own text for `FACE_MISMATCH`: "The face does not match this user. Please try
again.") The exact `message` text depends on the display language and the engine state that ended the attempt.

## 2. Native `LivenessService`

```java
public interface LivenessService {
    int REQUEST_CODE = 6;                                    // ARC uses 1-5 for its SBI intents
    LivenessPolicy policy(Workflow workflow);
    boolean isEnforced(Workflow workflow);
    Intent newCaptureIntent(Context context, LivenessCaptureRequest request);
    LivenessCaptureResult parseResult(int resultCode, Intent data);
    void preload();                                          // background model load at app start
    boolean isBuiltInDeviceProvisioned();                    // device key issued by a DEVICE CA?
}
```

`LivenessCaptureRequest.builder(Workflow, String subjectRef)` with `.language("eng")`, `.priorAttempts(n)` (attempts
already used in this transaction), `.priorSuspectedAttack(true)` (an earlier screen saw a suspected attack: no
override), `.priorLowLight(n)` (uncounted dim-light results since the last counted failure),
`.photoWhenOff(true)` (exception photo without liveness); debug builds only:
`.replay(pack, fps)`, `.replayFollow(true)`, `.replayMasked(true)`, `.configOverride(key, value)`.

`LivenessCaptureResult`: `isPass()`, decision, failure reason, reason codes, message key, attempts and maximum,
`isRetryAllowed()`, `isOverrideAllowed()`, `isLockRequired()`, `onMaxFailure()`, `lowLightRetries()` (the
transaction's uncounted dim-light results, to pass as `priorLowLight` to the next screen), and for a pass
`rCaptureResponse()` (SBI 0.9.5 JSON for ARC's `Biometrics095Service.handleRCaptureResponse`), `faceIso()`,
`transactionId()`, `tokenNonce()`, `evidence()` (`LIVENESS_*` map).

## 3. Capture screen intent

| Extra | Type | Meaning |
|---|---|---|
| `liveness.workflow` | string | workflow ID (default `registration`) |
| `liveness.subject` | string | RID or user ID |
| `liveness.language` | string | ARC language code (default `eng`) |
| `liveness.prior_attempts` | int | attempts already used |
| `liveness.prior_suspected_attack` | boolean | an earlier screen saw a counted PAD, frame integrity or binding failure since the last pass: no supervisor override (default `false`) |
| `liveness.prior_low_light` | int | uncounted dim-light results of earlier screens since the last counted failure (default 0) |
| `liveness.photo_when_off` | boolean | take a plain signed photo when the policy is `OFF` |
| `liveness.source` = `replay`, `liveness.replay`, `liveness.replay_fps`, `liveness.replay_follow`, `liveness.replay_masked` | debug builds | play a frame pack instead of the camera |
| `config.<key>` | debug builds | configuration override for this run |

Result: `RESULT_OK` with the pass extras (`liveness.result.*`), or `RESULT_CANCELED` with the failure details;
always read through `LivenessService.parseResult`.

Debug example (what `scripts/android-liveness-demo.sh` runs):

```bash
adb shell am start -W -n io.mosip.registration_client/io.mosip.registration.livenessmanager.ui.LivenessCaptureActivity \
  --es liveness.workflow registration --es liveness.subject demo --es liveness.language eng \
  --es liveness.source replay --es liveness.replay mockmds-still --ei liveness.replay_fps 15
adb logcat -s Liveness      # "Screen state CHECKING liveness.status.checking", audits, frame times
```

The bundled `mockmds-still` pack is a single still image, so this run ends with `FAIL_PAD` (`FROZEN`), the
expected refusal of an injected still stream.

## 4. Use case: face sign-in button in Flutter

```dart
final api = LivenessApi();
if (await api.isFaceAuthAvailable(userId, 'login_auth')) {
  final outcome = await api.authenticateFace(userId, 'login_auth');   // opens the capture screen
  if (outcome.status == 'PASSED') {
    final auth = await AuthResponseApi().loginWithFace(userId);       // consumes the 60 s grant
    // continue as after a password login
  } else {
    showBanner(outcome.message ?? textFor(outcome.errorCode));        // FaceAuthPanel does this
  }
}
```

This is what `lib/ui/liveness/face_auth_panel.dart` and `lib/provider/auth_provider.dart` do for the "Sign in with
face" option.
