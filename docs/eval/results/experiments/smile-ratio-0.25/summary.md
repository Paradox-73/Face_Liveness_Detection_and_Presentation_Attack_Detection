# Scenario run smile0.25

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
| bf-s3-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |

Transactions: 12, expectation mismatches: 4.

Per analysed frame (decode + analysis + PAD, wall clock): n=2794, mean 21.5 ms, p50 20.6 ms, p95 31.3 ms, max 251.7 ms.
- decode: n=2794, mean 3.1 ms, p50 3.0 ms, p95 4.1 ms
- analyze: n=2794, mean 14.1 ms, p50 13.8 ms, p95 18.1 ms
- pad: n=1488, mean 6.3 ms, p50 6.1 ms, p95 8.1 ms
- embed: n=114, mean 16.2 ms, p50 15.9 ms, p95 20.8 ms
