# Liveness evaluation tooling

Tools and data descriptions for testing the face liveness engine automatically and for reporting results with ISO/IEC 30107-3 metrics.

- [protocol.md](protocol.md): the in-house evaluation with real people and real artefacts (who, what, how many, consent, metrics).
- [team-self-test.md](team-self-test.md): how the team tests the real clients with their own faces and home-made attacks, and turns the hand-filled sheet into APCER / BPCER (`tools/eval/sheet-to-attempts.py`).
- [calibration-notes.md](calibration-notes.md): results of the automated suite on the shipped engine and the parameter changes they support.
- [results/](results/): the reports of the runs quoted in the calibration notes; the latest are the suites of 2026-10-05 evening, after the exposure correction and the screen-pattern check ([results/20261005b-shipped/README.md](results/20261005b-shipped/README.md), with the morning run before them).

The automated suite replays recorded frames into the engine. That is an injection path in itself, and the attack frames are made in software, so its results are **technology regression results, not an ISO/IEC 30107-3 evaluation**.

## Layout

| Path | What it is |
|---|---|
| `eval/packs/sources.yaml` | the openly licensed source clips: URL, licence, author, attribution, SHA-256 |
| `eval/packs/<pack>.yaml` | one manifest per frame pack: recipe (hand-written), output hashes (written by the generators) |
| `eval/scenarios/<id>.yaml` | one test scenario: packs, stream behaviour, configuration, seeds, expected outcome |
| `tools/eval/fetch-frame-packs.py` | downloads the clips, checks their SHA-256 and cuts the bona fide packs with ffmpeg |
| `tools/eval/make-attack-packs.py` | builds the synthetic attack and quality packs from the bona fide packs |
| `tools/eval/synth.py`, `packlib.py` | the image transforms and the manifest helpers used by the two generators |
| `tools/eval/scenario-runner/` | Java 11 command-line runner: the real engine and the real ONNX models over the scenarios |
| `tools/eval/analyze.py` | APCER, BPCER, APNRR, BPNRR, intervals, DET plot and a Markdown report from the runner's output |
| `tools/eval/run-suites.sh` | runs the four standard suites and their reports |
| `tools/eval/tests/` | pytest tests of the Python tools |

Frame packs and clips live outside git, in `../run/frame-packs/` next to the repository (override with the environment variable `LIVENESS_FRAME_PACKS`). Only manifests are committed.

## Quick start

Needs: JDK 11 or newer, Maven, Python 3.9+ with `pip install -r tools/eval/requirements.txt`, ffmpeg on `PATH`, and the liveness jars in the local Maven repository (`mvn -f liveness/pom.xml install`).

```bash
python tools/eval/fetch-frame-packs.py            # downloads the source clips (about 17 MB), cuts 12 bona fide packs, checks hashes
python tools/eval/make-attack-packs.py            # builds 31 synthetic packs (attack, quality, dim light), checks hashes
mvn -f tools/eval/scenario-runner/pom.xml package # builds target/scenario-runner.jar and runs 31 unit tests
bash tools/eval/run-suites.sh                     # about 15 minutes per suite on a 12-thread laptop
```

One suite by hand:

```bash
java -jar tools/eval/scenario-runner/target/scenario-runner.jar run \
     --packs ../run/frame-packs --scenarios eval/scenarios --out ../run/eval/mine [--filter '^atk-'] \
     [--config policy.registration=ALWAYS_ACTIVE] [--engine-param planarity.min_residual=0.05] [--no-fail] [--no-low-light]
python tools/eval/analyze.py ../run/eval/mine/attempts.jsonl --out ../run/eval/mine/report.md
```

The runner exits with 0 when every transaction met its scenario's expectation, 1 when at least one did not (0 with `--no-fail`), and 2 on a usage or setup error. Other options: `--models DIR` (a model bundle folder instead of the one in the `liveness-onnx` models jar), `--threads N` (ONNX Runtime threads per inference, default 2), `--provider CPU|XNNPACK|NNAPI` (ONNX Runtime execution provider; one the runtime does not include falls back to CPU, and `run.json` records the provider in effect), `--run-id NAME` (written into every record), `--filter REGEX` on scenario ids and `--no-low-light`. The runner tracks the dim-light ending the clients show (a dark-face passive failure ends as `LOW_LIGHT`, not counted, up to `passive.low_light_max_uncounted` = 3 times per transaction, then counts as a PAD failure); `--no-low-light` switches that off, so every dark-face failure counts, to reproduce runs made before 2026-10-05. `run.json` records the setting as `low_light_tracking`.

`run-suites.sh` runs four suites into `../run/eval/<time>/<suite>/`: `shipped` (the configuration as shipped, which since 2026-09-30 is the calibrated one of [calibration-notes.md](calibration-notes.md)), `legacy` (the configuration shipped before that date, on the current engine), `legacy-strict` (`legacy` with challenges forced for registration; the former `strict` suite) and `latency` (bona fide scenarios on one thread). `LIVENESS_RUNNER_JAR=<jar>` runs the suites with another runner build, for example one kept from before an engine change. The reports quoted in the calibration notes are copied to [results/](results/).

`inspect` measures every frame of a pack (face box, pose, eye and mouth measures, PAD score) into a CSV without running a session; it was used to label the action segments:

```bash
java -jar tools/eval/scenario-runner/target/scenario-runner.jar inspect --packs ../run/frame-packs --all --out packs.csv
```

## Frame packs

A pack is a folder of JPEG frames `frame_00000.jpg`, `frame_00001.jpg`, ... at 640x480 and 15 fps.

**Bona fide packs** (cut from the clips in `eval/packs/sources.yaml`; each manifest records the ffmpeg command):

| Source clip | Licence | Packs | Actions |
|---|---|---|---|
| Wikimedia Commons "20140529 - Blinking" and "20140529 - Smiling" by Clément Bucco-Lechat | CC BY-SA 3.0 | `s1-main`, `s1-blink`, `s1-smile` | blink, smile |
| Pexels 5474329 and 5474326 by cottonbro studio | Pexels License | `s2-main`, `s2-turn-left`, `s2-turn-right` | head turns |
| Pexels 6781549 by Artem Podrez | Pexels License | `s3-main`, `s3-blink`, `s3-smile`, `s3-long` (longer clip, source of the loop packs), `s3-turn-left` | blink, smile, one left turn |
| MockMDS stock stream image (`devices/mock-mds/Biometric Devices/Face/Stream Image/0.jpeg`) | MPL-2.0 | `m-stream-static` | none (one image repeated) |

- The ffmpeg cut uses bit-exact flags, so the same ffmpeg build reproduces the recorded SHA-256 of every frame; another build may give different bytes, which `--verify-only` reports.
- The people in the clips are not project members. Their frames are used only for automated tests, never shown in the demo video, slides or repository, and never used for training. The CC BY-SA clips are not redistributed; if frames cut from them were ever shared, they would carry the attribution in `sources.yaml` and the same licence.

**Synthetic packs** (made by `make-attack-packs.py`; every manifest description says "SYNTHETIC"):

| Species | Packs | How it is made | Stands in for |
|---|---|---|---|
| SYN-A1 | `a-s2-identical`, `a-s3-identical` | one frame's JPEG bytes repeated | an injected still image |
| SYN-A2 | `a-s2-print-still`, `a-s3-print-still` | print look (lower saturation and contrast, paper tint, softening), hand tremor, fresh sensor noise per frame | P1 print held by hand |
| SYN-A3 | `a-s2-print-affine`, `a-s3-print-affine` | the print moved rigidly in the image plane: pan, zoom, roll | P1 print waved by the attacker |
| SYN-A4 | `a-s2-print-yaw-left`, `a-s2-print-yaw-right` | the flat print turned 35 degrees about its vertical axis (perspective warp) | P2 print tilted to fake a head turn |
| SYN-A5 | `a-s2-screen-photo`, `a-s3-screen-photo` | screen look: moire, RGB sub-pixel grid, cool colour cast, gamma change, glare spot, refresh flicker | P4/P5 photo on a screen |
| SYN-A6 | `a-s2-screen-replay`, `a-s3-screen-replay` | the screen look on every frame of a bona fide video | P6 video replay |
| SYN-A7 | `a-s3-screen-blink`, `a-s3-screen-smile`, `a-s2-screen-turn-left`, `a-s2-screen-turn-right` | the screen look on each action clip | P7 adaptive replay |
| SYN-A8 | `a-s2-loop`, `a-s3-loop`, `a-s2-loop-2s`, `a-s3-loop-2s`, `a-s2-loop-4s`, `a-s3-loop-4s`, `a-s2-loop-8s`, `a-s3-loop-8s` | 20 frames (1.3 s), and 30, 60 and 120 frames (2, 4 and 8 s) of bona fide video, looped by the scenario | a looped replay |
| SYN-A9 | (bona fide action packs) | the scenario plays a genuine clip of the wrong action for each prompt | a replay attacker without the right clip |
| SYN-P0 | `m-stream-static` | the stock MockMDS stream image | the unmodified mock device |
| quality | `q-blank`, `q-pair`, `q-s3-dark`, `q-s3-blur` | empty scene, two faces side by side, 20% brightness, Gaussian blur 6 px | T-SCN-L-09..11 |
| bona fide, synthetic | `s3-dim-main`, `s3-dim-blink`, `s3-dim-smile` | S3 packs at 45% brightness with extra noise | condition C2 (dim) |

All randomness comes from the seed in each recipe, so the same Pillow and numpy versions give the same bytes (the manifests record the versions).

## Scenario files

```yaml
id: atk-s3-adaptive-screen          # must equal the file name; lower case, digits, '.', '-', '_'
title: 'S3: adaptive replay on a screen, the clip matching each prompt (T-SCN-P-07)'
kind: attack                        # bona_fide | attack | other (quality and behaviour tests, not in APCER/BPCER)
species: SYN-A7                     # required for attacks; BF and OTHER are filled in for the other kinds
subject: S3
condition: C1                       # bona fide condition label, reported per condition
workflow: registration              # registration | onboard_auth | login_auth | packet_auth | exception_auth | eod_auth
repeat: 4                           # transactions with seeds 1..4 (or seed: N, or seeds: [..])
fps: 15                             # replay rate; default 15
max_stream_ms: 60000                # the runner cancels an attempt after this much stream time
config:                             # liveness configuration; keys may omit the mosip.registration.liveness. prefix
  active.challenge_types: BLINK,SMILE
engine_params:                      # detection parameters (EngineParameter keys), numbers only
  planarity.min_residual: 0.05
stream:
  main: a-s3-screen-replay          # pack played outside challenges
  at_end: pingpong                  # loop | pingpong (default) | hold (freeze on the last frame) | end (stream ends)
  on_prompt: match                  # ignore | match (default with actions) | mismatch | fixed
  reaction_ms: 400                  # time between the prompt and the start of the action clip
  actions:                          # pack recorded for each challenge type
    BLINK: a-s3-screen-blink
    SMILE: a-s3-screen-smile
  fixed_pack: null                  # the pack used with on_prompt: fixed
expect:
  outcome: reject                   # accept | reject | no_response; omitted = only record what happens
  layers: [passive_pad]             # optional: layers allowed to end rejected attempts
notes: free text
```

Unknown keys are errors, so a typo cannot silently change a test.

The 75 committed scenarios (10 `bf-*`, 58 `atk-*`, 7 `other-*`) are named `bf-*` (bona fide, per subject and policy), `atk-*` (one attack species and subject each; the `-login`, `-packet-auth`, `-exception-auth` and `-eod-auth` ones replay the same packs on those workflows, and the `-2s`, `-4s` and `-8s` ones are the long loops) and `other-*` (quality and behaviour cases that count in neither APCER nor BPCER). Their 275 transactions are the suites quoted in [results/20261005b-shipped/README.md](results/20261005b-shipped/README.md) (and, before the PAD additions, [results/20261005-shipped/README.md](results/20261005-shipped/README.md)).

**How prompts are answered.** The runner listens for the engine's "challenge issued" audit event (REG-EVT-LV04). After `reaction_ms` it switches from the main pack to the pack chosen by `on_prompt`, plays it once, and resumes the main pack. `match` is a genuine person, or an adaptive replay attacker who holds a clip of every action; `mismatch` plays a clip of a different action; `fixed` always plays one pack; `ignore` never reacts (a still photo, or a person who ignores the prompt).

**Seeds are for tests only.** The seed fixes the challenge order, the prompt delays and the nonces through `SeededRandomSource`, which the core accepts only through `SessionRequest.Builder.random`. No configuration key of either client can select it; production sessions use `RandomSource.secure()`.

## How the runner drives the engine

- The engine is `DefaultLivenessEngine` with the real `OnnxFaceAnalyzer` (YuNet + Face Mesh), `MiniFasPassivePad` and `SFaceEmbedder`, loaded from the signed model bundle after its signature and hash checks. The parameters are resolved as in `OnnxLivenessEngines`: shipped defaults, then the bundle's signed `engineParams`, then the scenario's `engine_params`. The runner builds the engine itself (instead of calling `OnnxLivenessEngines.builder`) so that each model call can be timed per stage and one loaded model set can serve engines with different parameters.
- Time is stream time: a manual clock moves one frame period (1/fps) per frame, so every timeout behaves as on a live 15 fps stream however fast the models run.
- At `CAPTURE_READY` the runner plays the part of MockMDS: the last streamed frame becomes the signed capture, 300 ms after `captureStarted()`, and is bound with the pass token.
- Transactions follow `LivenessTransaction`: attempts until a pass or until `max_retries` attempts that count; endings that do not count (no face, device error) get at most 2 extra sessions.
- The configuration is the shipped defaults (`LivenessConfigKeys.defaultValues()`), then `--config` values, then the scenario's `config`. Diagnostics are switched on so the planarity residual can be read.

## Output

Each run folder holds:

- `attempts.jsonl`: one line per attempt, schema `liveness-eval-attempt/1`. Fields: scenario, kind, species, subject, condition, workflow, seed, attempt and transaction numbers, outcome (ACCEPT, REJECT, NO_RESPONSE) and transaction outcome, decision, failure reason, reason codes, the layer that ended the attempt (`passive_pad`, `active_pad`, `integrity`, `planarity`, `challenge`, `binding`, `quality`, `error`), method, passive and active PAD scores, binding similarity, planarity residual, challenges with onset and hold times, prompts with the pack played, stage times, per-frame latency (n, mean, p50, p95, max) overall and per stage (decode, analyse, PAD, embed), frames per pack, model set SHA-256, configuration hash, platform. **No images, embeddings or frame bytes.**
- `summary.md`: one row per transaction and the latency footer.
- `run.json`: model ids, versions and SHA-256, signed engine parameters, global overrides, platform.

`analyze.py` turns one or more `attempts.jsonl` files into `report.md` and `det.png`. The report sections, in order: Headline (per workflow and species: packs and subjects, transactions, APCER per transaction and on the first attempt, share of source artefacts accepted with an interval over artefacts, APNRR; the BPCER row; APCER_max bullets; pooled figures only as "Secondary" lines), evaluation type (b) per transaction, first attempt of each transaction, evaluation type (a) per attempt (secondary), genuine clips of the wrong action (SYN-A9, in neither APCER, BPCER nor the DET), full-system results IAPAR and FRR (only for workflows whose records carry `match_outcome`), per scenario (with the share accepted on the first attempt), where attacks were stopped, bona fide challenges, timings, the DET of the passive score with BPCER20 and BPCER10 per workflow, quality scenarios, and an expectation check. Intervals are exact 95% Clopper-Pearson; the columns are labelled "not independent" where one source artefact is replayed several times, because those transactions repeat the same frames. Every results table has a workflow column. The definitions are in [protocol.md](protocol.md), section 9.

## Tests

```bash
mvn -f tools/eval/scenario-runner/pom.xml test     # 31 JUnit 5 tests; a scripted engine stands in for the models
python -m pytest tools/eval/tests                  # 102 pytest tests
```

The Java tests cover the scenario loader (every field and error), the playlist (endings, reaction delay, match, mismatch, fixed), outcome and layer classification, the runner end to end with a scripted engine (bona fide pass, wrong-clip rejection over all retries, retry budget from the scenario, no-face and stream-end non-responses, runner time limit, expectation and layer mismatches, parameter pass-through, the low-light ending and `--no-low-light`, no frame content in records), JSON, latency statistics, CLI options, and that every committed scenario loads and names only packs that have a manifest. The Python tests check the Clopper-Pearson values against published figures, the counting rules (per transaction, first attempt, workflow grouping, source artefacts, IAPAR and FRR, the conversion of a MATCH failure in the hand-filled sheet), DET and BPCER20, the report, the manifest helpers, that every committed manifest is consistent, and the image transforms.

## Design choices

- Pack manifests are in `eval/packs/` and scenarios in `eval/scenarios/` (not both under `eval/scenarios/`), because several scenarios share one pack.
- The frame packs are not from team members but from openly licensed clips of unrelated people plus software-made attacks, because no consenting team captures exist yet. The development-split capture ([protocol.md](protocol.md) section 5, S1-S4 frame packs) replaces them once recorded; the tools do not change.
- Metrics and DET plots come from one script, `tools/eval/analyze.py`, not two.
- Only the headless runner exists; a second, services-level mode through the Registration Client and MockMDS `ImageSequenceSource` is not built. The same packs can be streamed by hand with MockMDS `POST /admin/source {source: SEQUENCE, path: ...}`.
