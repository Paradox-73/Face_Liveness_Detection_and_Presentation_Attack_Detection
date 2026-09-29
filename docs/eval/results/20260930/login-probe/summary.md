# Scenario run login-probe

| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt | passive score per attempt | reasons (last attempt) |
|---|---|---|---|---|---|---|---|---|---|---|
| atk-s2-print-still-login | attack | SYN-A2 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s2-print-still-login | attack | SYN-A2 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 73, 73, 73 | CHALLENGE_TIMEOUT |
| atk-s3-print-still-login | attack | SYN-A2 | 1 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still-login | attack | SYN-A2 | 2 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still-login | attack | SYN-A2 | 3 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-print-still-login | attack | SYN-A2 | 4 | 1 | ACCEPT | REJECT | **NO** | none | 99 |  |
| atk-s3-screen-replay-login | attack | SYN-A6 | 1 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 2 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |
| atk-s3-screen-replay-login | attack | SYN-A6 | 3 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | ONSET_TOO_FAST |
| atk-s3-screen-replay-login | attack | SYN-A6 | 4 | 3 | REJECT | REJECT | yes | challenge, challenge, challenge | 82, 82, 82 | CHALLENGE_TIMEOUT |

Transactions: 12, expectation mismatches: 4.

Per analysed frame (decode + analysis + PAD, wall clock): n=2341, mean 41.6 ms, p50 28.9 ms, p95 119.8 ms, max 478.3 ms.
- decode: n=2341, mean 6.0 ms, p50 4.9 ms, p95 13.3 ms
- analyze: n=2341, mean 27.5 ms, p50 18.3 ms, p95 80.7 ms
- pad: n=1288, mean 11.6 ms, p50 8.3 ms, p95 31.4 ms
- embed: n=108, mean 29.4 ms, p50 21.3 ms, p95 75.9 ms
