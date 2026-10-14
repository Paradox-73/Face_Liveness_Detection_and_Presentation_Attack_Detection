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

Per analysed frame (decode + analysis + PAD, wall clock): n=4410, mean 48.8 ms, p50 37.5 ms, p95 115.4 ms, max 558.0 ms.
- decode: n=4410, mean 5.0 ms, p50 4.4 ms, p95 10.7 ms
- analyze: n=4410, mean 34.1 ms, p50 26.3 ms, p95 77.0 ms
- pad: n=2352, mean 13.9 ms, p50 10.9 ms, p95 31.0 ms
- embed: n=152, mean 56.4 ms, p50 45.3 ms, p95 125.0 ms
