# Scenario suite of 2026-10-14 at `PASSIVE_THEN_ACTIVE`

The shipped configuration with `registration`, `login_auth`, `onboard_auth`, `packet_auth`, `exception_auth` and
`eod_auth` set to `PASSIVE_THEN_ACTIVE` (`bash tools/eval/run-suites.sh OUT passive-first`): 100 of 226 attack
transactions accepted (S3 print, score 99; looped clips of genuine video, scores 96-100), BPCER 0.0%. The
comparison with the shipped defaults and the decision it led to are in
[`../20261014-shipped/README.md`](../20261014-shipped/README.md).
