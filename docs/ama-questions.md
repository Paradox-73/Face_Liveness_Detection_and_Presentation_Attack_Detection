# Questions for the MOSIP Decode mentor calls

Prepared for the call of 2026-09-30 (16:00 IST). What the mentors answered is under "Answers". Next AMA: Oct 21,
final packaging and the pitch format.

## Still open

Not answered on the call; to send by email to decode@mosip.io. Q numbers are those of the list sent before the call (in git history), T numbers
those of the 2026-09-30 short list.

- T1: Is `ALWAYS_ACTIVE` an acceptable default for every workflow that matches a face, given PS04 says "passive
  first, active when below threshold"? Evidence: synthetic attack transactions accepted at `ALWAYS_ACTIVE` and at
  `PASSIVE_THEN_ACTIVE` ([eval/results/20261014-shipped/README.md](eval/results/20261014-shipped/README.md)).
- T3 / Q10: Is a supervisor override after the last failed resident attempt acceptable (the shipped core default is
  `BLOCK`)? Should a failed liveness attempt count against `mosip.registration.num_of_face_retries`?
- T4 / Q4: On Android, is an in-app camera (CameraX) acceptable as an L0 face device, since vendor SBI face apps send
  ARC no preview frames?
- T5 / Q7: Target APCER / BPCER values for the in-house evaluation (the mentors said they would check and reply).
- T6: Is a signed offline model import on both clients enough for "secure offline model updates"?
- T7 / Q11: Pitch: live or recorded, how long, and when? Is a recorded demo on the emulator and MockSync acceptable?
- Q14: Will judges test with a physical L0/L1 face device? Which SBI version (0.9.5 or 1.0)?
- Q5: Should device-side PAD follow #1117 (`padCapability`, `bio[].pad`, the ISO/IEC 39794-5 PAD block)?
- Q8: Should liveness evidence go in CBEFF BIR `others`, in packet `meta_info`, or both?
- Q9: Should liveness also apply to operator onboarding and to the exception photo? PS04 lists neither.
- Q12: May about 20 MB of Apache-2.0/MIT model files be committed?
- Q13: Is replacing the Visual C++ runtime DLLs of the bundled Zulu 11.0.8 JRE acceptable?
- Q15: Is a PR adding a row to `mosip/hackathon/Decode-2026/README.md` expected?
- Q16: Where does each student upload the college ID: Unstop profile, the Submission round, or email?
- Q2: Are FACE modes active for `login_auth`, `packet_auth`, `eod_auth` and `exception_auth` in Collab masterdata?
  They are inactive in `mosip-data` 1.2.0.1 `app_authentication_method.xlsx` (verified).
- Q6 follow-up: confirm that the Android client may stay on `release-1.2.x` (see Answers).

## Answers

### Mentor call, 2026-09-30

| Question | Answer | What it changes |
|---|---|---|
| Q1 / T2: is a local mock MOSIP server acceptable for judging the Registration Client flows? | Yes, a local set-up is fine. | No Collab environment needed. MockSync stays the demo and judging server. |
| Q3: do judges expect working desktop **and** Android versions? | Both: design and implementation for both, since the approach is almost the same. | Both are built. Android now needs the same depth of real-camera testing as desktop, ideally on a physical phone. |
| Q7 / T5: is an in-house test with 8-12 people and home-made attacks acceptable, and are there target APCER / BPCER values? | The mentors will check and reply. | Open. Follow up by email. The team self-test ([eval/team-self-test.md](eval/team-self-test.md)) goes ahead meanwhile. |
| Q6: `develop` or `master` as the base? | `master`, the latest released code; `develop` changes too often. | Desktop client: already on `master` (`22fe01f`). MockMDS: already on `master`. Android client: on `release-1.2.x` (`c4d3ca7`), which is upstream `master` (tag `v1.1.1`, `8f4643a`) plus 12 later upstream commits (unit-test fixes, an exception-photo dedupe fix, centre remapping, UI automation). Kept, and confirmed by email, because moving back to `master` would drop those upstream fixes. |
