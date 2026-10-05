# Scenario run latency

| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt | passive score per attempt | reasons (last attempt) |
|---|---|---|---|---|---|---|---|---|---|---|
| bf-s1-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s1-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 64 |  |
| bf-s2-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s2-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 94 |  |
| bf-s3-active | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-active | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 1 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 2 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 3 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |
| bf-s3-registration | bona_fide | BF | 4 | 1 | ACCEPT | ACCEPT | yes | none | 100 |  |

Transactions: 24, expectation mismatches: 0.

Per analysed frame (decode + analysis + PAD, wall clock): n=4410, mean 96.0 ms, p50 65.7 ms, p95 242.4 ms, max 1003.0 ms.
- decode: n=4410, mean 9.1 ms, p50 6.0 ms, p95 24.5 ms
- analyze: n=4410, mean 68.5 ms, p50 48.0 ms, p95 167.0 ms
- pad: n=2352, mean 27.5 ms, p50 18.7 ms, p95 68.3 ms
- embed: n=152, mean 89.5 ms, p50 71.4 ms, p95 219.4 ms
