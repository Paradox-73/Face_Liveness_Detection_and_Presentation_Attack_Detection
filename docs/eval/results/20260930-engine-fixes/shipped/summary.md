# Scenario run shipped

| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt | passive score per attempt | reasons (last attempt) |
|---|---|---|---|---|---|---|---|---|---|---|
| atk-m-mockmds-stock | attack | SYN-P0 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-m-mockmds-stock | attack | SYN-P0 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 64, 64, 64 | CHALLENGE_TIMEOUT |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 64, 64, 64 | WRONG_ACTION |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 64, 64, 64 | WRONG_ACTION |
| atk-s1-adaptive-wrong-clip | attack | SYN-A9 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 64, 64, 64 | WRONG_ACTION |
| atk-s2-adaptive-screen | attack | SYN-A7 | 1 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-adaptive-screen | attack | SYN-A7 | 2 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-adaptive-screen | attack | SYN-A7 | 3 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-adaptive-screen | attack | SYN-A7 | 4 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 94, 94, 94 | WRONG_ACTION |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 94, 94, 94 | WRONG_ACTION |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 94, 94, 94 | WRONG_ACTION |
| atk-s2-adaptive-wrong-clip | attack | SYN-A9 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 94, 94, 94 | WRONG_ACTION |
| atk-s2-injected-still | attack | SYN-A1 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s2-injected-still | attack | SYN-A1 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s2-looped-clip | attack | SYN-A8 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-looped-clip | attack | SYN-A8 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-looped-clip | attack | SYN-A8 | 3 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-looped-clip | attack | SYN-A8 | 4 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | 97, 97, 97 | LOOP |
| atk-s2-print-moved | attack | SYN-A3 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 72, 72, 72 | CHALLENGE_TIMEOUT |
| atk-s2-print-moved | attack | SYN-A3 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 72, 72, 72 | CHALLENGE_TIMEOUT |
| atk-s2-print-moved | attack | SYN-A3 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 72, 72, 72 | CHALLENGE_TIMEOUT |
| atk-s2-print-moved | attack | SYN-A3 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 72, 72, 72 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-still | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-print-tilted | attack | SYN-A4 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 76, 76, 76 | CHALLENGE_TIMEOUT |
| atk-s2-screen-photo | attack | SYN-A5 | 1 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 9, 9, 9 | PAD_SCORE |
| atk-s2-screen-photo | attack | SYN-A5 | 2 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 9, 9, 9 | PAD_SCORE |
| atk-s2-screen-photo | attack | SYN-A5 | 3 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 9, 9, 9 | PAD_SCORE |
| atk-s2-screen-photo | attack | SYN-A5 | 4 | 3 | REJECT | REJECT | yes | passive_pad, passive_pad, passive_pad | 9, 9, 9 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s2-screen-replay | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | active_pad, active_pad, active_pad | 20, 20, 20 | PAD_SCORE |
| atk-s3-adaptive-screen | attack | SYN-A7 | 1 | 1 | ACCEPT | REJECT | **NO** | none | 73 |  |
| atk-s3-adaptive-screen | attack | SYN-A7 | 2 | 1 | ACCEPT | REJECT | **NO** | none | 73 |  |
| atk-s3-adaptive-screen | attack | SYN-A7 | 3 | 1 | ACCEPT | REJECT | **NO** | none | 73 |  |
| atk-s3-adaptive-screen | attack | SYN-A7 | 4 | 1 | ACCEPT | REJECT | **NO** | none | 73 |  |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | CHALLENGE_TIMEOUT |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | WRONG_ACTION |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | WRONG_ACTION |
| atk-s3-adaptive-wrong-clip | attack | SYN-A9 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | WRONG_ACTION |
| atk-s3-injected-still | attack | SYN-A1 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s3-injected-still | attack | SYN-A1 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | FROZEN |
| atk-s3-looped-clip | attack | SYN-A8 | 1 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | LOOP |
| atk-s3-looped-clip | attack | SYN-A8 | 2 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | LOOP |
| atk-s3-looped-clip | attack | SYN-A8 | 3 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | LOOP |
| atk-s3-looped-clip | attack | SYN-A8 | 4 | 3 | REJECT | REJECT | yes | integrity, integrity, integrity | -, -, - | LOOP |
| atk-s3-print-moved | attack | SYN-A3 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-moved | attack | SYN-A3 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-moved | attack | SYN-A3 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-moved | attack | SYN-A3 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still-login | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still-login | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still-login | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still-login | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-print-still | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 99, 99, 99 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 79, 79, 79 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 79, 79, 79 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 79, 79, 79 | CHALLENGE_TIMEOUT |
| atk-s3-screen-photo | attack | SYN-A5 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 79, 79, 79 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | NOT_HELD |
| atk-s3-screen-replay-login | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | NOT_HELD |
| atk-s3-screen-replay-login | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | NOT_HELD |
| atk-s3-screen-replay | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | NOT_HELD |
| atk-s3-screen-replay | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | NOT_HELD |
| atk-s3-screen-replay | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | NOT_HELD |
| bf-s1-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-login | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-login | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-login | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-login | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s2-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-active | bona_fide | BF | 4 | 2 | ACCEPT | ACCEPT | yes | binding, none | 94, 94 |  |
| bf-s2-login | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-login | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-login | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-login | bona_fide | BF | 4 | 2 | ACCEPT | ACCEPT | yes | binding, none | 94, 94 |  |
| bf-s2-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 4 | 2 | ACCEPT | ACCEPT | yes | binding, none | 94, 94 |  |
| bf-s3-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-dim | bona_fide | BF | 1 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 3, 3, 3 | PAD_SCORE |
| bf-s3-dim | bona_fide | BF | 2 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 3, 3, 3 | PAD_SCORE |
| bf-s3-dim | bona_fide | BF | 3 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 3, 3, 3 | PAD_SCORE |
| bf-s3-dim | bona_fide | BF | 4 | 3 | REJECT | ACCEPT | **NO** | passive_pad, passive_pad, passive_pad | 3, 3, 3 | PAD_SCORE |
| bf-s3-login | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-login | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-login | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-login | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| other-blurred | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | BLURRY+PASSIVE_TIMEOUT |
| other-no-face | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | NO_FACE+PASSIVE_TIMEOUT |
| other-s1-wrong-action | other | OTHER | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 64, 64, 64 | CHALLENGE_TIMEOUT |
| other-s1-wrong-action | other | OTHER | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 64, 64, 64 | WRONG_ACTION |
| other-s3-ignores-prompt | other | OTHER | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | CHALLENGE_TIMEOUT |
| other-s3-ignores-prompt | other | OTHER | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 100, 100, 100 | NOT_HELD |
| other-stream-ends | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | error, error, error, error, error | -, -, -, -, - | DEVICE_ERROR |
| other-too-dark | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | TOO_DARK+PASSIVE_TIMEOUT |
| other-two-faces | other | OTHER | 1 | 5 | NO_RESPONSE | NO_RESPONSE | yes | quality, quality, quality, quality, quality | -, -, -, -, - | MULTI_FACE+PASSIVE_TIMEOUT |

Transactions: 131, expectation mismatches: 8.

Per analysed frame (decode + analysis + PAD, wall clock): n=30964, mean 39.6 ms, p50 34.1 ms, p95 78.4 ms, max 587.6 ms.
- decode: n=30964, mean 5.5 ms, p50 4.8 ms, p95 10.1 ms
- analyze: n=30922, mean 26.0 ms, p50 22.4 ms, p95 51.2 ms
- pad: n=18549, mean 11.5 ms, p50 9.7 ms, p95 24.0 ms
- embed: n=1227, mean 28.1 ms, p50 22.9 ms, p95 57.1 ms
