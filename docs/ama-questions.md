# Questions for the MOSIP Decode mentor call (Wed Sep 30, 16:00 IST)

- Email the starred questions to decode@mosip.io today, and post Q1–Q3 on the Unstop Discussions board (FAQ Q8, Q17).

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

_Filled in after the call._
