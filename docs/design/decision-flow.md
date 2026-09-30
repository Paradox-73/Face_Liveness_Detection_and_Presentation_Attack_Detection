# Passive-to-active liveness decision flow

PS04 Deliverable 1, bullet 4, and mandatory tasks 2 and 4: passive liveness first; proceed when the configured
threshold is met and no attack is found; otherwise start active challenges automatically; accept only after the
configured liveness and PAD criteria are met.

Implemented in `io.mosip.liveness.core.engine.DecisionPolicy` (package-private), called by the session at the end
of the passive stage. Table-tested at threshold - 1, threshold and threshold + 1 in `QualityAndDecisionTest`.

## 1. Inputs

| Input | Source | Default |
|---|---|---|
| Passive score `S` (0-100) | `round(100 x median)` of the MiniFASNet ensemble's live probability over the last `passive.min_frames` good frames | 8 frames |
| Integrity flags | `FrameIntegrityChecker`, `FaceTracker`, `IdentityTracker` | none allowed |
| `pad.reject_threshold` | configuration | 20 (floor 5) |
| `passive.threshold` for the workflow | `<workflow>.passive.threshold`, else global `passive.threshold`, else workflow default | 80 for `registration`; 95 for the five authentication workflows (floor 50) |
| Policy mode for the workflow | `policy.<workflow>` | `PASSIVE_THEN_ACTIVE`; `exception_photo`: `OFF` |
| `active.enable` | configuration | `Y` |
| Runnable challenges | pool minus types this stream cannot support ([challenges.md](challenges.md)) | 5 types |

## 2. Flow

```mermaid
flowchart TD
  START(["Passive stage complete:<br/>S from passive.min_frames good frames"]) --> INT{"Integrity flag raised?"}
  INT -- yes --> FINT["FAILED(INTEGRITY)<br/>LV08 FROZEN / LOOP / FACE_SWITCH ...<br/>screen: generic PAD message"]
  INT -- no --> REJ{"S < pad.reject_threshold?"}
  REJ -- yes --> FPAD["FAILED(PAD), LV08 PAD_SCORE<br/>screen: 'Face verification could not be completed.'"]
  REJ -- no --> MODE{"policy.&lt;workflow&gt;"}
  MODE -- OFF --> OFF["CAPTURE_READY, method NONE<br/>(no evidence in the packet)"]
  MODE -- PASSIVE_ONLY --> PO{"S >= passive.threshold?"}
  PO -- yes --> PASS1["CAPTURE_READY, method PASSIVE, LV02"]
  PO -- no --> BT1["FAILED(BELOW_THRESHOLD)"]
  MODE -- PASSIVE_THEN_ACTIVE --> PTA{"S >= passive.threshold?"}
  PTA -- yes --> PASS2["CAPTURE_READY, method PASSIVE, LV02"]
  PTA -- no --> AE{"active.enable = Y and<br/>at least one runnable challenge?"}
  AE -- yes --> ESC["CHALLENGE_WAIT<br/>LV03 reason BELOW_THRESHOLD"]
  AE -- no --> BT2["FAILED(BELOW_THRESHOLD)"]
  MODE -- ALWAYS_ACTIVE --> AA{"at least one runnable challenge?"}
  AA -- yes --> ESC2["CHALLENGE_WAIT<br/>LV03 reason POLICY"]
  AA -- no --> UNS["FAILED(ENGINE, UNSUPPORTED)<br/>no attempt used"]
  ESC --> CH["min_challenges random challenges"]
  ESC2 --> CH
  CH --> FIN{"every challenge passed AND<br/>active-phase PAD median >= reject threshold AND<br/>planarity OK on head turns AND same person?"}
  FIN -- yes --> PASS3["CAPTURE_READY, method PASSIVE_ACTIVE or ACTIVE"]
  FIN -- no --> FACT["FAILED(ACTIVE / TIMEOUT / PAD / INTEGRITY)"]
  PASS1 --> BIND["Signed capture, then CaptureBinder"]
  PASS2 --> BIND
  PASS3 --> BIND
  BIND --> OK(["PASSED, LV10"])
```

## 3. Three score bands (default registration values)

| Band | Score `S` | Result | PS04 text |
|---|---|---|---|
| Attack | 0-19 | Fails at once as `PAD`; no challenge is offered | "presentation attack is detected" |
| Unsure | 20-79 | Escalates to active challenges (`PASSIVE_THEN_ACTIVE`) | "does not meet the configured threshold ... automatically initiate active liveness verification" |
| Live | 80-100 | Proceeds to the signed capture | "meets the configured threshold and no presentation attack is detected" |

For the authentication workflows the live band starts at 95, so operators and supervisors usually answer a
challenge. This keeps the PS04 passive-first order while making active checks the normal case where one person's
face unlocks the client.

## 4. After the decision

- **Every pass still goes through binding** ([engine.md](engine.md), section 7): a pass token alone never stores a
  face or authenticates anyone.
- **A failure** goes to `LivenessTransaction` (core): counted failures use an attempt; after `max_retries` the
  workflow's `on_max_failure` applies ([errors.md](errors.md), section 4).
- **Method recorded in the packet** (`LIVENESS_METHOD`): `PASSIVE`, `PASSIVE_ACTIVE` (escalated), `ACTIVE`
  (`ALWAYS_ACTIVE` policy) or `NONE` (policy `OFF` or `CAPTURE_ONLY`).

## 5. Choice of policy per workflow

| Profile | Where set | Effect |
|---|---|---|
| Shipped | `LivenessConfigKeys.defaultValues()`, `tools/mock-sync/data/mock-sync-defaults.properties` | `PASSIVE_THEN_ACTIVE` everywhere except `exception_photo` (`OFF`) |
| Strict (proposal P1 in `docs/eval/calibration-notes.md`) | server config `policy.registration`, `policy.login_auth`, `policy.onboard_auth` = `ALWAYS_ACTIVE` | Every capture answers challenges |

Measured difference (automated suite, 39 scenarios, per attempt): shipped policy pooled APCER 17.6%, with a
rigid-motion print (SYN-A3) and a looped clip (SYN-A8) accepted passively in all 8 attempts each; strict / proposed
policy pooled APCER 3.5% / 1.7%, worst species 25% (an adaptive screen replay, SYN-A7). The cost is about 7 s of
challenges on every capture. The shipped default was kept because the change is a deployment decision; the end
to end runs show a printed photo accepted passively under the shipped policy (desktop D8 and Android run 8) and
refused once `policy.registration=ALWAYS_ACTIVE` is synced (desktop D8, Android A12).

## 6. Deviation from the plan

`ALWAYS_ACTIVE` ignores `active.enable` (that key controls escalation only), and when no challenge can run on the
stream it fails `ENGINE/UNSUPPORTED` without using an attempt. The plan did not define this case.
