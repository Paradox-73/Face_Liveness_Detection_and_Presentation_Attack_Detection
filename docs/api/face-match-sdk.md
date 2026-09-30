# Face-match SDK (`IBioApiV2`)

- **Artifact:** `io.mosip.liveness:face-match-sdk:1.0.0-SNAPSHOT` (`liveness/face-match-sdk`), class
  `io.mosip.liveness.facematch.SFaceBioApi`.
- **Interface:** MOSIP `io.mosip.kernel.biometrics.spi.IBioApiV2` from `kernel-biometrics-api` 1.2.0.2 (the version
  the desktop client uses; `provided` scope). SDK API version `0.9`.
- **Purpose:** real 1:1 FACE matching and face quality for operator and supervisor authentication, so that a live
  face that passed liveness can be matched against the onboarding face. FACE only; finger and iris stay with the
  mock or vendor SDK.
- **Authentication:** none; MOSIP's `BioAPIFactory` creates the class by name from configuration. `init` refuses to
  start if the signed model bundle fails verification.

Full behaviour and template format: `liveness/face-match-sdk/README.md`. Decision record:
[../adr/0005-sface-face-match-sdk.md](../adr/0005-sface-face-match-sdk.md).

## 1. Selecting it

MOSIP configuration keys (synced; a change needs a client restart):

```properties
mosip.biometric.sdk.providers.face.mockvendor.classname=io.mosip.liveness.facematch.SFaceBioApi
mosip.biometric.sdk.providers.face.mockvendor.version=0.9
mosip.biometric.sdk.providers.face.mockvendor.args=
mosip.biometric.sdk.providers.face.mockvendor.threshold=40
# optional: .modeldir=<folder with manifest.json, manifest.jws, *.onnx>  .decoder=<FaceImageDecoder class>
```

Keep the vendor name `mockvendor` and only one FACE vendor: the desktop `BioAPIFactory` keeps only the last vendor
registered for a modality. MockSync serves exactly these values (`tools/mock-sync/data/mock-sync-defaults.properties`).

## 2. Methods

| `IBioApiV2` method | Request | Response |
|---|---|---|
| `SDKInfo init(Map<String,String> params)` | `threshold`, optional `modeldir`, `decoder` | API `0.9`; FACE for `MATCH`, `QUALITY_CHECK`, `EXTRACT`; `otherInfo`: model ID, threshold, model versions and SHA-256 prefixes |
| `Response<QualityCheck> checkQuality(BiometricRecord sample, List<BiometricType> modalities, Map flags)` | FACE BIR (ISO/IEC 19794-5 record, JPEG, JPEG 2000 or PNG) | score 0-100 = round(100 x detector confidence x size x exposure x single face); flags such as `NO_FACE`, `MULTIPLE_FACES`, `FACE_TOO_SMALL` in `errors` |
| `Response<BiometricRecord> extractTemplate(BiometricRecord sample, List<BiometricType> modalities, Map flags)` | FACE BIRs | each FACE BDB replaced by a 539-byte `SFTE` template (level `PROCESSED`) |
| `Response<MatchDecision[]> match(BiometricRecord sample, BiometricRecord[] gallery, List<BiometricType> modalities, Map flags)` | probe and gallery records (images or templates) | per gallery record: `MATCHED` when `round(100 x max(0, cosine))` of the best face pair is at least `threshold`; `analyticsInfo` holds only `score`, `threshold`, `model` |
| `segment`, `convertFormatV2` | | record returned unchanged / only same-format conversion |

Status codes (same numbers as MOSIP's mock SDK): 200 success; 401 other modalities only, unreadable data or a
template of another model; 402 missing sample, gallery or FACE segment; 406 no face in the probe; 500 not
initialised or model failure.

## 3. Example

Match analytics logged by the desktop client for operator `officer1` at face sign-in (end-to-end run, section I3):

```text
{score=100, threshold=40, model=sface@2021dec-fp16w}
```

Measured scores with threshold 40: same record 100; another photo of the same person 62; a different person 7
(repository test data); live wrong person at desktop sign-in 11 and at Android sign-in 13.

## 4. Use case: operator face sign-in on the desktop

1. `LoginController` runs liveness (`login_auth`) and captures the signed face (gate-bound).
2. `AuthenticationServiceImpl.authValidator` checks the liveness pass, then asks MOSIP's `BioAPIFactory` for the
   FACE provider, which is `SFaceBioApi`.
3. `match(probe = captured face, gallery = [USER_BIOMETRIC face template of officer1])` returns `MATCHED` (score 100
   in the run), and the operator reaches the home screen.

On Android the same `SFaceMatcher` code runs through `EngineFaceVerifier` on the engine's own SFace session (no
second model load); the SDK class itself is not loaded there.
