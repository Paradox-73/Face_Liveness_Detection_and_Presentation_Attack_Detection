# Android liveness performance, 2026-10-24

- Device: OnePlus CPH2569, ABI arm64-v8a, Android 15 (sdk 35), SoC SM7325, 6 CPU cores
- Frames per setting: 100 measured after 20 warm-up; frame 640x480 JPEG, steps timed together: decode, face analysis, passive PAD
- App APK: none
- Raw instrumentation output: raw/<provider>-t<threads>.txt; data: perf.csv

| Provider asked | Provider used | Threads | First frame (ms) | p50 (ms) | p95 (ms) | Max (ms) | Model load (ms) | Peak PSS dumpsys (MB) | Peak PSS test (MB) | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| CPU | CPU | 1 | 1780.02 | 78.70 | 481.20 | 882.62 | 2948 | 268.6 | 308.3 | OK |
| CPU | CPU | 2 | 389.52 | 58.08 | 92.32 | 940.71 | 629 | 256.7 | 310.1 | OK |
| CPU | CPU | 4 | 395.82 | 79.22 | 181.98 | 591.28 | 509 | 258.2 | 311.8 | OK |
| XNNPACK | XNNPACK | 1 | 903.21 | 74.39 | 123.15 | 299.69 | 933 | 238.5 | 349.0 | OK |
| XNNPACK | XNNPACK | 2 | 346.34 | 58.33 | 79.39 | 102.02 | 679 | 241.0 | 364.4 | OK |
| XNNPACK | XNNPACK | 4 | 381.85 | 94.65 | 139.97 | 156.46 | 646 | 240.8 | 352.2 | OK |
| NNAPI | NNAPI | 1 | 599.62 | 312.48 | 332.20 | 351.11 | 686 | 290.3 | 327.0 | OK |
| NNAPI | NNAPI | 2 | 692.06 | 334.39 | 401.00 | 512.36 | 694 | 289.6 | 327.3 | OK |
| NNAPI | NNAPI | 4 | 636.43 | 349.15 | 405.29 | 612.16 | 680 | 252.2 | 327.4 | OK |

Provider used differs from provider asked when the installed ONNX Runtime does not include the provider and the engine fell back to CPU.
