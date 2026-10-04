# Scenario runner

Java 11 command-line runner that plays frame packs through the real liveness engine and ONNX models and writes one
JSON line per session. Usage, scenario format and output fields: `docs/eval/README.md`.

## Retries and low light

A scenario transaction follows the policy's retry rules (`LivenessTransaction`). Each session is told how many
sessions of the transaction ended uncounted for low light since the last counted failure
(`SessionRequest.Builder.lowLightRetries`), as the desktop and Android clients do. A dark face then ends the session
as `QUALITY` with reason `LOW_LIGHT` (outcome `NO_RESPONSE`, `no_response_reason` `LOW_LIGHT`) up to
`passive.low_light_max_uncounted` times (default 3); the next passive PAD failure counts as an attempt.

- On by default. `run.json` records it as `low_light_tracking`.
- `--no-low-light` passes no count, so a dark face always counts as a PAD failure: use it to reproduce runs made
  before low light was tracked.
- Low-light endings do not use up the runner's session budget (`max_retries` + 2); the engine bounds them.

Results from 2026-10-05 (shipped configuration, 4 seeds): `bf-s3-dim` now ends each counted attempt after three
`LOW_LIGHT` sessions (12 sessions per transaction, outcome still `REJECT` against the scenario's `ACCEPT`
expectation); `bf-s1-registration`, `atk-s2-print-still` and `atk-s3-adaptive-screen` end as in the shipped-defaults
run of the same day. Files: `run/eval/f01/` of the working folder.

## Tests

`mvn -f tools/eval/scenario-runner/pom.xml package` runs 31 JUnit tests; a scripted engine stands in for the models.
