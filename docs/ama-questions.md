# Questions for the MOSIP Decode mentor call (Wed Sep 30, 16:00 IST)

- Email the starred questions to decode@mosip.io today, and post Q1–Q3 on the Unstop Discussions board (FAQ Q8, Q17).

## Ask first today (updated 2026-09-30, after the engine and gap fixes)

Each question is tied to a decision already built into the code; the answer either confirms it or tells us what to
change. The original list follows.

| # | Question | What we built, and why it needs an answer | If the answer is no |
|---|---|---|---|
| T1 | ★ Is `ALWAYS_ACTIVE` an acceptable **default** for resident registration, face sign-in and onboarding, given PS04 says "passive first, active when below threshold"? | Passive-first let synthetic prints pass (APCER 17.6% pooled, 100% for two species); challenges on every capture cut it to 1.7%, at about 3 s more per capture (median 9.7 s). The passive stage still runs first and still rejects attacks. The mode is one synced key per workflow. | Ship `PASSIVE_THEN_ACTIVE` as default and document `ALWAYS_ACTIVE` as the recommended hardening. |
| T2 | ★ Will judges run the clients against a real MOSIP (Collab) server or accept our local mock server (MockSync) for the demo? Do they need a physical L0/L1 face device, and which SBI version? | Both clients were run end to end against MockSync with the mock face device (MockMDS, SBI 0.9.5) and an Android emulator; no physical device or Collab environment was used. | Plan a Collab run and a device test before the deadline (Q1, Q2, Q14). |
| T3 | ★ Is a supervisor override after the last failed resident attempt acceptable? | On both clients, when `registration.on_max_failure=SUPERVISOR_OVERRIDE`, a supervisor can accept the face; the packet says `LIVENESS_DECISION=OVERRIDE` and audit LV16 names the supervisor. It is never offered after an attack (PAD, integrity, binding). PS04 task 5 says to prevent acceptance of a face that fails liveness. The shipped core default is `BLOCK`. | Keep `BLOCK` only and remove the override value (Q10). |
| T4 | On Android, is an in-app camera (CameraX) acceptable as an L0 face device, since vendor SBI face apps show the preview in their own screen and send ARC no frames? | Built and run (replayed frames on the emulator). An adapter for vendor SBI apps exists but is not wired in, and ARC's device check still accepts L0 only. | Wire the SBI intent adapter and ask vendors for a stream channel (Q4). |
| T5 | Is an evaluation by the team following the ISO/IEC 30107-3 method (about 12 volunteers, home-made prints, screens and replays) enough, and are there target APCER / BPCER values? | Only the automated synthetic suite has run. The volunteer protocol is written (`docs/eval/protocol.md`) and not run. | Adjust the protocol to the expected targets (Q7). |
| T6 | Is a desktop-only offline model import (supervisor loads a signed model bundle from a folder or ZIP) enough for "secure offline model updates", with Android models updated through a new APK? | Desktop import checks signature, version (no rollback) and hashes, and was run end to end. Android has no in-app import. | Build the Android import. |
| T7 | Pitch: live or recorded, how long, and when? Is a recorded demo on the emulator and MockSync acceptable? | Deck outline, summary and video script are ready (`docs/pitch/`). | — |

## Original list

1. ★ Is a local mock MOSIP backend acceptable for judging the Registration Client flows?
   - It serves the syncdata and IDA endpoints, and the clients' sync code stays unchanged.
   - Context: Collab lead times.
2. ★ Can Decode teams get faster Collab access, for both the desktop TPM form and the Android credential form?
   - Are FACE modes active for `login_auth`, `packet_auth`, `eod_auth` and `exception_auth` in Collab masterdata (server reference data)?
   - They are inactive in `mosip-data` 1.2.0.1 `app_authentication_method.xlsx` (verified).
3. ★ Do judges expect working Desktop **and** Android implementations for full functionality credit?
4. On Android, is an in-app CameraX camera acceptable as an L0 face device if it signs its capture with a device key?
   - Context: the Android SBI keeps the preview inside the SBI app, so no frames reach ARC.
5. Should device-side PAD follow #1117 (`padCapability`, `bio[].pad`, the ISO/IEC 39794-5 PAD block)?
   - Is a JSON placeholder acceptable until 39794-5 encoding is available?
6. Which base branches should the work target?
   - RC: `master` (1.2.0.2, Java 11) or `develop` (1.2.1-SNAPSHOT)?
   - ARC: `release-1.2.x` or `develop`?
   - MockMDS: `master` or `develop`?
7. Is an in-house evaluation following the ISO/IEC 30107-3 method acceptable (team-made attack material, about 12 subjects)?
   - Are there target APCER or BPCER values?
8. Should liveness evidence go in CBEFF BIR `others`, in packet `meta_info`, or both?
9. Should liveness also apply to operator onboarding and to the exception photo? PS04 lists neither.
10. Should a failed liveness attempt count against `mosip.registration.num_of_face_retries`?
    - At maximum retries, block, or allow a supervisor override flagged in the packet?
    - An override stores a face that failed liveness, an exception to PS04 task 5, bullet 2 ("Prevent acceptance of a face that does not satisfy liveness requirements").
11. Will finalists pitch live? When, and in what format?
12. May about 20 MB of Apache-2.0/MIT model files be committed, or should the build download them with pinned hashes?
13. Is replacing the Visual C++ runtime DLLs of the bundled Zulu 11.0.8 JRE acceptable, or must we stay on ORT 1.20.0?
14. Will judges test with a physical L0/L1 face device? Which SBI version (0.9.5 or 1.0)?
15. Is a PR adding a row to `mosip/hackathon/Decode-2026/README.md` expected?
16. Where does each student upload the college ID: Unstop profile, the Submission round, or email?

**Later AMAs**
- **Oct 7:** show the walking skeleton. Re-ask Q4, Q5 and Q10 if unanswered.
- **Oct 14:** show `v0.9`. Ask for feedback on the UX and the evaluation report format.
- **Oct 21:** ask about final packaging and the pitch format.

## Answers

### Mentor call, 2026-09-30

| Question | Answer | What it changes |
|---|---|---|
| Q1 / T2: is a local mock MOSIP server acceptable for judging the Registration Client flows? | Yes, a local set-up is fine. | No Collab environment needed. MockSync stays the demo and judging server. |
| Q3: do judges expect working desktop **and** Android versions? | Both: design and implementation for both, since the approach is almost the same. | Both are built. Android now needs the same depth of real-camera testing as desktop, ideally on a physical phone. |
| Q7 / T5: is an in-house test with 8-12 people and home-made attacks acceptable, and are there target APCER / BPCER values? | The mentors will check and reply. | Open. Follow up by email. The team self-test ([eval/team-self-test.md](eval/team-self-test.md)) goes ahead meanwhile. |
| Q6: `develop` or `master` as the base? | `master`, the latest released code; `develop` changes too often. | Desktop client: already on `master` (`22fe01f`). MockMDS: already on `master`. Android client: on `release-1.2.x` (`c4d3ca7`), which is upstream `master` (tag `v1.1.1`, `8f4643a`) plus 12 later upstream commits (unit-test fixes, an exception-photo dedupe fix, centre remapping, UI automation). Kept, and confirmed by email, because moving back to `master` would drop those upstream fixes. |

Not asked on the call, to send by email to decode@mosip.io: T1 (`ALWAYS_ACTIVE` default), T3 (supervisor override),
T4 (in-app camera as L0 on Android), T6 (desktop-only offline model import), T7 (pitch format), Q5 (#1117 PAD
block), Q8 (where liveness evidence goes in the packet), Q16 (college ID upload) and the Android base branch above.
