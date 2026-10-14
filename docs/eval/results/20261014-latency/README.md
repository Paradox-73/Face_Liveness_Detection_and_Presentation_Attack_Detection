# Latency suite of 2026-10-14 (shipped engine)

`bash tools/eval/run-suites.sh OUT latency` on the final code of the PS04 completion pass: the 6 bona fide
registration scenarios (24 transactions, all accepted) with one ONNX Runtime thread, shipped defaults
(`ALWAYS_ACTIVE`, pool `BLINK,SMILE,TURN_LEFT,TURN_RIGHT` cut by each scenario to the actions its subject has
footage of), model bundle 1.3.0, ONNX Runtime 1.20.0 on CPU, Java 11.0.8.

| Measure | Value |
|---|---|
| Per analysed frame (decode + analysis + PAD, wall clock), 4,410 frames | p50 37.5 ms, p95 **115.4 ms**, mean 48.8 ms, max 558.0 ms |
| Stages, p95 | decode 10.7 ms, face analysis 77.0 ms, PAD (every other frame, with the screen-pattern check) 31.0 ms |
| Accepted `registration` attempt, median time on the replayed stream | 11.0 s (11,033 ms over 24 attempts) |

What limits the figure: face analysis (YuNet detection plus Face Mesh) is two thirds of the p95. The laptop (12
logical CPUs) was shared: Windows reported 65% total CPU load just before the run and 62% just after, from other
builds. The same suite gave p95 162.4 ms on 2026-10-05 morning and 242.4 ms that evening (with Android tests
running at the same time), so the per-frame p95 on this machine moves with its load; the project target of 20 ms
is not met at any of them. The p50 (37.5 ms) is the steadier figure. No phone was measured
([../../../test-report/android-performance.md](../../../test-report/android-performance.md)).
