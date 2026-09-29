# Scenario run smile0.4

| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt | passive score per attempt | reasons (last attempt) |
|---|---|---|---|---|---|---|---|---|---|---|
| bf-s1-active | bona_fide | BF | 1 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| bf-s1-active | bona_fide | BF | 2 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| bf-s1-active | bona_fide | BF | 3 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| bf-s1-active | bona_fide | BF | 4 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 75, 75, 75 | CHALLENGE_TIMEOUT |
| bf-s2-active | bona_fide | BF | 1 | 2 | ACCEPT | ACCEPT | yes | active_pad, none | 84, 84 |  |
| bf-s2-active | bona_fide | BF | 2 | 2 | ACCEPT | ACCEPT | yes | active_pad, none | 84, 84 |  |
| bf-s2-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s3-active | bona_fide | BF | 1 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 100, 100, 100 | NOT_HELD |
| bf-s3-active | bona_fide | BF | 2 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 100, 100, 100 | NOT_HELD |
| bf-s3-active | bona_fide | BF | 3 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 100, 100, 100 | NOT_HELD |
| bf-s3-active | bona_fide | BF | 4 | 2 | ACCEPT | ACCEPT | yes | challenge, none | 100, 100 |  |

Transactions: 12, expectation mismatches: 7.

Per analysed frame (decode + analysis + PAD, wall clock): n=3781, mean 23.3 ms, p50 22.2 ms, p95 34.7 ms, max 92.4 ms.
- decode: n=3781, mean 3.4 ms, p50 3.3 ms, p95 4.5 ms
- analyze: n=3781, mean 15.4 ms, p50 15.0 ms, p95 20.1 ms
- pad: n=2012, mean 6.8 ms, p50 6.7 ms, p95 8.9 ms
- embed: n=142, mean 17.3 ms, p50 17.1 ms, p95 23.7 ms
