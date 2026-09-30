# 0005. Ship an SFace-based `IBioApiV2` SDK for FACE matching

- Status: Accepted
- Date: 2026-09-29

## Context

PS04: "Where face verification is used for operator authentication, liveness shall provide an additional control to
ensure that the matched face is presented by a live person." The clients match faces through MOSIP's biometric SDK
interface (`io.mosip.kernel.biometrics.spi.IBioApiV2`). The SDK normally configured in development, MOSIP's mock
SDK, compares images by hash: a new, live capture of the same person never matches the onboarding capture, so face
sign-in after a real liveness check could not work. The liveness engine already loads SFace (OpenCV Zoo, Apache-2.0)
for its same-person and binding checks.

## Decision

`liveness/face-match-sdk`: `io.mosip.liveness.facematch.SFaceBioApi implements IBioApiV2` for FACE only.

- `init` verifies the signed model bundle and loads YuNet and SFace.
- `checkQuality`: 0-100 from detector confidence, face size, exposure and face count.
- `extractTemplate`: a documented 539-byte `SFTE` template (magic, version, model ID, 128 float32 values).
- `match`: `round(100 x max(0, cosine))` of the best probe/gallery pair, `MATCHED` at or above the threshold
  (default 40, from OpenCV's published same-person cosine 0.363 plus a margin).
- Other modalities are refused, so MOSIP's per-modality configuration keeps the mock or vendor SDK for finger and
  iris.
- Selected with MOSIP's own keys (`mosip.biometric.sdk.providers.face.mockvendor.classname`), keeping the vendor
  name `mockvendor` because the desktop `BioAPIFactory` keeps only one vendor per modality.
- Android does not load the SDK through `BioSdkLoader`; it matches on the engine's own SFace session through the
  same `SFaceMatcher` code (`EngineFaceVerifier`), against the onboarding face stored on the tablet.

## Consequences

- Face sign-in, packet authentication and supervisor approval match real faces offline; a live but wrong person is
  refused (desktop D11: score 11; Android A3: score 13; enrolled person 94-100).
- The desktop loads a second YuNet and SFace session for the SDK (about 20 MB native memory, about 1 s), because
  MOSIP creates SDKs by class name and cannot receive the engine object.
- The threshold is not calibrated on a large data set; the repository holds one genuine pair of different photos
  (score 62) and one impostor pair (score 7).
- SFace's training data (research datasets per its paper) is disclosed in `models/SOURCES.md`; the model is used for
  1:1 matching and binding only, never for the PAD decision.
- SDK keys contain `mosip.biometric.sdk`, so a change needs a client restart.

## Alternatives considered

- **MOSIP mock SDK:** hash comparison; unusable with live captures.
- **InsightFace / ArcFace models:** non-commercial licences.
- **Server-side matching through IDA:** not available offline, and MockSync's IDA does not match.
