# Evaluation of liveness and PAD technologies

PS04 expected task E5: "Evaluate liveness and PAD technologies". This page lists the families of technology the
team considered for face liveness and presentation attack detection (PAD) in the MOSIP Registration Clients, the
criteria used to compare them, what was chosen, and why. The per-model details (files, hashes, licences,
preprocessing) are in [`models/SOURCES.md`](../../models/SOURCES.md); the measured behaviour of the chosen
stack is in [`docs/eval/calibration-notes.md`](../eval/calibration-notes.md).

Terms used on this page:

- **Passive PAD:** a decision from the camera frames alone, with no action asked of the person.
- **Active liveness:** the person is asked to do something (blink, smile, turn the head) and the frames are checked
  for it.
- **APCER / BPCER:** share of attack presentations accepted / share of genuine presentations rejected
  (ISO/IEC 30107-3 definitions).
- **ONNX Runtime (ORT):** the open-source library that runs ONNX model files on the CPU or an accelerator.
- **L0 / L1:** MOSIP SBI device trust levels; L0 keeps the device key in software, L1 in a secure chip.

## 1. Criteria

The criteria come from PS04 and from facts checked in the two upstream clients before any code was written
(research notes of 2026-09-26; the facts are repeated here with their source).

| # | Criterion | Why it matters here | Source of the constraint |
|---|---|---|---|
| K1 | **Works offline** | PS04: liveness "must work in both online and offline modes"; registration centres run offline for days | PS04 "Online and offline" section |
| K2 | **Licence allows publication and deployment** in an MPL-2.0 / MIT code base, for government use, including the training data terms | MOSIP code is MPL-2.0 (desktop, MockMDS) and MIT (Android); models trained on non-commercial datasets would block a national deployment | repository `LICENSE` files |
| K3 | **Runs in the existing clients** | Desktop: Java 11 on the bundled Zulu 11.0.8 JRE (`registration/pom.xml`, `configure.sh`). Android: minSdk 28, Java/Flutter | upstream build files |
| K4 | **Size and CPU cost** | Registration PCs and low-end tablets, no GPU assumed; the APK already carries the whole client | PS04 bonus "low-resource Android devices" |
| K5 | **Vendor neutrality** | PS04: "must remain independent of any specific vendor"; a certified vendor engine must be able to plug in | PS04 recommended stack |
| K6 | **Evidence of accuracy** we can check or reproduce | Judges score PAD against ISO/IEC 30107; claims must be measurable by the team | PS04 mandatory task 3 |
| K7 | **Works with the frames MOSIP devices deliver** | SBI streams are MJPEG, at least 3 frames per second, may carry vendor overlays, and are not signed; on Android the SBI app keeps the preview in its own screen | MOSIP SBI specification 1.2.0 (`mds-specification.md`, stream section) |
| K8 | **Privacy** | No image or telemetry may leave the registration machine | MOSIP data minimisation; PS04 audit requirements |

## 2. Candidates and verdicts

| Family | Candidates looked at | K1 offline | K2 licence | K4 size / CPU | K6 evidence | Verdict |
|---|---|---|---|---|---|---|
| **Passive CNN PAD models (open weights)** | Minivision Silent-Face **MiniFASNetV2 + MiniFASNetV1SE**; OpenVINO `anti-spoof-mn3`; facenox `face-antispoof-onnx`; CDCN / CDCN++; DeePixBiS | yes | MiniFASNet: Apache-2.0 code and weights. `anti-spoof-mn3` and facenox: permissive weights, but trained on CelebA-Spoof, whose terms restrict it to non-commercial research. CDCN: "research purposes only". DeePixBiS: no licence | MiniFASNet about 1.74 MB each, 2-2.4 ms per face on one desktop core | MiniFASNet README: TPR 97.8% at FPR 1e-5 on the vendor's own data; a third-party test reports APCER 66.4% on 2-D attacks | **Chosen: MiniFASNet ensemble**, the only open-licence model with clean weights. Its weakness on good 2-D attacks is the reason for the other layers (section 3) |
| **Landmark-based active challenges** | MediaPipe **Face Mesh V2** (478 points, ONNX conversion); **YuNet** detector (5 points); MediaPipe Tasks FaceLandmarker (Android only); Google ML Kit face detection; dlib 68-point; InsightFace 2d106 | yes | Face Mesh V2: Apache-2.0; YuNet: MIT. ML Kit: proprietary terms, sends usage metrics to Google. dlib 68 (iBUG 300-W) and InsightFace models: non-commercial | Face Mesh 5.8 ms, YuNet 1.7-7.6 ms per frame on one core | Challenge logic is ours and unit-tested; blink, smile, head-turn and gaze measured on replayed clips | **Chosen: YuNet + Face Mesh V2**, running on ORT on both clients. MediaPipe Tasks has no desktop Java version, so it would split the engine in two |
| **rPPG** (pulse read from skin colour changes) | POS / CHROM algorithms; pyVHR library | yes | pyVHR is GPL-3.0 (cannot be copied into MPL/MIT code); the algorithms could be written from the papers | light | A survey (arXiv 2010.04145) reports that good video replays carry a real pulse, so rPPG catches mainly photos and low-quality replays | **Rejected**: needs a steady 15 fps or more with light compression and stable lighting; SBI guarantees 3 fps of JPEG frames (K7) |
| **Depth / IR / structured-light hardware** | L1 SBI devices with device-side PAD; RGB-D or NIR cameras | yes | device vendor terms | device cost | strongest against prints and screens | **Not a software choice for this project.** The engine records a device's declared PAD capability (`padCapability`) but never trusts it alone; no such device was available to test (traceability G9) |
| **Geometric checks without a model** | Planarity from landmarks during head turns (Wang et al., ICB 2013); frame integrity (frozen, looped, time-reversed streams); moiré / frequency analysis; specular reflection | yes | own code | negligible | planarity: 92.4% liveness accuracy for the paper's head-rotation system; the others are weak alone | **Chosen: planarity and frame integrity** (they stop flat prints moved in 3-D and injected or looped streams, which passive CNNs miss). Moiré and reflection **rejected**: MJPEG compression and vendor overlays make them unreliable |
| **Commercial on-device SDKs** | Vendor liveness SDKs with ISO/IEC 30107-3 test letters from accredited laboratories | yes, for most | proprietary, per-device or per-transaction licences | varies | laboratory letters (level 1 / level 2), not reproducible by us | **Not bundled** (K5, K2). The engine interface (`LivenessEngineProvider`, found through Java `ServiceLoader`) lets a country plug in a certified SDK without changing the clients ([engine-interface.md](engine-interface.md)) |
| **Cloud liveness APIs** | Amazon Rekognition Face Liveness; Azure AI Face liveness detection | **no**: the selfie video or images are sent to the provider's service, and a backend must create each session | proprietary, pay per call | none on the device | Azure reports 0% penetration in iBeta level 1 and 2 PAD tests | **Rejected**: fails K1 (offline registration) and K8 (face images leave the country's infrastructure) |

### 2.1 Runtime

| Option | Desktop (Java 11, Zulu 11.0.8) | Android | Verdict |
|---|---|---|---|
| **ONNX Runtime Java 1.20.0** / `onnxruntime-android` 1.20.0 (1.28.0 until 2026-10-05) | Runs on the bundled JRE. ORT 1.21 fails to load and 1.30 crashes the JVM because the JRE ships an old Visual C++ runtime (measured) | Same Java API (`ai.onnxruntime`) and, since 2026-10-05, the same release on both clients; 1.29 and later add a telemetry provider by default | **Chosen**: one engine code base on both clients, with versions pinned for those two reasons |
| OpenCV DNN (already on the desktop class path via `biometrics-util`) | OpenCV 4.5.3 lacks the YuNet API | would add a second native library | kept as a fallback only |
| LiteRT (TensorFlow Lite) + MediaPipe Tasks | no desktop Java build | smaller native library | rejected: results would differ between the two clients |
| GPU / NPU execution providers | the Windows ORT Java package has only the CPU provider (measured) | XNNPACK and NNAPI are in the Android package; NNAPI is deprecated from Android 15 | CPU by default; XNNPACK / NNAPI selectable by `engine.execution_provider` on Android, not yet measured on a device ([calibration notes](../eval/calibration-notes.md) 0.4) |

## 3. What was chosen, and why this combination

No single open technology meets PS04 on its own: open passive models are weak against good prints (third-party
APCER 66.4% on 2-D attacks for MiniFASNet), challenges alone are beaten by a replay that answers the prompt, and
neither sees an injected or looped stream. The engine therefore layers cheap, independent checks, all offline and
all open:

| Layer | Technology | Stops (measured in the synthetic suite) |
|---|---|---|
| Frame integrity | own code: frame hashes, difference hash loop detection, timestamp order | injected stills (SYN-A1) and looped clips (SYN-A8): 0 accepted |
| Passive PAD | MiniFASNetV2 + V1SE ensemble on ORT | photos on screens (SYN-A5): 0 accepted |
| Active challenges | YuNet + Face Mesh V2 landmarks, random sequence from `SecureRandom`, onset and hold timing | prints held, moved or tilted (SYN-A2..A4) and wrong-action clips (SYN-A9): 0 accepted |
| Active PAD and planarity | MiniFASNet during challenges; homography residual of landmarks on head turns | screen replays (SYN-A6): 0 accepted |
| Capture binding | SFace (Apache-2.0) similarity between the live session and the signed capture; single-use pass token | a different face captured after a passed session |

Result with the shipped defaults (2026-10-14): 0 of 226 synthetic attack transactions accepted, BPCER 0.0%
([../eval/results/20261014-shipped/README.md](../eval/results/20261014-shipped/README.md)). The adaptive screen
replay (SYN-A7) was accepted in 4 of 8 `registration` transactions until the screen-pattern check of 2026-10-05; the suite's screens are software-made, so the screen check still needs real screens. These
are technology regression results on synthetic attacks, not an ISO/IEC 30107-3 evaluation; the history and how to
read it are in [iso-30107.md](iso-30107.md) section 3. Planarity has no attack evidence and cannot be relied on
against a screen that replays a real head turn.

Total model size: 25.7 MB for five models, signed as one bundle ([security-privacy.md](security-privacy.md)).

## 4. What would change the choice

- **A stronger open passive model with clean training data.** It would slot in behind the `PassivePad` interface
  and the signed model bundle; no client change.
- **L1 devices with device-side PAD.** The engine would combine the device's result with its own
  (traceability G9); this is the most direct answer to the adaptive replay (SYN-A7).
- **A country that buys a certified SDK.** It plugs in through `LivenessEngineProvider`; the clients, audit events
  and packet evidence stay the same.
- **Physical tablets.** A measured speed gain from XNNPACK or NNAPI would change the Android default from CPU.

## 5. Sources

| Topic | Source |
|---|---|
| MiniFASNet weights, licence, accuracy claim, preprocessing | https://github.com/minivision-ai/Silent-Face-Anti-Spoofing (commit `b6d5f04a`); ONNX files https://github.com/yakhyo/face-anti-spoofing |
| Third-party test of open PAD models (vendor blog, updated 2026-04-22) | https://axonlab.ai/best-open-source-face-liveness-detection-models/ |
| CelebA-Spoof dataset terms | https://github.com/ZhangYuanhan-AI/CelebA-Spoof |
| `anti-spoof-mn3` | OpenVINO Open Model Zoo, `models/public/anti-spoof-mn3/model.yml` |
| facenox model | https://github.com/facenox/face-antispoof-onnx |
| CDCN licence note | https://github.com/ZitongYu/CDCN |
| YuNet | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |
| Face Mesh V2 model card and ONNX conversion | MediaPipe "Model Card MediaPipe Face Mesh V2"; PINTO model zoo `410_FaceMeshV2` |
| ML Kit terms (usage metrics sent to Google) | https://developers.google.com/ml-kit/terms |
| rPPG survey | arXiv 2010.04145 |
| Planarity from a single camera | Wang et al., "Face liveness detection using 3D structure recovered from a single camera", ICB 2013 |
| Amazon Rekognition Face Liveness (video streamed to cloud APIs) | https://docs.aws.amazon.com/rekognition/latest/dg/face-liveness.html |
| Azure AI Face liveness (app server plus cloud service; iBeta level 1 and 2 result) | https://learn.microsoft.com/azure/ai-services/computer-vision/concept-face-liveness-detection |
| NNAPI deprecation | https://developer.android.com/ndk/guides/neuralnetworks/migration-guide |
| ORT telemetry (Android 1.29+) and Visual C++ runtime requirement (1.21+) | https://github.com/microsoft/onnxruntime/blob/main/docs/Privacy.md; ORT issues #24287 and #26599; team measurements with ORT 1.20.0, 1.21.0 and 1.30.0 on Zulu 11.0.8 |
| SBI stream properties | MOSIP documentation 1.2.0, `mds-specification.md` (stream and Android sections) |
| Injection attacks (outside ISO/IEC 30107) | CEN/TS 18099:2024 |
