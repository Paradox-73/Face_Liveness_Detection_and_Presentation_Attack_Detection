# Scenario run legacy-strict

| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt | passive score per attempt | reasons (last attempt) |
|---|---|---|---|---|---|---|---|---|---|---|
| atk-m-mockmds-stock | attack | SYN-P0 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-m-mockmds-stock | attack | SYN-P0 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| atk-s2-adaptive-screen | attack | SYN-A7 | 1 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-adaptive-screen | attack | SYN-A7 | 2 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-adaptive-screen | attack | SYN-A7 | 3 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-adaptive-screen | attack | SYN-A7 | 4 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| atk-s2-injected-still | attack | SYN-A1 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s2-injected-still | attack | SYN-A1 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s2-looped-clip | attack | SYN-A8 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-looped-clip | attack | SYN-A8 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-looped-clip | attack | SYN-A8 | 3 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-looped-clip | attack | SYN-A8 | 4 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-print-moved | attack | SYN-A3 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 90, 90, 90 | CHALLENGE_TIMEOUT |
| atk-s2-print-moved | attack | SYN-A3 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 90, 90, 90 | CHALLENGE_TIMEOUT |
| atk-s2-print-moved | attack | SYN-A3 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 90, 90, 90 | CHALLENGE_TIMEOUT |
| atk-s2-print-moved | attack | SYN-A3 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 90, 90, 90 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-screen-photo | attack | SYN-A5 | 1 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 8, 8, 8 | PAD_SCORE |
| atk-s2-screen-photo | attack | SYN-A5 | 2 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 8, 8, 8 | PAD_SCORE |
| atk-s2-screen-photo | attack | SYN-A5 | 3 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 8, 8, 8 | PAD_SCORE |
| atk-s2-screen-photo | attack | SYN-A5 | 4 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 8, 8, 8 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 14, 14, 14 | PAD_SCORE |
| atk-s3-adaptive-screen | attack | SYN-A7 | 1 | 1 | ACCEPT | REJECT | **NO** | none | 82 |  |
| atk-s3-adaptive-screen | attack | SYN-A7 | 2 | 1 | ACCEPT | REJECT | **NO** | none | 82 |  |
| atk-s3-adaptive-screen | attack | SYN-A7 | 3 | 1 | ACCEPT | REJECT | **NO** | none | 82 |  |
| atk-s3-adaptive-screen | attack | SYN-A7 | 4 | 1 | ACCEPT | REJECT | **NO** | none | 82 |  |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | CHALLENGE_TIMEOUT |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | WRONG_ACTION |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | ONSET_TOO_FAST |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | WRONG_ACTION |
| atk-s3-injected-still | attack | SYN-A1 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s3-injected-still | attack | SYN-A1 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s3-looped-clip | attack | SYN-A8 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 100, 100, 100 | LOOP |
| atk-s3-looped-clip | attack | SYN-A8 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 100, 100, 100 | LOOP |
| atk-s3-looped-clip | attack | SYN-A8 | 3 | 3 | REJECT | REJECT | yes | integrity, integrity, challenge | 100, 100, 100 | ONSET_TOO_FAST |
| atk-s3-looped-clip | attack | SYN-A8 | 4 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 100, 100, 100 | LOOP |
| atk-s3-print-moved | attack | SYN-A3 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-moved | attack | SYN-A3 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-moved | attack | SYN-A3 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-moved | attack | SYN-A3 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still-login | attack | SYN-A2 | 1 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still-login | attack | SYN-A2 | 2 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still-login | attack | SYN-A2 | 3 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still-login | attack | SYN-A2 | 4 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 65, 65, 65 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 65, 65, 65 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 65, 65, 65 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 65, 65, 65 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | ONSET_TOO_FAST |
| atk-s3-screen-replay-login | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | ONSET_TOO_FAST |
| atk-s3-screen-replay | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| bf-s1-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-active | bona_fide | BF | 2 | 2 | ACCEPT | ACCEPT | yes | challenge, none | 75, 75 |  |
| bf-s1-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-login | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-login | bona_fide | BF | 2 | 2 | ACCEPT | ACCEPT | yes | challenge, none | 75, 75 |  |
| bf-s1-login | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-login | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-registration | bona_fide | BF | 2 | 2 | ACCEPT | ACCEPT | yes | challenge, none | 75, 75 |  |
| bf-s1-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s2-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-login | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-login | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-login | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-login | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s3-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-dim | bona_fide | BF | 1 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 4, 4, 4 | PAD_SCORE |
| bf-s3-dim | bona_fide | BF | 2 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 4, 4, 4 | PAD_SCORE |
| bf-s3-dim | bona_fide | BF | 3 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 4, 4, 4 | PAD_SCORE |
| bf-s3-dim | bona_fide | BF | 4 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 4, 4, 4 | PAD_SCORE |
| bf-s3-login | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-login | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-login | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-login | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| other-blurred | other | OTHER | 1 | 3 | REJECT | NO_RESPONSE | **NO** | challenge, challenge, challenge | 46, 46, 46 | NEUTRAL_TIMEOUT |
| other-no-face | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | NO_FACE+PASSIVE_TIMEOUT |
| other-s1-wrong-action | other | OTHER | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| other-s1-wrong-action | other | OTHER | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| other-s3-ignores-prompt | other | OTHER | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | CHALLENGE_TIMEOUT |
| other-s3-ignores-prompt | other | OTHER | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | CHALLENGE_TIMEOUT |
| other-stream-ends | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | error, error, error, error, error | -, -, -, -, - | DEVICE_ERROR |
| other-too-dark | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | TOO_DARK+PASSIVE_TIMEOUT |
| other-two-faces | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | MULTI_FACE+PASSIVE_TIMEOUT |

Transactions: 131, expectation mismatches: 13.

Per analysed frame (decode + analysis + PAD, wall clock): n=21514, mean 24.6 ms, p50 22.2 ms, p95 44.6 ms, max 217.3 ms.
- decode: n=21514, mean 3.9 ms, p50 3.7 ms, p95 5.9 ms
- analyze: n=21473, mean 15.8 ms, p50 14.7 ms, p95 25.9 ms
- pad: n=11098, mean 7.4 ms, p50 6.7 ms, p95 11.8 ms
- embed: n=1171, mean 18.8 ms, p50 17.0 ms, p95 32.2 ms
