# face-match-sdk: SFace biometric SDK for MOSIP (FACE)

A MOSIP biometric SDK (`io.mosip.kernel.biometrics.spi.IBioApiV2`, SDK API version `0.9`) for the FACE modality.
It uses the SFace embedding model and the YuNet detector from the signed liveness model bundle (`models/`), so
the Registration Clients get a real 1:1 / 1:N face matcher and a real face quality score instead of MOSIP's mock
SDK. It handles FACE only: finger and iris stay with the mock SDK or a vendor SDK, because MOSIP selects one SDK
per modality. Licence: MPL-2.0.

| Class | Role |
|---|---|
| `io.mosip.liveness.facematch.SFaceBioApi` | The `IBioApiV2` implementation named in the MOSIP configuration |
| `SFaceMatcher` | Platform-neutral core with no MOSIP types: BDB to embedding, quality, similarity |
| `FaceTemplate` | The template written into the BIR `BDB` by `extractTemplate` |
| `FaceQualityScore` | The 0-100 quality score of `checkQuality` |
| `FaceImageDecoder` | Interface for the platform image decoder; `imageio.ImageIoFaceImageDecoder` is the desktop one |
| `iso.FaceIsoRecord` | Reader of ISO/IEC 19794-5 face records (2005 and 2011 editions) |

## Selecting the SDK in a Registration Client

Both clients read the same keys, `mosip.biometric.sdk.providers.<modality>.<vendor>.<param>`, from the synced
global parameters. The desktop client binds them with `@ConfigurationProperties(prefix =
"mosip.biometric.sdk.providers")` on `io.mosip.kernel.biosdk.provider.factory.BioAPIFactory`
(`kernel-biosdk-provider` 1.2.0.1); the Android client reads them in `GlobalParamRepository`
(`BIOMETRIC_SDK_PROVIDERS_PREFIX`). The upstream defaults are in
`tools/mock-sync/data/upstream/mosip-config/registration-default.properties` (lines 265-276).

Replace the values of the existing FACE entries. Keep the vendor name:

```properties
mosip.biometric.sdk.providers.face.mockvendor.classname=io.mosip.liveness.facematch.SFaceBioApi
mosip.biometric.sdk.providers.face.mockvendor.version=0.9
mosip.biometric.sdk.providers.face.mockvendor.args=
mosip.biometric.sdk.providers.face.mockvendor.threshold=40
# optional:
# mosip.biometric.sdk.providers.face.mockvendor.modeldir=<directory holding manifest.json, manifest.jws and the .onnx files>
# mosip.biometric.sdk.providers.face.mockvendor.decoder=<FaceImageDecoder or liveness ImageDecoder class>
```

- **Keep one FACE vendor.** Do not add a second vendor under `face.` beside `mockvendor`.
  - On the desktop, `BioAPIFactory` walks the vendors in `HashSet` order and the last one registered wins, so
    two FACE vendors make the choice unpredictable.
  - Android keeps a list per modality and picks the first provider that supports the function.
- **Finger and iris stay unchanged.** The `finger.mockvendor.*` and `iris.mockvendor.*` keys keep pointing at
  `io.mosip.mock.sdk.impl.SampleSDK`. `MosipSdkLoaderTest` runs this exact layout through MOSIP's own
  `BioAPIFactory` and `BioProviderImpl_V_0_9`: FACE goes to this SDK and FINGER to the other SDK.
- **Restart needed.** On the desktop these keys only apply after a restart:
  `GlobalParamServiceImpl.isPropertyRequireRestart` flags every key that contains `mosip.biometric.sdk`.
- **Parameters:**
  - `threshold`: 0-100, default 40.
  - `modeldir`: default is the bundle on the class path.
  - `decoder`: default `io.mosip.liveness.facematch.imageio.ImageIoFaceImageDecoder`.
  - `classname`, `version` and `args` are read by MOSIP's loader.
  - `args` must be empty: the SDK has only a no-argument constructor.

### Getting the jar and the models into the desktop client's `lib/`

The desktop build copies every runtime dependency of `registration-services` into `lib/`
(`scripts/desktop-build.sh`, line 174: `cp "$target"/lib/*.jar "$RUN_DIR/lib/"`). `registration-services/pom.xml`
already has `liveness-core`, `liveness-onnx` and `liveness-onnx:models`, plus `onnxruntime` 1.20.0. One more
dependency is needed:

```xml
<dependency>
    <groupId>io.mosip.liveness</groupId>
    <artifactId>face-match-sdk</artifactId>
    <version>${liveness.version}</version>
    <scope>runtime</scope>
</dependency>
```

- `kernel-biometrics-api`, `jai-imageio-jpeg2000` and `onnxruntime` are `provided` here. The client already ships
  all three.
- The SDK reads the same `liveness-onnx-<version>-models.jar` as the liveness engine, so the models are not
  shipped twice.
- It loads its own YuNet and SFace sessions at `init`. The engine sessions are not shared, because MOSIP creates
  the SDK by class name.
- If the client checks the hashes of its `lib/` jars (the `ClientSetupValidator` manifest), add
  `face-match-sdk-<version>.jar` to that list.

### Android

- Not run yet. The Android loader (`BioSdkLoader`) opens SDK files from `assets/biosdk/` with a
  `DexClassLoader`. It needs this jar, `liveness-core` and `liveness-onnx` dexed, plus `onnxruntime-android`.
- Set `modeldir` to a directory that holds the bundle, for example the liveness models copied from assets into
  `filesDir`.
- Set `decoder` to a class with a public no-argument constructor that implements `FaceImageDecoder` or the
  liveness `ImageDecoder`. The Android liveness decoder qualifies. `FaceImageDecoder.of(ImageDecoder)` adapts
  one to the other.
- The ImageIO decoder is never loaded on Android: the class is named only in configuration, and animal-sniffer
  checks all other code against Android API 28.

## Behaviour

| Call | Result |
|---|---|
| `init(params)` | Verifies the bundle signature, loads YuNet and SFace, and returns `SDKInfo`: API `0.9`; FACE for `MATCH`, `QUALITY_CHECK` and `EXTRACT`; `otherInfo` with the model id, threshold and each model's version and SHA-256 prefix. Invalid `threshold` or `decoder`: `IllegalArgumentException`. Bundle rejected: `IllegalStateException`. Calling `init` again with only a new threshold does not reload the models. |
| `checkQuality` | `QualityScore` 0-100 for FACE (formula below). Flags such as `NO_FACE`, `MULTIPLE_FACES`, `FACE_TOO_SMALL`, `TOO_DARK`, `TOO_BRIGHT` and `UNREADABLE` go into `errors`; `faces`, `interEyeDistance` and `brightness` go into `analyticsInfo`. |
| `extractTemplate` | Every FACE BIR gets a template as its `BDB`, with level `PROCESSED`; format, quality, subtype, purpose, SB and others are kept. BIRs of other modalities are returned unchanged. |
| `match` | For each gallery record: the best cosine similarity over the probe and gallery FACE pairs, as `round(100 * max(0, cosine))`. `MATCHED` when the score is at least `threshold`, otherwise `NOT_MATCHED`. A BDB may be an ISO/IEC 19794-5 record, a plain JPEG / JPEG 2000 / PNG, or a template. `analyticsInfo` holds only `score`, `threshold` and `model`. |
| `segment` | Returns the record unchanged, because a face image needs no segmentation. Not advertised in `SDKInfo`. |
| `convertFormatV2` / `convertFormat` | Supports only a conversion to the same format (the record is returned); any other conversion gives `401` or `UnsupportedOperationException`. |

Status codes (the same numbers as MOSIP's mock SDK):

| Code | When |
|---|---|
| 200 | Success. A gallery record without a usable face gets a per-record `ERROR` decision, not a failed response. |
| 401 | Only other modalities requested; unreadable BDB; template of another model. |
| 402 | Missing sample, missing gallery, or no FACE segment in the sample. |
| 406 | No face found in the probe image. |
| 500 | `init` not called, or a model failed. |

- Requested modalities other than FACE (for example FACE plus IRIS) get an `ERROR` decision with
  `NOT_SUPPORTED: IRIS` next to the FACE decision.

### Template format (`FaceTemplate`, version 1, big-endian, 539 bytes for SFace)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `SFTE` |
| 4 | 1 | format version `1` |
| 5 | 1 | length L of the model id |
| 6 | L | model id, UTF-8: `sface@2021dec-fp16w` |
| 6+L | 2 | dimension D (128) |
| 8+L | 4·D | unit-length embedding, IEEE 754 float32 |

- The magic cannot start a face record (`FAC\0`), a JPEG, a PNG or a JPEG 2000 file, so a BDB is recognised
  without other hints.
- A template of another model id is refused (`401`, "enrol again") instead of being compared with meaningless
  results.

### Quality score

`score = round(100 × confidence × size × exposure × single)`:

- `confidence`: YuNet's detection score.
- `size`: `min(1, inter-eye distance / 60 px)`. 60 px is the ISO/IEC 19794-5 token-image minimum.
- `exposure`: 1 for a mean face brightness of 50-225, falling linearly to 0.5 at 0 or 255.
- `single`: 1 for one face, 0.5 when more faces are visible.
- No face gives 0.

### Measured scores (repository test data, threshold 40)

| Probe | Reference | Score |
|---|---|---|
| MockMDS registration record (person A) | the same record | 100 |
| MockMDS auth record (the registration photo again, lossy JPEG 2000) | registration record | 100 |
| MockMDS stream frame (another photo of person A, JPEG) | registration record | 62 |
| MockMDS stream frame (person A) | exception photo (person B) | 7 |

- The registration record's quality is 92 (inter-eye distance 59 px, so `FACE_TOO_SMALL`; brightness 86).
- There is one genuine pair of different photos (62) and one impostor pair (7). That is enough to check the
  behaviour, not to calibrate the threshold.
- The default threshold of 40 is cosine 0.40. That is slightly stricter than OpenCV's published SFace
  same-person threshold of 0.363.
- Calibrate the threshold on a larger openly licensed set before production use.

## Image decoding and licences

- **ISO record:** `FaceIsoRecord` is our own reader, not MOSIP's `io.mosip.biometrics.util.face.FaceDecoder`,
  for two reasons:
  - MOSIP's decoder reads only the 2011 edition, logs through SLF4J and needs `java.awt`, so it cannot run on
    Android.
  - `FaceIsoRecordTest` checks that both readers return the same width, height, types, quality and image bytes
    on all three MockMDS records. `biometrics-util` (MPL-2.0) is a test dependency only.
- **JPEG 2000:** the desktop decoder uses whichever ImageIO JPEG 2000 reader is on the class path. The desktop
  client already ships `com.github.jai-imageio:jai-imageio-jpeg2000:1.3.0` in `registration-services/pom.xml`.
  - This module does not bundle it (test scope only).
  - Its licences are the JJ2000 licence and "BSD 3-clause with nuclear disclaimer". Neither is OSI-approved.
  - It stays an upstream MOSIP dependency, not one we add. An Android build needs its own JPEG 2000 decoder
    anyway.
- **No temporary files:** images are read through an in-memory ImageIO stream, so ImageIO writes no cache file
  holding biometric data.
- **Models:** YuNet (MIT) and SFace (Apache-2.0), from `models/` (see `models/SOURCES.md`).

## Security and privacy

- Nothing biometric is logged or returned in analytics: only scores, thresholds, counts and model ids.
  - `ArchitectureTest` forbids logging APIs in this module.
- Decoded pixel buffers are wiped after use. The sample byte arrays are never modified (tested).
- The model bundle is verified against its signature before any model loads (fail closed).

## Build and test

```bash
. /e/Projects/MOSIP/toolchain/env.sh
with_lock liveness-build mvn -f liveness/pom.xml -pl face-match-sdk verify
```

- 42 tests, about 10 s:
  - `SFaceBioApiContractTest`: `IBioApiV2` semantics and status codes.
  - `MosipSdkLoaderTest`: MOSIP's `BioAPIFactory` and `BioProviderImpl_V_0_9` with FACE on this SDK and FINGER on
    a stub.
  - `FaceIsoRecordTest`: cross-checked with MOSIP's reader.
  - `FaceTemplateTest`, `FaceQualityScoreTest`, `ImageIoFaceImageDecoderTest`, `ArchitectureTest`.
- Test data is read in place from `devices/mock-mds/Profile/Default/` (MPL-2.0 MockMDS files) and
  `liveness-onnx/src/test/resources/faces/`.
- The compile step fails on an Android API 28 violation (animal-sniffer). Only `imageio` may use `java.awt` and
  `javax.imageio`.

## Design choices

- `kernel-biometrics-api` is 1.2.0.2, not 1.2.0.1: it is the version the desktop client really uses
  (`mosip.biometric.util.version` in `clients/desktop/registration/pom.xml`). `kernel-biosdk-provider` is 1.2.0.1
  (`mosip.core.kernel.version`).
- The ISO/IEC 19794-5 reader is our own, not MOSIP's `FaceDecoder`, so the same code runs on Android. See
  "Image decoding and licences".
- The SDK loads its own YuNet and SFace sessions instead of sharing the liveness engine's, because MOSIP creates
  SDKs by class name, with no way to pass it an object.
