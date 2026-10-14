# Manual test results

Results of the steps in [manual-test-checklist.md](manual-test-checklist.md). Every row id is a step id of the
checklist.

## How to fill it in

1. Copy this file to `../run/manual-tests/results-<your pseudonym or initials>-<date>.md`, outside git.
2. Do the steps of one section in order and fill one row per step as you go.
3. Send the filled file back as described at the end of this page.

| Column | What to write |
|---|---|
| Tester | initials of the person who did the step; the pseudonym (`S1` to `S12`) when the person is a subject |
| OS / device | for example `Windows 11, Integrated Camera 1280x720` or `Pixel 6a, Android 14` |
| Pass / fail | `PASS` when the screen showed the expected result of the checklist, `FAIL` when it did not, `SKIP` with a reason in the notes |
| Time | duration of the step in minutes and seconds, for example `2:40`; for a capture, the time from **Scan** to the face on screen |
| Notes | for a `FAIL`, the exact text on screen and the reason line of the diagnostic log; which policy was in effect (`MT-DT-04`); whether prompts appeared; anything odd. Never a name, never a password. |
| File names | recordings, screenshots, logs, sheets and reports that belong to the step, for example `S1_C1_neutral.mp4` or `../run/pad-session/report.md` |

- Steps marked with `S1` to `S4` are done once per subject.
- Per-attempt results of the attack session go into the results sheet (`MT-PAD-06` to `MT-PAD-09`), not into this
  file. Write the sheet's file name and the session totals in the row.
- A screenshot that shows a face needs the person's written OK. Prefer a photo of the screen with the face out of
  the picture, or the text copied into the notes.

## Item under test

Fill this table once per session (`MT-PAD-02` names the same facts).

| Fact | Value |
|---|---|
| Date and place | |
| Git commit (`git rev-parse --short HEAD`) | |
| Liveness engine line from the client log | |
| Policy for registration, sign-in and end of day (`MT-DT-04`) | |
| Challenge pool (`MT-DT-04`) | |
| Webcam model and resolution | |
| Phone model and Android version | |
| Tester and recorder, with experience | |

## A. Webcam recordings

| Id | Step | Tester | OS / device | Pass / fail | Time | Notes | File names |
|---|---|---|---|---|---|---|---|
| `MT-REC-01` | consent forms, pseudonym list | | | | | | |
| `MT-REC-02` | camera prepared, effects off | | | | | | |
| `MT-REC-03` | room and folder | | | | | | |
| `MT-REC-04` | normal light clips (C1), S1 | | | | | | |
| `MT-REC-04` | normal light clips (C1), S2 | | | | | | |
| `MT-REC-04` | normal light clips (C1), S3 | | | | | | |
| `MT-REC-04` | normal light clips (C1), S4 | | | | | | |
| `MT-REC-05` | dim light clips (C2), S1 | | | | | | |
| `MT-REC-05` | dim light clips (C2), S2 | | | | | | |
| `MT-REC-05` | dim light clips (C2), S3 | | | | | | |
| `MT-REC-05` | dim light clips (C2), S4 | | | | | | |
| `MT-REC-06` | clips checked, S1 | | | | | | |
| `MT-REC-06` | clips checked, S2 | | | | | | |
| `MT-REC-06` | clips checked, S3 | | | | | | |
| `MT-REC-06` | clips checked, S4 | | | | | | |
| `MT-REC-07` | zip and SHA-256 handed over, S1 | | | | | | |
| `MT-REC-07` | zip and SHA-256 handed over, S2 | | | | | | |
| `MT-REC-07` | zip and SHA-256 handed over, S3 | | | | | | |
| `MT-REC-07` | zip and SHA-256 handed over, S4 | | | | | | |
| `MT-REC-08` | ingest and report | | | | | | |

## B. Volunteer attack session

| Id | Step | Tester | OS / device | Pass / fail | Time | Notes | File names |
|---|---|---|---|---|---|---|---|
| `MT-PAD-01` | briefing, roles, consent | | | | | | |
| `MT-PAD-02` | item under test frozen | | | | | | |
| `MT-PAD-03` | source sessions | | | | | | |
| `MT-PAD-04` | artefacts made and labelled | | | | | | |
| `MT-PAD-05` | webcam recordings of artefacts | | | | | | |
| `MT-PAD-06` | registration session | | | | | | |
| `MT-PAD-07` | face sign-in, full-system test (c) | | | | | | |
| `MT-PAD-08` | supervisor workflows | | | | | | |
| `MT-PAD-09` | Android replication | | | | | | |
| `MT-PAD-10` | session filmed | | | | | | |
| `MT-PAD-11` | sheet converted, report, prints shredded | | | | | | |

## C. Desktop client with a webcam

| Id | Step | Tester | OS / device | Pass / fail | Time | Notes | File names |
|---|---|---|---|---|---|---|---|
| `MT-DT-01` | tools installed, builds done | | | | | | |
| `MT-DT-02` | MockSync up | | | | | | |
| `MT-DT-03` | MockMDS on the webcam | | | | | | |
| `MT-DT-04` | policy and pool read | | | | | | |
| `MT-DT-05` | client started, officer1 login | | | | | | |
| `MT-DT-06` | operator onboarding (officer2) | | | | | | |
| `MT-DT-07` | resident registration, live face | | | | | | |
| `MT-DT-08` | genuine failure and retry | | | | | | |
| `MT-DT-09` | attacks at registration | | | | | | |
| `MT-DT-10` | dim light and quality | | | | | | |
| `MT-DT-11` | supervisor onboarding | | | | | | |
| `MT-DT-12` | face sign-in | | | | | | |
| `MT-DT-13` | packet authentication | | | | | | |
| `MT-DT-14` | exception authentication | | | | | | |
| `MT-DT-15` | end-of-day approval and upload | | | | | | |
| `MT-DT-16` | supervisor override | | | | | | |
| `MT-DT-17` | offline | | | | | | |
| `MT-DT-18` | device fault | | | | | | |
| `MT-DT-19` | sign-in lock | | | | | | |

## D. Android client on a real phone

| Id | Step | Tester | OS / device | Pass / fail | Time | Notes | File names |
|---|---|---|---|---|---|---|---|
| `MT-AND-01` | USB debugging | | | | | | |
| `MT-AND-02` | APK installed, adb reverse | | | | | | |
| `MT-AND-03` | machine keys read | | | | | | |
| `MT-AND-04` | phone registered in MockSync | | | | | | |
| `MT-AND-05` | first login and sync | | | | | | |
| `MT-AND-06` | device certificate | | | | | | |
| `MT-AND-07` | operator and supervisor onboarding | | | | | | |
| `MT-AND-08` | face sign-in | | | | | | |
| `MT-AND-09` | registration, packet authentication, attacks | | | | | | |
| `MT-AND-10` | supervisor approval and override | | | | | | |
| `MT-AND-11` | offline | | | | | | |
| `MT-AND-12` | B7 and B8 measurement | | | | | | |

## E. Demo video dry run

| Id | Step | Tester | OS / device | Pass / fail | Time | Notes | File names |
|---|---|---|---|---|---|---|---|
| `MT-VID-01` | desktop prepared, screen clean | | | | | | |
| `MT-VID-02` | live segments | | | | | | |
| `MT-VID-03` | replayed segments | | | | | | |
| `MT-VID-04` | Android segment | | | | | | |
| `MT-VID-05` | timing | | | | | | |
| `MT-VID-06` | final check | | | | | | |

## Headline numbers

Copy these from the reports after `MT-REC-08` and `MT-PAD-11`.

| Report | File | APCER_max per transaction (species) | BPCER per transaction | Transactions |
|---|---|---|---|---|
| Team recordings (`ingest-recordings.py`) | | | | |
| Volunteer session, desktop | | | | |
| Volunteer session, Android | | | | |

## Send it back

1. Check that the notes hold no name, password or face picture.
2. Send the filled file to Kanav by the team chat or on the USB stick with the recordings.
3. Keep recordings, sheets with pseudonyms and zip files out of git. Hand the recordings over as `MT-REC-07`
   describes (zip file and SHA-256).
4. Kanav copies the outcomes into the test report and the traceability matrix after the team agrees to publish
   them.
