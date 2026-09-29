# Scenario run latency

| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt | passive score per attempt | reasons (last attempt) |
|---|---|---|---|---|---|---|---|---|---|---|
| bf-s1-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-active | bona_fide | BF | 2 | 2 | ACCEPT | ACCEPT | yes | challenge, none | 75, 75 |  |
| bf-s1-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-registration | bona_fide | BF | 2 | 2 | ACCEPT | ACCEPT | yes | challenge, none | 75, 75 |  |
| bf-s1-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s1-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 75 |  |
| bf-s2-active | bona_fide | BF | 1 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| bf-s2-active | bona_fide | BF | 2 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| bf-s2-active | bona_fide | BF | 3 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, active_pad | 84, 84, 84 | PAD_SCORE |
| bf-s2-active | bona_fide | BF | 4 | 3 | REJECT | ACCEPT | **NO** | challenge, challenge, challenge | 84, 84, 84 | WRONG_ACTION |
| bf-s2-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s2-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 84 |  |
| bf-s3-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |

Transactions: 24, expectation mismatches: 4.

Per analysed frame (decode + analysis + PAD, wall clock): n=2501, mean 23.6 ms, p50 20.7 ms, p95 45.0 ms, max 110.2 ms.
- decode: n=2501, mean 2.6 ms, p50 2.2 ms, p95 4.5 ms
- analyze: n=2501, mean 15.9 ms, p50 13.8 ms, p95 26.9 ms
- pad: n=1393, mean 6.2 ms, p50 5.4 ms, p95 10.1 ms
- embed: n=162, mean 22.7 ms, p50 20.1 ms, p95 37.5 ms
