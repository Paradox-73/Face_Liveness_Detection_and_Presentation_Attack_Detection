# Model bundle: sources, licences and preprocessing

This folder is the signed model bundle used by `liveness/liveness-onnx` on both registration clients.

- `manifest.json`: bundle name and version, and per model its file, version, SHA-256, size, licence, source and
  parameters.
- `manifest.jws`: detached JWS (RS256) over the exact bytes of `manifest.json`, signed with the development
  model-signing key (`kid` `sha256:99538367853153c2`).
- `model-signing-dev.pub.pem`: that key's public half. The same key is built into
  `io.mosip.liveness.onnx.model.ModelTrustAnchors`. The private half is not in this repository.
- `LICENSES/`: licence texts the model licences require us to ship (Apache-2.0, and YuNet's MIT notice). The
  same two texts are also in the repository-wide `licenses/` folder (`Apache-2.0.txt`, `MIT-YuNet.txt`), and the
  models are listed in `NOTICE` and in the licence review `docs/sbom/README.md`. All five model licences (MIT,
  Apache-2.0) are OSI-approved; the caveats below concern training data and intended use, not the licences.
- `tools/fp16_weights.py`: the script that produced the two `*_fp16w.onnx` files.

Bundle version 1.3.0, signed on 2026-10-04. It is above every version signed earlier with the development key
(1.0.0, 1.0.1 and 1.1.0) and above the 1.2.0 that the tampered sample bundle claims without a valid signature.
The default `model.min_version` is 1.3.0, so a client at the defaults refuses all of those older bundles. The signed `engineParams` set `quality.min_sharpness` to 40 and
`integrity.loop_window_frames` to 300 (loops of up to 294 frames are found; see
`liveness/liveness-core/.../EngineParameter.java`). Total size of the five model files: 25,671,839 bytes (24.5 MiB).

Licences and sources were checked on 2026-09-28 in each source repository (LICENSE file at the commit named
below). SHA-256 values were computed on the downloaded files. For the LFS-stored OpenCV Zoo files they also match
the LFS pointer at that commit.

## Summary

| id | File | Bytes | SHA-256 | Licence |
|---|---|---|---|---|
| `yunet` | `face_detection_yunet_2026may.onnx` | 229,738 | `ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0` | MIT |
| `facemesh` | `face_landmarks_detector_1x3x256x256_fp16w.onnx` | 2,581,728 | `db4b768438ee1304d7e22b9cb8447360ba3750f133b377d68bdf484be2b3f886` | Apache-2.0 |
| `minifasnet-v2` | `MiniFASNetV2.onnx` | 1,743,581 | `b32929adc2d9c34b9486f8c4c7bc97c1b69bc0ea9befefc380e4faae4e463907` | Apache-2.0 |
| `minifasnet-v1se` | `MiniFASNetV1SE.onnx` | 1,742,335 | `ebab7f90c7833fbccd46d3a555410e78d969db5438e169b6524be444862b3676` | Apache-2.0 |
| `sface` | `face_recognition_sface_2021dec_fp16w.onnx` | 19,374,457 | `d2899b3ea45696262517b6890f72f22c6500e887d400c678ffd6c4da9a0d62f3` | Apache-2.0 |

## YuNet face detector (`yunet`)

- **Source:** OpenCV Zoo, `models/face_detection_yunet/face_detection_yunet_2026may.onnx`, repository
  `https://github.com/opencv/opencv_zoo`, commit `47534e27c9851bb1128ccc0102f1145e27f23f98`.
  - The file was added in commit `26cc381e` (2026-05-22) as the 2023mar model re-exported with dynamic height and
    width.
- **Licence:** MIT, "Copyright (c) 2020 Shiqi Yu" (`models/face_detection_yunet/LICENSE`); the text is in
  `LICENSES/MIT-YuNet.txt`.
- **Shipped file:** unchanged; SHA-256 equals the upstream LFS object id.
- **Input:** `input`, float32 `[1, 3, H, W]`, where H and W are multiples of 32.
- **Outputs:** `cls_{8,16,32}` `[1, N, 1]`, `obj_{8,16,32}` `[1, N, 1]`, `bbox_{8,16,32}` `[1, N, 4]` and
  `kps_{8,16,32}` `[1, N, 10]`, with N = (H/stride) x (W/stride).
- **Preprocessing:**
  - BGR planes with raw 0-255 values, no mean and no scaling.
  - The image is scaled so its longer side is at most 320 px (configurable), then placed at the top-left of a zero
    tensor padded to multiples of 32.
- **Decoding (as OpenCV `FaceDetectorYN`):**
  - `score = sqrt(clamp(cls) * clamp(obj))`.
  - Box centre `(col + dx, row + dy) * stride`, size `exp(dw, dh) * stride`.
  - Landmarks `(kx + col, ky + row) * stride`, in the order right eye, left eye, nose tip, right mouth corner, left
    mouth corner (the subject's own sides).
  - Greedy non-maximum suppression at IoU 0.3. Default score threshold 0.7.
- **Accuracy (upstream README):** WIDER Face validation AP 0.884 / 0.866 / 0.750 (easy / medium / hard).

## MediaPipe Face Mesh V2 landmarks (`facemesh`)

- **Source:** PINTO model zoo `410_FaceMeshV2`, `face_landmarks_detector_1x3x256x256.onnx`.
  - Taken from the archive the folder's `download.sh` fetches:
    `https://s3.ap-northeast-2.wasabisys.com/pinto-model-zoo/410_FaceMeshV2/resources.tar.gz`, archive SHA-256
    `bcbc0e7fc711b3d5504defcbfc1d47b39ba67591fb238018d65a3c4b1642e79d`, read at repository commit
    `07938bed3d0bdfe47538277f06d6c6c9af102e9b`.
  - It is a conversion of Google's MediaPipe Face Mesh V2 (face landmarker) model.
- **Licence:** Apache-2.0.
  - Checked in the PINTO folder `LICENSE` and in the MediaPipe Face Mesh V2 model card ("LICENSED UNDER Apache
    License, Version 2.0").
  - Text in `LICENSES/Apache-2.0.txt`.
- **Modified:**
  - The float32 weights are stored as float16. `tools/fp16_weights.py` adds a Cast back to float32 for each weight,
    and the computation stays in float32.
  - The file's ONNX `doc_string` states the change.
  - Original SHA-256 `70fe4e14169ca084b03b8103077a4051296e07939a19c1fdfd1f18b3792b4048` (4,955,225 bytes).
  - On a test face crop the 478 x 3 outputs of the converted model equal the original's exactly (maximum
    difference 0.0), because the original weights already fit in float16. Speed is also unchanged: 8.13 ms versus
    8.14 ms, ONNX Runtime Java 1.20, 1 thread.
- **Input:** `input_12`, float32 `[1, 3, 256, 256]`.
- **Outputs:**
  - `Identity` `[1, 1, 1, 1434]`: 478 x (x, y, z) in crop pixels; z is relative depth on the same scale.
  - `Identity_1` `[1, 1, 1, 1]`: face-presence logit; a sigmoid turns it into a probability.
  - `Identity_2` `[1, 1]`: a blendshape score (not used).
- **Preprocessing (MediaPipe face landmarker):**
  - A square region centred on the detector box, 1.5 times its longer side ("25% margin on each side" in the
    model card).
  - The region is rotated so that the line between the eyes is horizontal, then warped to 256 x 256.
  - RGB planes scaled to 0-1.
  - Landmarks are mapped back through the inverse warp.
- **Use restriction:**
  - The model card says it "is not intended for human life-critical decisions" and gives no identity.
  - We use it only to detect challenge actions (blink, smile, head turn, gaze), never for the PAD decision.

## MiniFASNetV2 and MiniFASNetV1SE passive anti-spoofing (`minifasnet-v2`, `minifasnet-v1se`)

- **Weights:**
  - Minivision `Silent-Face-Anti-Spoofing` (`https://github.com/minivision-ai/Silent-Face-Anti-Spoofing`, commit
    `b6d5f04ad78778917853b25c778acef6d5626d15`).
  - Files `resources/anti_spoof_models/2.7_80x80_MiniFASNetV2.pth` and `4_0_0_80x80_MiniFASNetV1SE.pth`.
- **ONNX files:**
  - Release assets `MiniFASNetV2.onnx` and `MiniFASNetV1SE.onnx` of `https://github.com/yakhyo/face-anti-spoofing`,
    release tag `weights`.
  - URLs: `https://github.com/yakhyo/face-anti-spoofing/releases/download/weights/MiniFASNetV2.onnx` and
    `.../MiniFASNetV1SE.onnx`. The repository was at commit `aea85c1fa14e4d52a7910af75d59ef51e62a2267`.
- **Licence:** Apache-2.0, in both repositories' `LICENSE`. Text in `LICENSES/Apache-2.0.txt`.
- **Shipped files:** unchanged.
- **Training data:** Minivision's own; not disclosed.
- **Input:** `input`, float32 `[N, 3, 80, 80]`.
- **Output:** `output`, float32 `[N, 3]` logits. After a softmax, class 1 is live; classes 0 and 2 are attack
  types.
- **Preprocessing (upstream, pinned by unit tests):**
  1. Face box as integers `[x, y, w, h]`.
  2. Crop box from `src/generate_patches.py::_get_new_box`:
     - scale = `min((H-1)/h, (W-1)/w, s)`, with s = 2.7 for V2 and 4.0 for V1SE;
     - the box is centred on the face, then shifted, not cut, to stay inside the image.
  3. Bilinear resize to 80 x 80.
  4. BGR planes with raw 0-255 values. The upstream `to_tensor` has `/255` commented out, and a Hugging Face model
     card that says to divide by 255 is wrong for these weights.
  5. Softmax. The ensemble averages the probabilities of the two models (upstream `test.py`).
- **Measured on the MockMDS stream frame** (Java, ONNX Runtime 1.20.0):

  | Input | Class probabilities | Live probability | Result |
  |---|---|---|---|
  | V2, raw 0-255 | [0.001, 0.881, 0.118] | 0.881 | live |
  | V1SE, raw 0-255 | [0.018, 0.216, 0.766] | 0.216 | attack |
  | Ensemble mean | — | 0.548 | argmax class 1 (live) |
  | V2, divided by 255 | [0.000, 0.007, 0.993] | 0.007 | attack |

  - The research notes of 2026-09-26 measured 0.72 for this image; it cut the crop at the image edge (as the yakhyo sample does)
    instead of shifting it as upstream does. With the cut crop our Python reference also gives 0.70.
- **Known weakness:** a vendor benchmark (axonlab.ai, updated 2026-04-22) reports high attack acceptance for 2-D
  attacks. The engine therefore adds active challenges, frame-integrity and planarity checks, and does not rely on
  this model alone.

## SFace face recognition (`sface`)

- **Source:** OpenCV Zoo, `models/face_recognition_sface/face_recognition_sface_2021dec.onnx`, commit
  `47534e27c9851bb1128ccc0102f1145e27f23f98`.
  - The model was trained by Yaoyao Zhong with the SFace loss (paper arXiv 2205.12010); the ONNX conversion is by
    Chengrui Wang.
- **Licence:** Apache-2.0 (`models/face_recognition_sface/LICENSE`). Text in `LICENSES/Apache-2.0.txt`.
- **Modified:**
  - The float32 weights are stored as float16 with Cast nodes (`tools/fp16_weights.py`). The graph inputs that
    duplicated the weights were also removed, so ONNX Runtime can fold constants.
  - The file's ONNX `doc_string` states the change.
  - Original SHA-256 `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` (38,696,353 bytes).
  - Embeddings of the converted model have cosine 0.999998 or more to the original's on the three test faces.
  - Speed on this machine (ONNX Runtime Java 1.20, 1 thread): 18.5 ms per face, versus 22.5 ms for the original.
- **Why not the int8 file:** the published `face_recognition_sface_2021dec_int8.onnx` (9,896,933 bytes) was about 8
  times slower on x86 in our measurement: 168-179 ms versus 19-24 ms, ONNX Runtime 1.30. Its embeddings had cosine
  0.94-0.98 to the float32 ones.
- **Training data:** The SFace paper trained on CASIA-WebFace, VGGFace2 and MS-Celeb-1M, which are research
  datasets. The dataset of the released model is not stated. We use the model only for 1:1 same-person and
  stream-to-capture checks, not for the PAD decision.
- **Input:** `data`, float32 `[1, 3, 112, 112]`.
- **Output:** `fc1`, float32 `[1, 128]`.
- **Preprocessing (OpenCV `FaceRecognizerSF`):**
  1. The five detector landmarks are aligned by a least-squares similarity (Umeyama) to the template
     (38.2946, 51.6963), (73.5318, 51.5014), (56.0252, 71.7366), (41.5493, 92.3655), (70.7299, 92.2041).
  2. Bilinear warp to 112 x 112.
  3. RGB planes with raw 0-255 values (`swapRB=true`, scale 1).
  4. We scale the 128 outputs to unit length.
  - The OpenCV Zoo sample treats cosine at or above 0.363 as the same person.
- **Measured** (Java, ONNX Runtime 1.20.0):
  - same image: 1.000;
  - MockMDS stream frame versus the MockMDS registration photo of the same person: 0.585;
  - versus the MockMDS exception photo of another person: 0.054.

## Refreshing the bundle

1. Put the new files here and edit `manifest.json`: id, file, version, licence, source and params. Raise
   `version` so that `model.min_version` can block rollback.
2. Recompute the hashes:
   `java -cp liveness-core.jar:liveness-onnx.jar io.mosip.liveness.onnx.tools.ModelSigner update --bundle models`.
3. Sign with the private key, kept outside the repository: `... ModelSigner sign --bundle models --private <key.pem>`.
4. Check the bundle: `... ModelSigner verify --bundle models`. The build also runs this check in
   `ModelRegistryTest` and `ModelSignerTest`.
