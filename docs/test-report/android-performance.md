# Android performance on a physical phone

Per-frame latency and memory of the liveness engine on a real Android phone, for bonus items B7 and B8. The only
Android numbers recorded so far come from an x86_64 emulator (section 5); the phone table (section 4) is empty
until the phone run has been done.

## 1. How to run it

Needs: a phone with USB debugging on, `adb` (Android platform-tools) on `PATH` or `ANDROID_HOME` set, and, to build
the benchmark, JDK 21 and the liveness engine installed in the local Maven repository (`scripts/android-build.sh`
does that; see [../install/android.md](../install/android.md)).

```
bash scripts/android-perf.sh --dry-run          # shows the plan; no phone needed
bash scripts/android-perf.sh --build            # builds the test APK, then runs CPU, XNNPACK, NNAPI x 1, 2, 4 threads
bash scripts/android-perf.sh --release          # also installs clients/android/build/app/outputs/flutter-apk/app-release.apk
```

Options: `--providers "CPU XNNPACK"`, `--threads "1 2"`, `--frames 200`, `--warmup 20`, `--apk PATH`,
`--no-install-app`, `--serial ID` (several phones attached), `--out DIR`. Run it with the screen on, the phone
unplugged or fully charged, and no other heavy app open; run twice and compare, because phones throttle when hot.

What it does:

- Installs the app APK (debug or release, if the file exists) and the instrumented test APK of the `livenessmanager`
  module. The benchmark itself is the instrumented test `LivenessPerformanceTest`
  (`clients/android/android/livenessmanager/src/androidTest/java/io/mosip/registration/livenessmanager/`), because a
  release APK cannot be instrumented. It uses the same engine code and the same signed model bundle as the app.
- For each provider and thread count it runs `adb shell am instrument` once. The test loads the models, times the first
  frame, runs the warm-up frames, then times the measured frames. Each timed frame is JPEG decode, face analysis
  (YuNet, Face Mesh, quality) and passive PAD (the two MiniFASNet models) on one 640x480 frame, the steps the capture
  screen runs on every frame. SFace (face matching) is not in the per-frame time.
- While the test runs, the script reads `adb shell dumpsys meminfo` of the test process once a second.
- Writes `run/perf/<date>/perf.csv`, `summary.md`, `device.txt` and the raw instrumentation output in `raw/`.
  `run/` is the folder next to the repository checkout.

Not covered: the camera and the screen (the frame is replayed), the Flutter UI, battery, temperature. The same
frame is used for every iteration, so a changing scene does not affect timings.

## 2. Columns

| Column | Meaning |
|---|---|
| `provider_requested` | ONNX Runtime execution provider asked for: `CPU` (default kernels), `XNNPACK` (optimised CPU kernels), `NNAPI` (Android Neural Networks API, may use the phone's NPU or GPU) |
| `provider_effective` | Provider actually used. Differs from the requested one when the installed runtime lacks it and the engine falls back to CPU; then the row measures CPU, not the requested provider |
| `threads` | Intra-op threads: how many CPU threads one model run may use (engine default is 2) |
| `frames` | Number of measured frames (after the warm-up) |
| `frame_size` | Decoded frame size, 640x480 |
| `model_load_ms` | Time to create all model sessions from the asset bundle |
| `first_frame_ms` | Time of the first frame after loading, including one-off start-up work of the provider; what the user waits for at the start of a session |
| `p50_ms`, `p95_ms` | Median and 95th percentile of the per-frame time (decode + analysis + PAD). The design target is p95 of at most 20 ms per frame (T-PERF-01, [../traceability.md](../traceability.md)) |
| `mean_ms`, `max_ms` | Mean and slowest measured frame |
| `peak_pss_dumpsys_kb` | Highest proportional set size (the process's own memory plus its share of shared pages) in kB that `dumpsys meminfo` showed, sampled once a second; short peaks between samples are missed |
| `peak_pss_test_kb` | Highest proportional set size read inside the test process (after load, after the first frame, every 10 frames) |
| `status` | `OK`, or `FAIL` when the test failed (for example the provider could not run); see `raw/<provider>-t<threads>.txt` |
| `date`, `manufacturer`, `model`, `abi`, `android`, `sdk`, `soc` | Phone and Android version, from `getprop` (`abi` is the CPU instruction set, for example `arm64-v8a`) |

## 3. What to compare

- B7, speed: p95 per frame for each provider and thread count against the 20 ms target, and `first_frame_ms`.
- B8, memory: `peak_pss_dumpsys_kb` for the setting the app ships with (CPU, 2 threads, from
  `mosip.registration.liveness.engine.execution_provider`, default CPU).
- NNAPI results depend on the phone's driver; a slower NNAPI row than CPU is a valid result.

## 4. Phone results

To be filled after the phone run.

| Phone (model, SoC) | Android | ABI | Provider used | Threads | First frame (ms) | p50 (ms) | p95 (ms) | Peak PSS (MB) | Date | Build |
|---|---|---|---|---|---|---|---|---|---|---|
| | | | CPU | 1 | | | | | | |
| | | | CPU | 2 | | | | | | |
| | | | CPU | 4 | | | | | | |
| | | | XNNPACK | 1 | | | | | | |
| | | | XNNPACK | 2 | | | | | | |
| | | | XNNPACK | 4 | | | | | | |
| | | | NNAPI | 1 | | | | | | |
| | | | NNAPI | 2 | | | | | | |
| | | | NNAPI | 4 | | | | | | |

## 5. Emulator numbers (for reference only; not a phone)

These come from the Android 14 x86_64 emulator run in [android-e2e.md](android-e2e.md) section 5, CPU inference with
ONNX Runtime 1.28.0 (the historic run; Android now ships 1.20.0, see [interoperability.md](interoperability.md)), 4 virtual CPUs, while about 25 other Java processes shared the host laptop. The emulator runs
the x86_64 build of the models through the host CPU, so the numbers say nothing about the speed of an arm64 phone.
They were measured inside the real app (`session.FrameTimer`: decoding, detection, landmarks, PAD while it runs,
challenge tracking), not by `android-perf.sh`.

| Kind of attempt | Attempts | Frames per attempt | p50 per frame | p95 per frame | Slowest frame |
|---|---|---|---|---|---|
| Passive check only | 9 | 6-8 | 57-251 ms | 295-998 ms | 998 ms |
| Passive check, then challenges | 6 | 81-141 | 37-45 ms | 106-374 ms | 1,151 ms |

- Engine start on the emulator: 1,069-1,138 ms (logcat, "Liveness engine ready").
- A repeat run on the emulator measured a median p50 of 150 ms over 17 attempts, so the spread between runs is large.
- Desktop for comparison (host JVM, not Android): p95 38-45 ms per frame, [../eval/calibration-notes.md](../eval/calibration-notes.md).
- Memory was not measured on the emulator.

## 6. Checks done without a phone

- `bash -n scripts/android-perf.sh` passes; `--help` and `--dry-run` print the plan without `adb`.
- `gradlew :livenessmanager:compileDebugAndroidTestJavaWithJavac` compiles `LivenessPerformanceTest`.
- The script's output parsing and summary were exercised against a stand-in `adb` that prints fixed values; nothing
  was run on a real phone or on the emulator, so the on-device path (test APK install, `am instrument`, `dumpsys`
  output formats on Android versions before 10) is untested.
