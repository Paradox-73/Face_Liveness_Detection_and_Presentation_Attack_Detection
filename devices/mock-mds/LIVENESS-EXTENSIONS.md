# MockMDS face liveness extensions

This copy of MOSIP MockMDS (`mosip-mock-services/MockMDS` at `3d2174d`, MPL-2.0) acts as a mock L0/L1 face device for testing face liveness and presentation attack detection (PAD) in the Registration Client:

- **STREAM** sends real, changing frames from a pluggable frame source: one JPEG (stock behaviour), a folder of JPEGs, or ffmpeg (webcam or video file).
- **RCAPTURE** builds the signed ISO/IEC 19794-5 face record from the last frame written to STREAM, so the signed image is the image the client analysed.
- **Fault injection** over an admin API: disconnect, reset, stall, freeze, dropped frames, corrupt/truncated/non-JPEG parts, wrong Content-Length, low frame rate, busy, not ready, camera unavailable, extra delay.
- **L0/L1 switch** (L1 signs the digitalId with the FTM key) and an optional `padCapability` with challenge echo, following MOSIP issue `android-registration-client#1117`.

With the defaults, stock clients keep working:

- Discovery and device info have the same fields and values as stock MockMDS.
- Two things differ from stock: the RCAPTURE face image is the streamed frame (not `Face.iso`), and the face quality score is 95 (not 44).

## 1. Build and run

Requires Java 21 and Maven 3.9. ffmpeg is needed only for the `FFMPEG` source; it is **not bundled** (GPL). Install it separately and put it on `PATH`, or set `mosip.mock.sbi.face.ffmpeg.path`.

```sh
cd devices/mock-mds
mvn -DskipTests package        # the pom forces surefire skipTests=false, so the tests still run (about 1 minute)
cd target
run_reg.bat                    # Windows; Linux/macOS: sh run.sh
# same as: java -cp "mock-mds-1.3.1.jar;lib/*" io.mosip.mock.sbi.test.TestMockSBI "mosip.mock.sbi.device.purpose=Registration" "mosip.mock.sbi.biometric.type=Biometric Device"
```

- Run from `target/`: `application.properties`, `Biometric Devices/` and `Profile/` are read from the working directory.
- The service listens on the first free port from 4501 to 4600 on 127.0.0.1.
- Settings are in `target/application.properties` (a copy made from `application.properties` at build time).

Quick check:

```sh
curl -s -X MOSIPDISC http://127.0.0.1:4501/device -d '{"type":"Face"}'
curl -s -X STREAM http://127.0.0.1:4501/stream -d '{"deviceId":"3","deviceSubId":"0","timeout":"3000"}' -o stream.bin
curl -s http://127.0.0.1:4501/admin/liveness
```

## 2. Frame sources

| Source | What it serves | Needs |
|---|---|---|
| `STATIC` (default) | One JPEG file as every frame, read again for every frame (stock behaviour; an external program may keep replacing the file) | nothing |
| `SEQUENCE` | Every `.jpg`/`.jpeg` file of a folder in file-name order, looping or once; each STREAM restarts at the first file | nothing |
| `FFMPEG` | Webcam (`dshow` on Windows, `v4l2` on Linux, `avfoundation` on macOS) or a video file, read from `ffmpeg -f image2pipe -vcodec mjpeg pipe:1`; only the newest frame is kept | ffmpeg |

Behaviour common to all sources:

- STREAM is paced at the source's `fps` (default 15, maximum 60) instead of the stock fixed 30 ms pause.
- The source is opened when a STREAM starts and closed when the device is de-initialised, so the webcam is on only while streaming. A webcam takes about 1.2 s to deliver its first frame (measured with the laptop's Integrated Camera).
- If the source cannot be opened, STREAM answers error `608` before any stream header.
- A source can be switched during a stream (`POST /admin/source`); the next frames come from the new source on the same connection.
- `FFMPEG` refuses webcam names containing `OBS`, `ManyCam`, `XSplit` or `Virtual` unless `mosip.mock.sbi.face.ffmpeg.allow.virtual.camera=true`, because a virtual camera can inject any video as if it were the sensor.

### 2.1 Start-up configuration (`application.properties`)

| Key | Default | Meaning |
|---|---|---|
| `mosip.mock.sbi.face.source.type` | `STATIC` | `STATIC`, `SEQUENCE` or `FFMPEG` |
| `mosip.mock.sbi.face.source.fps` | `15` | frames per second |
| `mosip.mock.sbi.face.source.path` | empty | `STATIC`: JPEG file (empty = `mosip.mock.sbi.file.face.streamimage`); `SEQUENCE`: folder |
| `mosip.mock.sbi.face.source.loop` | `true` | restart `SEQUENCE` or an ffmpeg video file at the end |
| `mosip.mock.sbi.face.source.camera` | empty | ffmpeg webcam name, for example `Integrated Camera` |
| `mosip.mock.sbi.face.source.file` | empty | ffmpeg video file |
| `mosip.mock.sbi.face.source.input.format` | by OS | `dshow`, `v4l2` or `avfoundation` |
| `mosip.mock.sbi.face.source.width` | `640` | ffmpeg output width; height keeps the aspect ratio |
| `mosip.mock.sbi.face.source.allowed.roots` | `.` | folders (separated by `;`) that paths given to `/admin/source` must lie under |
| `mosip.mock.sbi.face.ffmpeg.path` | `ffmpeg` | ffmpeg executable (properties only, never from the admin API) |
| `mosip.mock.sbi.face.ffmpeg.allow.virtual.camera` | `false` | allow virtual camera names |
| `mosip.mock.sbi.face.ffmpeg.input.args` | empty | raw ffmpeg input arguments separated by spaces, used when no camera or file is set (properties only), for example `-re -f lavfi -i testsrc=size=640x480:rate=15` |
| `mosip.mock.sbi.face.capture.mode` | `FRAME` | RCAPTURE image: `FRAME` (last streamed frame) or `PROFILE` (stock `Profile/<id>/Registration/Face.iso`) |
| `mosip.mock.sbi.face.quality.score` | `95` | face quality score when `/admin/score` has not set one |
| `mosip.mock.sbi.face.certification` | `L0` | `L0` or `L1`; empty keeps `DeviceInfo.json` |
| `mosip.mock.sbi.face.pad.capability` | `false` | advertise `padCapability` in the signed DeviceInfo |
| `mosip.mock.sbi.face.pad.discovery` | `false` | also advertise it in discovery (stock clients then fail to parse discovery) |
| `mosip.mock.sbi.face.pad.decision` | `NOT_EVALUATED` | decision echoed in RCAPTURE: `NOT_EVALUATED`, `NO_ATTACK` or `ATTACK` |
| `mosip.mock.sbi.face.admin.allow.browser` | `false` | accept face admin requests that carry an `Origin` header |

Examples:

```properties
# replay a folder of frames at 10 fps, once
mosip.mock.sbi.face.source.type=SEQUENCE
mosip.mock.sbi.face.source.path=frames/live-01
mosip.mock.sbi.face.source.fps=10
mosip.mock.sbi.face.source.loop=false

# webcam
mosip.mock.sbi.face.source.type=FFMPEG
mosip.mock.sbi.face.source.camera=Integrated Camera

# replay-attack video, looping
mosip.mock.sbi.face.source.type=FFMPEG
mosip.mock.sbi.face.source.file=clips/replay-phone.mp4
```

List webcam names on Windows with `ffmpeg -hide_banner -list_devices true -f dshow -i dummy`.

## 3. STREAM and RCAPTURE

- **STREAM** keeps the stock wire format: `multipart/x-mixed-replace; boundary=--BoundaryString`, each part `Content-Type:image/jpeg` and `Content-Length:<n>`. It ends on the request timeout (`609`), when the client disconnects, when a `SEQUENCE` without loop or a video file ends, or after an RCAPTURE (stock behaviour).
- **RCAPTURE during a stream** (capture mode `FRAME`):
  1. The stream stops adding frames once the capture has started and at least one frame was streamed (SBI 0.9.5: "When the Device Registration Capture API is called the frames should not be added to the stream").
  2. The last intact frame written to STREAM is converted JPEG → JPEG 2000 (in memory, lossy, 2 bits per pixel, the settings of the stock `ImageHelper.toJ2000`) → ISO/IEC 19794-5:2011 record (`BioUtilHelper.getFaceIsoFromJP2000`, MOSIP biometrics-util).
  3. The record is signed in the stock way. The exception photo still comes from the profile.
  4. If the capture starts before any frame was streamed, it waits for the first frame; frames damaged by a fault are never used.
- **RCAPTURE without a stream** opens the source, takes one frame, and closes the source.
- **Capture mode `PROFILE`** returns the stock profile `Face.iso`. This is useful when a flow relies on MOSIP's mock biometric SDK, which matches faces by comparing image hashes.
- **Quality score:** 95 by default, so a first capture passes a face threshold of 90 without being force-captured. `POST /admin/score` for Face still overrides it.
- **Measured on this machine:** RCAPTURE during a SEQUENCE stream returned the frame the client received last (same SHA-256). The decoded JPEG 2000 image had a PSNR of 37.5 dB against that frame and 10.1 dB against the first frame.

## 4. Admin API

Existing stock calls are unchanged: `POST /admin/status`, `/admin/score`, `/admin/delay`, `/admin/profile`. New calls:

| Call | Purpose |
|---|---|
| `POST /admin/source` | select the frame source and/or capture mode |
| `POST /admin/fault` | set or clear a fault |
| `POST /admin/certification` | switch L0 / L1 |
| `POST /admin/pad` | control `padCapability` and the echoed PAD decision |
| `GET /admin/liveness` (or `POST`) | read the state; changes nothing |

Rules for the new calls:

- **Authentication:** none; the service binds to 127.0.0.1 only.
- **Allow list:** each call must be listed in `mosip.mock.sbi.allowed.admin.apis`, otherwise error `516`. The stock code never checked this list; the new calls do.
- **Browser requests refused:** requests with an `Origin` header (sent by web browsers) get error `517`. MockMDS answers any origin (CORS `*`), so otherwise any web page could point the device at the webcam or at local files.
- **Paths:** `path` and `file` must lie under `mosip.mock.sbi.face.source.allowed.roots`, otherwise error `511`. Relative paths are resolved against the working directory.
- **`type`:** optional; if present it must be `Face` or `Biometric Device`.
- **Numbers:** may be JSON numbers or strings.
- **Response on success:** `{"errorCode":"0","errorInfo":"Success","state":{...}}`, where `state` is the object returned by `GET /admin/liveness`.
- **Response on failure:** `{"errorCode":"<code>","errorInfo":"<text> <detail>"}`, and nothing is changed.

| Code | Meaning |
|---|---|
| 503 | no face device in this service |
| 511 | invalid source request (unknown type, missing path, path outside the allowed roots, bad fps, bad input format) |
| 512 | the new source could not be opened (for example ffmpeg missing or camera busy); the old source stays |
| 513 | invalid fault request |
| 514 | certification is not `L0` or `L1` |
| 515 | invalid PAD request |
| 516 | call not listed in `mosip.mock.sbi.allowed.admin.apis` |
| 517 | request carried an `Origin` header |

### 4.1 `POST /admin/source`

| Field | Type | Meaning |
|---|---|---|
| `source` | string | `STATIC`, `SEQUENCE` or `FFMPEG` (aliases `IMAGE_SEQUENCE`, `FOLDER`, `WEBCAM`, `CAMERA`, `VIDEO`); omit to change only `captureMode` |
| `path` | string | `STATIC` JPEG file (omit for the stock image) or `SEQUENCE` folder |
| `fps` | number | frames per second, above 0 and at most 60; omitted = current value |
| `loop` | boolean | `SEQUENCE` / video file restart at the end; omitted = current value |
| `camera` | string | `FFMPEG` webcam name |
| `file` | string | `FFMPEG` video file |
| `inputFormat` | string | `dshow`, `v4l2` or `avfoundation` |
| `width` | integer | `FFMPEG` output width, 16 to 4096 |
| `captureMode` | string | `FRAME` or `PROFILE` |

The new source is opened once to check it (a webcam must deliver a frame within 10 s). During a stream it replaces the old source at once; otherwise it is closed again until the next stream.

```sh
curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"SEQUENCE","path":"frames/live-01","fps":15,"loop":true}'
curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"FFMPEG","camera":"Integrated Camera","fps":10}'
curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"FFMPEG","file":"clips/replay-phone.mp4","loop":true}'
curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","source":"STATIC"}'
curl -s -X POST http://127.0.0.1:4501/admin/source -d '{"type":"Face","captureMode":"PROFILE"}'
```

Response (from a real run; paths shortened):

```json
{"errorCode":"0","errorInfo":"Success","state":{
  "source":{"source":"SEQUENCE","path":"C:\\...\\manual\\seq","fps":15.0,"loop":true,"frameCount":45,
            "sessionOpen":false,"framesServed":0,"capturesTaken":0},
  "fault":{"fault":"NONE","afterFrames":0,"frames":0,"delayMs":0,"framesInStream":0},
  "device":{"certification":"L0","padCapability":false,"padInDiscovery":false,"padDecision":"NOT_EVALUATED",
            "captureMode":"FRAME","defaultQualityScore":95.0,"purpose":"Registration","deviceStatus":"Ready","qualityScore":95.0}}}
```

After frames were streamed and captured, `state.source` also holds `lastServedFrame` and `lastCapturedFrame`: `{"sequence":23,"timestamp":1790597367701,"bytes":22198,"sha256":"d229a235..."}`. These identify frames by SHA-256 digest only; no image data is returned or logged.

### 4.2 `POST /admin/fault`

One fault is active at a time; a new call replaces it, `{"fault":"NONE"}` clears it. Faults persist across streams; frame counts restart with every STREAM.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `fault` | string | required | see the table below |
| `afterFrames` | integer | 0 | frames of each stream that pass unaltered before the fault starts |
| `frames` | integer | 0 | frames the fault lasts once started; 0 = until cleared |
| `dropRatio` | number | 0.5 | `DROP_FRAMES`: share of frames skipped, above 0 and at most 1 (deterministic: 0.5 skips every second frame) |
| `fps` | number | 2 | `SLOW_FPS` frame rate |
| `delayMs` | integer | 0 | extra delay before every frame, 0 to 60000 ms, with any fault including `NONE` |
| `durationMs` | integer | 0 | `STALL` length; 0 = until cleared or the stream timeout |
| `lengthDelta` | integer | 512 | `BAD_CONTENT_LENGTH`: bytes added to the true length (may be negative) |

| Fault (aliases) | Effect | T-MOCK test |
|---|---|---|
| `NONE` (`CLEAR`) | no fault | — |
| `DISCONNECT` (`FIN`, `CLOSE`) | clean TCP close (FIN) of the STREAM connection | T-MOCK-09 |
| `RESET` (`RST`) | TCP reset of the STREAM connection | T-MOCK-02 |
| `STALL` | connection stays open, no frames | T-MOCK-08 |
| `FREEZE` | the first affected frame is repeated | T-MOCK-11 (also the `STATIC` source) |
| `DROP_FRAMES` (`DROP`) | skip a share of frames (gaps in the stream) | — |
| `CORRUPT_JPEG` (`CORRUPT`, `GARBLED`) | JPEG markers intact, entropy-coded data damaged | T-MOCK-07 |
| `TRUNCATED_JPEG` (`TRUNCATE`) | first 60 % of the JPEG, no end marker, matching Content-Length | T-MOCK-07 |
| `NON_JPEG` | ASCII text (no `0xFF` byte) sent as `image/jpeg` | T-MOCK-07 |
| `BAD_CONTENT_LENGTH` | Content-Length differs from the part size by `lengthDelta` | T-MOCK-07 |
| `SLOW_FPS` (`SLOW`, `FPS`, `FPS=<n>`) | stream at `fps` (or n) | T-MOCK-10 |
| `BUSY` | device status Busy (discovery error 111, STREAM refused 111), same as `/admin/status` | — |
| `NOT_READY` | device status Not Ready (110) | T-MOCK-01 |
| `UNAVAILABLE` (`CAMERA_UNAVAILABLE`) | STREAM answers `608` before any header; captures without a stream time out (`701`) | T-MOCK-03 |

Clearing `BUSY` or `NOT_READY` (with any other fault) sets the status back to Ready. Damaged frames (corrupt, truncated, non-JPEG, wrong length) are never used for RCAPTURE.

```sh
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"DISCONNECT","afterFrames":5}'
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"FREEZE","afterFrames":30,"frames":45}'
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"FPS=2"}'
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"DROP_FRAMES","dropRatio":0.3,"delayMs":50}'
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"NONE"}'
```

Results measured with curl against a 15 fps `SEQUENCE`:

| Fault | Result |
|---|---|
| `DISCONNECT afterFrames=5` | exactly 5 frames, then EOF; curl exited 0 after 618 ms |
| `FREEZE afterFrames=3` | 15 frames in 1 s, 4 distinct |
| `FPS=3` | 6 frames in 2 s |
| `NON_JPEG afterFrames=2 frames=2` | 2 text parts |
| `UNAVAILABLE` | `{"errorCode":"608","errorInfo":"Live Stream Image not present"}` |

### 4.3 `POST /admin/certification`

```sh
curl -s -X POST http://127.0.0.1:4501/admin/certification -d '{"type":"Face","certification":"L1"}'
```

- **What changes:** `certification` in discovery and in the signed DeviceInfo.
- **L1 signing:** for L1 the DeviceInfo `digitalId` is signed with the FTM key `Biometric Devices/Face/Keys/mosipfaceftm.p12`. Its certificate is `CN=MosipFaceFTM`, issued by `CN=RootCAFTM` (`Biometric Devices/rootcaftm.cer`).
  - The DeviceInfo JWT and the capture data stay signed with the device key `mosipface.p12` (`CN=MosipFace`, issued by `CN=RootCA`).
  - A client that checks the digitalId in its `DEVICE` trust domain needs `rootcaftm.cer` in that domain.
- **Re-discovery:** clients cache the level, so they must discover the device again after a switch.
- **Android client:** the stock Android client accepts only L0 devices.

### 4.4 `POST /admin/pad`

| Field | Type | Meaning |
|---|---|---|
| `padCapability` | boolean | advertise `padCapability` in the signed DeviceInfo |
| `discovery` | boolean | also advertise it in discovery |
| `decision` | string | decision echoed in RCAPTURE: `NOT_EVALUATED`, `NO_ATTACK` or `ATTACK` |

```sh
curl -s -X POST http://127.0.0.1:4501/admin/pad -d '{"type":"Face","padCapability":true}'
```

- **Advertised object (provisional shape, pending the final SBI text for #1117):** `"padCapability": {"methods": ["passive"], "challenge": true}`.
- **Default is off.** With it off, DeviceInfo and discovery have exactly the stock fields.
- **Where it may go:**
  - The stock desktop client ignores unknown DeviceInfo fields (`MdmDeviceInfo` has `@JsonIgnoreProperties(ignoreUnknown = true)`).
  - The stock desktop client fails on unknown discovery fields (`DeviceDiscoveryMDSResponse` has no such annotation), so `discovery=true` is for patched clients only.
- **Challenge echo:** when `padCapability` is on and an RCAPTURE request carries `bio[0].pad`, the signed data block of the response contains:

```json
"pad": {"method": "passive", "challenge": "<bio[0].pad.challenge>", "decision": "NOT_EVALUATED"}
```

Request fragment: `"bio":[{"type":"Face","count":"1",...,"pad":{"required":true,"method":"passive","challenge":"c29tZS1ub25jZQ"}}]`. Requests without `pad` get no `pad` field.

- **Decision value:** the mock does not run PAD itself, so the default decision is `NOT_EVALUATED`.

### 4.5 `GET /admin/liveness`

```sh
curl -s http://127.0.0.1:4501/admin/liveness
```

Returns the success envelope with `state` as in 4.1, and changes nothing.

## 5. Tests

`mvn test` runs 226 tests: the 177 upstream tests plus 49 new JUnit 5 tests. All passed on this machine (Windows 11, Temurin 21.0.12, Maven 3.9.9, ffmpeg 9.0).

| Class | What it checks |
|---|---|
| `devicehelper/face/source/JpegStreamSplitterTest` | ffmpeg output split into exact JPEGs; `FFD9` inside a metadata segment does not cut a frame; cut-off frame dropped |
| `devicehelper/face/source/FaceFrameSourcesTest` | `SEQUENCE` frames in name order, all different, loop / once, restart on open; `STATIC` repeats and follows a replaced file; factory and settings |
| `devicehelper/face/source/FfmpegPipeSourceTest` | command lines (dshow, v4l2, avfoundation, file loop), virtual-camera and quote refusal, missing ffmpeg; with ffmpeg on `PATH`: live test pattern gives changing decodable frames, finite input ends the source |
| `devicehelper/face/fault/FaceFaultInjectorTest` | every fault mode, `afterFrames` / `frames` windows, stall duration, drop ratio, aliases, validation |
| `devicehelper/face/SBIFaceHelperLivenessTest` | ISO built from the last streamed frame decodes to that frame (mean difference < 4 grey levels); capture waits for a first frame; `PROFILE` mode returns `Face.iso`; score 95; L1 digitalId signed by `CN=MosipFaceFTM`; `padCapability` and echo |
| `service/FaceLivenessEndToEndTest` | a real MockMDS on a loopback port driven over raw HTTP: frames in order at 15 fps; RCAPTURE during STREAM signs a frame the client received; DISCONNECT gives exactly 5 frames then clean EOF; RST gives a connection error; FREEZE; FPS=4; UNAVAILABLE gives 608 on STREAM and 701 on RCAPTURE with the device Ready again; BUSY; source switch mid-stream; L1; `padCapability` and echo; admin refusals |

Test frames are drawn at run time from the stock `0.jpeg`, so no binary fixtures are added. The end-to-end test writes them under `target/test-frames/`.

## 6. Changed upstream files

| File | Change | Reason |
|---|---|---|
| `src/main/java/io/mosip/mock/sbi/devicehelper/face/SBIFaceHelper.java` | `getLiveStream` reads the frame source; `getBioCapture` dispatches to capture-from-frame or the unchanged stock code (now `getBioCaptureFromProfile`); overrides for quality score, certification / FTM signing, `padCapability`; PAD echo; `deInitDevice` closes the source | frame sources, capture binding, L1, #1117 |
| `src/main/java/io/mosip/mock/sbi/service/SBIServiceResponse.java` | routes the 5 new admin calls; `processLiveStreamInfo` opens the face source before the stream header (608 on failure), writes face frames through `FaceStreamWriter` and paces them instead of `Thread.sleep(30)`; `getBiometricData` adds the PAD echo; `processRCaptureInfo` skips `getBioExceptionInfo().deInitBioException()` when the capture info has none | admin API, faults, pacing, #1117; the stock code threw a NullPointerException after a face capture timeout (error 710) and left the device Busy until restart. Stock face captures never timed out, so the bug was hidden |
| `src/main/java/io/mosip/mock/sbi/service/SBIWorker.java` | logs only the size of RCAPTURE/CAPTURE responses | the response holds the face image, which can now be a real person's face |
| `src/main/java/io/mosip/mock/sbi/SBIConstant.java` | request-line constants of the new admin calls | routing, same pattern as the stock calls |
| `src/main/java/io/mosip/registration/mdm/dto/DeviceInfo.java`, `DiscoverDto.java` | nullable `padCapability`, omitted when null | #1117; stock output unchanged by default |
| `src/main/java/io/mosip/registration/mdm/dto/BioMetricsDataDto.java` | nullable `pad`, omitted when null | challenge echo |
| `application.properties` | new calls in `mosip.mock.sbi.allowed.admin.apis`; `mosip.mock.sbi.face.*` keys; error texts 511-517 | configuration |
| `run_reg.bat`, `run_auth.bat`, `run.sh` | jar name `mock-mds-${project.version}.jar` (filled in by the build) instead of `mock-mds-1.3.0-SNAPSHOT.jar` | the pom builds `mock-mds-1.3.1.jar`; the stock scripts pointed at a missing jar |
| `README.md` | pointer to this file | discoverability |

New code:

- `io.mosip.mock.sbi.devicehelper.face.source`: frame sources, JPEG splitter, settings.
- `io.mosip.mock.sbi.devicehelper.face.fault`: faults.
- `io.mosip.mock.sbi.devicehelper.face`: `FaceFrameFeed`, `FaceDeviceSettings`, `FaceIsoBuilder`.
- `io.mosip.mock.sbi.service`: `SBIFaceLivenessAdmin`, `FaceStreamWriter`.
- `io.mosip.registration.mdm.dto`: `FaceSourceRequest`, `FaceFaultRequest`, `CertificationRequest`, `PadRequest`.

## 7. Known limitations

- **Log noise after DISCONNECT and RESET:** `SBIWorker` logs an error, because it still tries to write the final JSON to the closed connection (stock code path).
- **STALL and client departure:** during `STALL` a client that gives up is noticed only at the next write, after the stall ends or the fault is cleared. The device stays Busy until then or until the stream timeout.
- **Any web page can read STREAM:** MockMDS answers any origin, so while a webcam source streams, any local web page could read the frames. Admin calls from browsers are refused, but STREAM itself is stock SBI behaviour.
- **`STATIC` is frozen by design:** a liveness client should reject it as a frozen stream.
- **Not implemented from plan §4.14:**
  - `PlaylistSource`: scripted segments can be done by calling `/admin/source` during a stream.
  - `OpenCvCameraSource`.
  - Demo control page on port 4700.
  - The ISO/IEC 39794-5 PAD data block.
- **Deviation from plan §4.14:** the plan advertises `padCapability` in DeviceInfo always. Here it is off by default (`mosip.mock.sbi.face.pad.capability=false`), so default output equals stock MockMDS.
- **`run.sh` line endings:** `run.sh` keeps the upstream CRLF line endings, which `/bin/sh` on Linux may reject. Convert it with `dos2unix` before use there.
