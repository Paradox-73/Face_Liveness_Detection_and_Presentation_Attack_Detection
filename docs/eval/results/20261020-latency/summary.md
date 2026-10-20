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

Per analysed frame (decode + analysis + PAD, wall clock): n=4410, mean 51.2 ms, p50 39.4 ms, p95 127.7 ms, max 283.4 ms.
- decode: n=4410, mean 5.4 ms, p50 4.0 ms, p95 14.1 ms
- analyze: n=4410, mean 38.9 ms, p50 30.0 ms, p95 97.3 ms
- pad: n=2352, mean 8.8 ms, p50 6.5 ms, p95 22.3 ms
- embed: n=152, mean 54.7 ms, p50 45.5 ms, p95 124.0 ms
