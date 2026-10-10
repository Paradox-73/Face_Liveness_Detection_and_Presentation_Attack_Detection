# MockMDS admin API extensions

- **Service:** the mock face device in `devices/mock-mds` (MOSIP MockMDS 1.3.1 extended; Java 21). Start it with
  `bash scripts/mockmds-run.sh` (run folder outside git, frame packs allowed as sources) or from
  `devices/mock-mds/target` with `run_reg.bat`.
- **Base URL:** `http://127.0.0.1:<port>`, first free port 4501-4600 (4501 in every run so far). Binds to loopback
  only.
- **Purpose:** drive the mock L0/L1 face device from tests and demos: choose what the "camera" shows, inject device
  faults, switch the certification level, and control the provisional PAD capability.
- **Authentication:** none (loopback). Protections: each call must be listed in `mosip.mock.sbi.allowed.admin.apis`
  (else error 516); requests with an `Origin` header (web browsers) are refused (517); `path` and `file` must lie
  under `mosip.mock.sbi.face.source.allowed.roots` (511).
- **Bodies:** JSON; numbers may be JSON numbers or strings; optional `"type":"Face"`.

Stock calls (`/admin/status`, `/admin/score`, `/admin/delay`, `/admin/profile`) are unchanged. Complete reference:
`devices/mock-mds/LIVENESS-EXTENSIONS.md`.

## 1. Endpoints

| Call | Body fields | Effect |
|---|---|---|
| `POST /admin/source` | `source` (`STATIC`, `SEQUENCE`, `FFMPEG`), `path`, `fps` (at most 60), `loop`, `camera`, `file`, `inputFormat`, `width`, `captureMode` (`FRAME`, `PROFILE`) | Select the frame source; switching during a stream takes effect on the next frame |
| `POST /admin/fault` | `fault`, `afterFrames`, `frames`, `dropRatio`, `fps`, `delayMs`, `durationMs`, `lengthDelta` | Set one fault (`NONE` clears it) |
| `POST /admin/certification` | `certification` (`L0`, `L1`) | L1 signs the digital ID with the FTM key |
| `POST /admin/pad` | `padCapability`, `discovery`, `decision` (`NOT_EVALUATED`, `NO_ATTACK`, `ATTACK`) | Advertise `padCapability`; echo `bio[].pad.challenge` in captures |
| `GET /admin/liveness` (or `POST`) | none | Read the state; changes nothing |

Faults: `NONE`, `DISCONNECT` (FIN), `RESET` (RST), `STALL`, `FREEZE`, `DROP_FRAMES`, `CORRUPT_JPEG`,
`TRUNCATED_JPEG`, `NON_JPEG`, `BAD_CONTENT_LENGTH`, `SLOW_FPS` (or `FPS=<n>`), `BUSY`, `NOT_READY`, `UNAVAILABLE`.

## 2. Responses

Success: `{"errorCode":"0","errorInfo":"Success","state":{...}}`. Failure: `{"errorCode":"<code>","errorInfo":"<text>"}`
and nothing changes.

Real response of `GET /admin/liveness` on the shared MockMDS (2026-09-30):

```json
{"errorCode":"0","errorInfo":"Success","state":{
  "source":{"source":"SEQUENCE","path":"E:\\Projects\\MOSIP\\run\\frame-packs\\s3-main","fps":15.0,"loop":true,
            "frameCount":59,"sessionOpen":false,"framesServed":5295,"capturesTaken":84,
            "lastCapturedFrame":{"sequence":0,"timestamp":1790725217849,"bytes":16785,
                                 "sha256":"632749b6e26e1356440769d80e6800a903308cff58c52c68a52ff0c37bb03aee"}},
  "fault":{"fault":"NONE","afterFrames":0,"frames":0,"delayMs":0,"framesInStream":0},
  "device":{"certification":"L0","padCapability":false,"padInDiscovery":false,"padDecision":"NOT_EVALUATED",
            "captureMode":"FRAME","defaultQualityScore":95.0,"purpose":"Registration","deviceStatus":"Ready",
            "qualityScore":95.0}}}
```

Frames are identified by SHA-256 only; no image data is returned or logged.

| Error code | Meaning |
|---|---|
| 503 | no face device in this service |
| 511 | invalid source request (unknown type, missing path, path outside the allowed roots, bad fps or format) |
| 512 | new source could not be opened (ffmpeg missing, camera busy); the old source stays |
| 513 | invalid fault request |
| 514 | certification not `L0` or `L1` |
| 515 | invalid PAD request |
| 516 | call not listed in `mosip.mock.sbi.allowed.admin.apis` |
| 517 | request carried an `Origin` header |

## 3. Use case: disconnect the camera during a check, then recover (desktop run D2)

```bash
# 1. The "camera" shows a live frame pack.
curl -s -X POST http://127.0.0.1:4501/admin/source \
  -d '{"type":"Face","source":"SEQUENCE","path":"<run root>/frame-packs/s2-main","fps":15,"loop":true}'
# 2. Drop the connection after 6 frames of every stream.
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"DISCONNECT","afterFrames":6}'
#    Client: "The face camera was disconnected. Reconnect it and select Retry." (no attempt used, audit LV12)
# 3. "Reconnect" the camera.
curl -s -X POST http://127.0.0.1:4501/admin/fault -d '{"type":"Face","fault":"NONE"}'
#    Client: Retry, passive check passes.
```

The same pattern drives the other demo cases: `source` to an attack pack (print, screen replay), `FREEZE` or the
`STATIC` source for an injected still stream, `certification` `L1` for the L1 device flow, `UNAVAILABLE` for a
missing camera. The desktop flow scripts (`tools/mock-sync/e2e/flows/*.flow`) call these endpoints.

Frame packs are made from openly licensed clips by `tools/eval/fetch-frame-packs.py` and kept outside git
(`run/frame-packs/`); they must be under `mosip.mock.sbi.face.source.allowed.roots`, which `scripts/mockmds-run.sh`
sets.
