# Android liveness performance, 2026-10-24

- Device: OnePlus CPH2569, ABI arm64-v8a, Android 15 (sdk 35), SoC SM7325, 8 CPU cores
- Frames per setting: 100 measured after 20 warm-up; frame 640x480 JPEG, steps timed together: decode, face analysis, passive PAD
- App APK: none
- Raw instrumentation output: raw/<provider>-t<threads>.txt; data: perf.csv

| Provider asked | Provider used | Threads | First frame (ms) | p50 (ms) | p95 (ms) | Max (ms) | Model load (ms) | Peak PSS dumpsys (MB) | Peak PSS test (MB) | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| CPU | CPU | 1 | 323.68 | 84.77 | 97.18 | 117.21 | 415 | 261.2 | 311.7 | OK |
| CPU | CPU | 2 | 377.97 | 60.91 | 74.41 | 99.17 | 463 | 338.1 | 338.1 | OK |
| CPU | CPU | 4 | 365.01 | 61.44 | 74.95 | 86.83 | 451 | 317.0 | 318.4 | OK |
| XNNPACK | XNNPACK | 1 | 330.40 | 84.44 | 135.88 | 181.97 | 524 | 254.7 | 351.8 | OK |
| XNNPACK | XNNPACK | 2 | 390.97 | 71.34 | 83.72 | 107.54 | 495 | 330.1 | 374.7 | OK |
| XNNPACK | XNNPACK | 4 | 468.75 | 86.71 | 114.49 | 144.99 | 658 | 242.1 | 348.7 | OK |
| NNAPI | NNAPI | 1 | 852.20 | 467.91 | 513.58 | 539.67 | 760 | 292.5 | 331.1 | OK |
| NNAPI | NNAPI | 2 | 810.25 | 455.43 | 484.28 | 515.40 | 705 | 281.8 | 332.8 | OK |
| NNAPI | NNAPI | 4 | 815.48 | 431.81 | 481.46 | 757.30 | 706 | 287.3 | 331.6 | OK |

Provider used differs from provider asked when the installed ONNX Runtime does not include the provider and the engine fell back to CPU.
