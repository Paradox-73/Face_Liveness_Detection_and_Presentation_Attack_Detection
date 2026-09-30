# SBI calls used by the clients

- **What:** MOSIP Secure Biometric Interface (SBI) 0.9.5 as the desktop client calls it on a face device service,
  plus the provisional PAD fields of MOSIP issue `android-registration-client#1117`. SBI 1.0 devices are called
  through the RC's existing 1.0 provider with the same timeouts.
- **Transport:** HTTP with custom methods on `http://127.0.0.1:<port>/`, the device service choosing the first free
  port in 4501-4600. Loopback only.
- **Authentication:** none on the request. Trust comes from the responses: the device information and every capture
  are JWS-signed by the device key, whose certificate must chain to a CA synced in the client's `DEVICE` trust
  domain (`MosipDeviceSpecificationHelper.validateJWTResponse`). L1 devices also sign the digital ID with the FTM
  key.
- **Used by:** `SbiFaceCaptureDevice` and `FaceStreamSession` (desktop liveness), through the RC's
  `MosipDeviceSpecification_095_ProviderImpl` / `_SBI_1_0_ProviderImpl`. On Android the built-in camera writes the
  same 0.9.5 capture format itself ([device-adapter.md](device-adapter.md)).

Examples below were taken from the MockMDS in this repository (`devices/mock-mds`, face device ID `3`) on
2026-09-30.

## 1. `MOSIPDISC /device` (discovery)

Request:

```bash
curl -s -X MOSIPDISC http://127.0.0.1:4501/device -d '{"type":"Face"}'
```

Response:

```json
[{"deviceId":"3","deviceStatus":"Ready","certification":"L0","serviceVersion":"0.9.5",
  "callbackId":"http://127.0.0.1:4501/","digitalId":"eyJzZXJpYWxObyI6IjIzNDU2Nzg5MDEiLC...",
  "deviceCode":"b692b595-3523-face-99fc-bd76e35f190f","purpose":"Registration",
  "error":{"errorCode":"0","errorInfo":"Success"},"specVersion":["0.9.5"],"deviceSubId":["0"]}]
```

`digitalId` (base64url, unsigned in discovery) decodes to
`{"serialNo":"2345678901","make":"MOSIP","model":"FACE01","type":"Face","deviceSubType":"Full face",
"deviceProviderId":"MOSIP.PROXY.SBI","deviceProvider":"MOSIP","dateTime":"2026-09-30T05:18:55Z"}`.

Liveness use: device choice, `deviceStatus` polling every 100 ms (up to 1,500 ms) until `Ready` after an
authentication stream is closed. The RC's discovery DTOs now ignore unknown fields (plan D25), so a device that adds
`padCapability` here does not break discovery.

## 2. `MOSIPDINFO /info` (signed device information)

```bash
curl -s -X MOSIPDINFO http://127.0.0.1:4501/info
```

Response: `[{"deviceInfo":"<JWS>","error":{...}}]`. The JWS payload repeats the discovery fields, signed with the
device key (certificate in `x5c`). Provisional #1117 extension, advertised only when MockMDS `/admin/pad` switches it
on: `"padCapability":{"methods":["passive"],"challenge":true}`.

Liveness use: `DeviceDescriptor.certification` (`L0`/`L1`) and `isPadCapable()`.

## 3. `STREAM /stream` (preview frames)

Request (the RC's `StreamRequestDTO`; `timeout` is `mosip.registration.liveness.stream.timeout_ms`, default 60,000,
instead of the RC's usual `capture_time_out`):

```bash
curl -s -X STREAM http://127.0.0.1:4501/stream -d '{"deviceId":"3","deviceSubId":"0","timeout":"60000"}'
```

Response headers and the start of the body, as MockMDS sends them:

```text
HTTP/1.0 200 OK
Server: http://127.0.0.1:4501
Access-Control-Allow-Origin:*
Connection: close

--BoundaryString
Access-Control-Allow-Origin:*
Content-Type:image/jpeg
Content-Length:16785

<16,785 bytes of JPEG>
--BoundaryString
...
```

Client behaviour: 2 s socket read timeout, 3 s first-frame deadline, parts validated as complete JPEGs, exactly one
terminal event ([../design/streaming.md](../design/streaming.md)). The stream ends on its timeout, on a client
disconnect, or after an `RCAPTURE` (stock MockMDS behaviour), so a retry opens a new stream. Errors before the body:
SBI error JSON, for example `{"errorCode":"608","errorInfo":"Live Stream Image not present"}` (camera unavailable).

## 4. `RCAPTURE /capture` (signed registration capture)

Request as `MosipDeviceSpecification_095_ProviderImpl.getRCaptureRequest` builds it (`env` from the client's
active profile, `requestedScore` from the capture request, `bioSubType` null for face; `previousHash` is `""` for a
first capture, a field SBI 0.9.5 requires and MockMDS rejects when `null`). Values are illustrative:

```json
{"env":"Staging","purpose":"Registration","specVersion":"0.9.5","timeout":"10000",
 "captureTime":"2026-09-30T10:50:01.123","transactionId":"<uuid>",
 "bio":[{"type":"Face","count":"1","bioSubType":null,"exception":[],"requestedScore":"90",
         "deviceId":"3","deviceSubId":"0","previousHash":""}],
 "customOpts":null}
```

Provisional #1117 addition for `padCapability` devices (not yet sent by the desktop client, plan D7):
`"pad":{"required":true,"method":"passive","challenge":"<session nonce>"}` inside `bio[0]`.

Response:

```json
{"biometrics":[{"specVersion":"0.9.5","data":"<JWS>","hash":"<hex>","error":{"errorCode":"0","errorInfo":"Success"}}]}
```

The JWS payload (`RCaptureResponseDataDTO`) has `digitalId`, `deviceCode`, `deviceServiceVersion`, `bioType`
(`Face`), `bioSubType`, `purpose`, `env`, `bioValue` (base64url ISO/IEC 19794-5 record with a JPEG 2000 face),
`transactionId`, `timestamp`, `requestedScore`, `qualityScore`; for `padCapability` devices also
`"pad":{"method":"passive","challenge":"<echo>","decision":"NOT_EVALUATED"}`.

Liveness use: `SignedCaptureReader` extracts the ISO record, capture time, device serial, echoed challenge and
device PAD decision; `LivenessGate` binds them to the session. With MockMDS the signed image is the last streamed
frame (`captureMode` `FRAME`), so the stored face is the face the client analysed.

## 5. Error codes seen by the liveness code

| SBI error | Meaning | Liveness result |
|---|---|---|
| 110 | device not ready | `DEVICE` (`NOT_READY` or `STREAM_OPEN_FAILED`) |
| 111 | device busy | `DEVICE` (`NOT_READY` or `STREAM_OPEN_FAILED`) |
| 608 | live stream image not present (camera unavailable) | `DEVICE` / `STREAM_OPEN_FAILED` |
| 609 | stream timeout | `onEnd(STREAM_TIMEOUT)` |
| 701 | capture timeout | `DEVICE` / `CAPTURE_FAILED` |

## 6. Use case: resident capture on the desktop

1. `MOSIPDISC` finds face device `3` (Ready, L0).
2. `STREAM` runs while the session checks passive PAD and challenges.
3. At `CAPTURE_READY` the RC sends `RCAPTURE` on the same device while the stream is paused.
4. The signed record is verified against the `DEVICE` trust domain, decoded and bound to the session; the face goes
   into the packet with `LIVENESS_DEVICE_LEVEL=L0`.

Mock device controls for testing these calls: [mock-mds-admin.md](mock-mds-admin.md).
