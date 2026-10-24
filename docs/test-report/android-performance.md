# Android performance on a physical phone

Per-frame latency and memory of the liveness engine on a real Android phone, for bonus items B7 and B8. The phone was
measured on 2026-10-24 (section 4); the earlier x86_64 emulator numbers are kept in section 5 for reference.

## 1. How to run it

The team's step for this is `MT-AND-12` in [../manual-test-checklist.md](../manual-test-checklist.md).

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

Two runs of `scripts/android-perf.sh` on 2026-10-24 on one phone, no app APK installed (the benchmark is the
instrumented test). Run 1 started 23:05, run 2 at 23:09 (IST). Files: [android-perf-20261024/](android-perf-20261024/)
(`run1/` and `run2/`, each with `summary.md`, `perf.csv`, `device.txt` and `raw/`).

| Fact | Value |
|---|---|
| Phone | OnePlus CPH2569, Android 15 (sdk 35), SoC SM7325, ABI `arm64-v8a`, 11 GiB RAM |
| CPU cores reported | 6 in `device.txt` of run 1, 8 in run 2 (the script reads the count from the phone each time; the phone did not change) |
| Power | Charging over USB, battery about 40%. A charging phone can throttle its CPU, so the numbers may be slower than on an unplugged, cool phone. Temperature was not logged. |
| Frames | 100 measured after 20 warm-up, per setting. One JPEG, reported by the test as 490x360 (the script text says 640x480); the same frame every iteration |
| Timed together | JPEG decode, face analysis (YuNet, Face Mesh, quality) and passive PAD (two MiniFASNet models) |
| Not timed | SFace face matching, camera, screen, Flutter UI |
| Engine | The signed model bundle and engine code the app uses; 9 settings per run, all `OK`, and `provider_effective` equals the provider asked for in every row (no fallback to CPU, including NNAPI) |

### 4.1 Run 1 (23:05)

| Provider | Threads | Status | First frame (ms) | p50 (ms) | p95 (ms) | Max (ms) | Model load (ms) | Peak PSS dumpsys (MB) | Peak PSS test (MB) |
|---|---|---|---|---|---|---|---|---|---|
| CPU | 1 | OK | 1780 | 78.7 | 481.2 | 882.6 | 2948 | 269 | 308 |
| CPU | 2 | OK | 390 | 58.1 | 92.3 | 940.7 | 629 | 257 | 310 |
| CPU | 4 | OK | 396 | 79.2 | 182.0 | 591.3 | 509 | 258 | 312 |
| XNNPACK | 1 | OK | 903 | 74.4 | 123.2 | 299.7 | 933 | 238 | 349 |
| XNNPACK | 2 | OK | 346 | 58.3 | 79.4 | 102.0 | 679 | 241 | 364 |
| XNNPACK | 4 | OK | 382 | 94.7 | 140.0 | 156.5 | 646 | 241 | 352 |
| NNAPI | 1 | OK | 600 | 312.5 | 332.2 | 351.1 | 686 | 290 | 327 |
| NNAPI | 2 | OK | 692 | 334.4 | 401.0 | 512.4 | 694 | 290 | 327 |
| NNAPI | 4 | OK | 636 | 349.1 | 405.3 | 612.2 | 680 | 252 | 327 |

### 4.2 Run 2 (23:09)

| Provider | Threads | Status | First frame (ms) | p50 (ms) | p95 (ms) | Max (ms) | Model load (ms) | Peak PSS dumpsys (MB) | Peak PSS test (MB) |
|---|---|---|---|---|---|---|---|---|---|
| CPU | 1 | OK | 324 | 84.8 | 97.2 | 117.2 | 415 | 261 | 312 |
| CPU | 2 | OK | 378 | 60.9 | 74.4 | 99.2 | 463 | 338 | 338 |
| CPU | 4 | OK | 365 | 61.4 | 75.0 | 86.8 | 451 | 317 | 318 |
| XNNPACK | 1 | OK | 330 | 84.4 | 135.9 | 182.0 | 524 | 255 | 352 |
| XNNPACK | 2 | OK | 391 | 71.3 | 83.7 | 107.5 | 495 | 330 | 375 |
| XNNPACK | 4 | OK | 469 | 86.7 | 114.5 | 145.0 | 658 | 242 | 349 |
| NNAPI | 1 | OK | 852 | 467.9 | 513.6 | 539.7 | 760 | 293 | 331 |
| NNAPI | 2 | OK | 810 | 455.4 | 484.3 | 515.4 | 705 | 282 | 333 |
| NNAPI | 4 | OK | 815 | 431.8 | 481.5 | 757.3 | 706 | 287 | 332 |

Times are milliseconds per frame. "Peak PSS dumpsys" is the highest proportional set size (the process's own memory
plus its share of shared pages) that `dumpsys meminfo` showed, sampled once a second; "Peak PSS test" is read inside
the test process.

### 4.3 Reading the numbers

- Best settings are CPU with 2 threads and XNNPACK with 2 threads. p50 was 58.1-60.9 ms (CPU) and 58.3-71.3 ms
  (XNNPACK); p95 was 92.3 and 74.4 ms (CPU) and 79.4 and 83.7 ms (XNNPACK) in runs 1 and 2. Across both runs and both
  providers, the 2-thread rows span p50 58-71 ms and p95 74-92 ms.
- The 20 ms per-frame target (T-PERF-01) is missed on the phone: the best p50 is about three times the target and the
  best p95 about four times. The desktop also misses it (p95 127.7 ms, latency run of 2026-10-20,
  [../eval/results/20261014-latency/README.md](../eval/results/20261014-latency/README.md); p95 38-45 ms on a quieter
  machine, [../eval/calibration-notes.md](../eval/calibration-notes.md)).
- NNAPI is 5-8 times slower than CPU: p50 312-468 ms, p95 332-514 ms at every thread count. It loads (no fallback), so
  this is its real speed on this phone's driver; it is not a good setting here.
- 4 threads are no faster than 2: CPU p50 79.2 and 61.4 ms, XNNPACK p50 94.7 and 86.7 ms. The thread count has no
  effect on NNAPI beyond run-to-run noise. 1 thread is slower than 2 (p50 74-85 ms).
- Spread between runs: CPU with 1 thread had p95 481 ms in run 1 and 97 ms in run 2; its first frame took 1,780 ms
  against 324 ms. Run 1 had the cold-start model load of 2,948 ms (probably a cold file cache; not checked); run 2 loaded
  the models in 415-760 ms. Treat single-run p95 and max values with care; the p50 values agree within about 13 ms for
  the best settings.
- Model load takes 0.4-0.9 s in warm conditions, 2.9 s in the cold CPU 1-thread case of run 1. The first frame takes
  about 0.35-0.4 s for the 2-thread settings.
- Memory: peak PSS 239-338 MB by `dumpsys` and 308-375 MB inside the test process (XNNPACK rows are the highest in the
  test process, 349-375 MB). For CPU with 2 threads, the setting the app ships with, it was 257 and 338 MB by `dumpsys`
  and 310 and 338 MB in the test.
- The app default (CPU, 2 threads) is also the measured best, so no default needs to change. XNNPACK with 2 threads is
  as fast but uses more memory in the test process. No code or configuration was changed because of these runs.

Not covered by these runs: sustained load (thermal throttling over minutes), an unplugged phone, a second phone model,
the camera path, and the full app. One phone and two runs are a small sample.

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

## 6. Checks done before the phone run

- `bash -n scripts/android-perf.sh` passes; `--help` and `--dry-run` print the plan without `adb`.
- `gradlew :livenessmanager:compileDebugAndroidTestJavaWithJavac` compiles `LivenessPerformanceTest`.
- The script's output parsing and summary were exercised against a stand-in `adb` that prints fixed values; nothing
  was run on a real phone or on the emulator at that time. The phone runs of section 4 then exercised the on-device path
  on Android 15; `dumpsys` output formats on Android versions before 10 remain untested.
