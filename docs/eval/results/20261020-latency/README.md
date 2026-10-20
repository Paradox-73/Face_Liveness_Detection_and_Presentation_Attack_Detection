# Latency suite of 2026-10-20 (PAD models scored at the same time)

`bash tools/eval/run-suites.sh OUT latency` after commit 39e0e06, which runs the two MiniFASNet models of the passive
PAD at the same time (the second on a helper thread) instead of one after the other. Same suite as
[../20261014-latency/README.md](../20261014-latency/README.md): the 6 bona fide registration scenarios (24
transactions, all accepted), one ONNX Runtime intra-op thread per inference, shipped defaults, model bundle 1.3.0,
ONNX Runtime 1.20.0 on CPU, Java 11.0.8. Machine: the shared team laptop, 12th Gen Intel Core i5-12450H (8 cores, 12
logical CPUs), Windows, with other builds running.

The files in this folder are the second run with the change (`new 2` below).

| Measure | Value |
|---|---|
| Per analysed frame (decode + analysis + PAD, wall clock), 4,410 frames | p50 39.4 ms, p95 **127.7 ms**, mean 51.2 ms, max 283.4 ms |
| Stages, p95 | decode 14.1 ms, face analysis 97.3 ms, PAD (every other frame, with the screen-pattern check) 22.3 ms |
| Accepted `registration` attempt, median time on the replayed stream | 11.2 s (11,233 ms over 24 attempts) |

## Before and after, same day

We ran the suite four times, alternating the engine before the change (runner jar built from commit ab47ffa) and
after it, so both saw similar load. Windows CPU load is the value read just before and just after each run.

| Run | CPU load before / after | per-frame p50 | per-frame p95 | PAD p50 / p95 | analysis p50 / p95 |
|---|---|---|---|---|---|
| before, 1 | 52% / 38% | 46.0 ms | 130.1 ms | 12.4 / 38.5 ms | 32.5 / 93.4 ms |
| after, 1 | 34% / 11% | 40.9 ms | 123.3 ms | 6.7 / 18.9 ms | 31.4 / 95.1 ms |
| before, 2 | 11% / 65% | 44.7 ms | 148.6 ms | 11.6 / 43.6 ms | 30.6 / 111.2 ms |
| after, 2 | 51% / 69% | 39.4 ms | 127.7 ms | 6.5 / 22.3 ms | 30.0 / 97.3 ms |

An earlier run on the old engine the same afternoon gave p50 49.4 ms and p95 167.4 ms. The PAD stage is about half
as long, and the per-frame p50 is about 5 ms lower. The per-frame p95 moves more with the machine's load than with
the change. The 20 ms target is not met.

## Where the time goes

We timed each step on the 88 frames of the `s1-main` pack, one thread, on the same laptop (means of 60 runs on the
quietest repeat):

| Step | Time |
|---|---|
| JPEG decode (ImageIO) | about 4.4 ms (p50) |
| YuNet: resize to 320x240 / inference | 0.6 ms / 2.4 ms |
| Face Mesh: warp to 256x256 / inference | 1.6 ms / 8.6 ms |
| MiniFASNet V2 or V1SE: crop / inference, each | 0.2 ms / 2.6 ms |
| quality measures, screen-pattern check | under 1 ms each |
| SFace embedding (only on 152 of the 4,410 frames) | about 17 ms quiet, 45 ms p50 in this run |

Even on an idle machine a PAD frame needs about 20 ms of inference and decoding with one thread, so the 20 ms p95
target cannot be met at one thread without skipping a model on some frames, which would change decisions. Two
options that keep decisions and that we measured but did not adopt:

- Face Mesh with 2 intra-op threads (the shipped default; the latency suite pins 1): 30% faster, outputs identical.
- ONNX Runtime 1.30 instead of 1.20 (Python, same model): Face Mesh 6.4 ms instead of 8.3 ms. Changing the runtime
  also changes the Android build and its supply-chain checks, so we left it.

## Decisions did not change

With the old and the new engine, the `shipped` suite (755 attempts: 226 attack transactions all rejected, 40 bona
fide transactions all accepted) and the `passive-first` suite (555 attempts, 100 of 226 attack transactions
accepted) gave the same outcome, decision, reason codes and PAD, binding and planarity scores for every attempt.
Both also match the committed 2026-10-14 runs attempt for attempt. The four latency runs above gave the same 24
attempts. `mvn -f liveness/pom.xml package` passed (544 tests, including the golden-value tests of the models).
