# Test data

All values here are synthetic and exist only in the local mock MOSIP server (`tools/mock-sync`).
They are not valid on any real MOSIP environment.

## Operator logins (mock server)

| User ID | Password | Role |
|---|---|---|
| `officer1` | `Mosip@Officer1` | Registration officer |
| `officer2` | `Mosip@Officer2` | Registration officer |
| `supervisor1` | `Mosip@Supervisor1` | Registration supervisor |

- The server stores only salted SHA-256 hashes of these passwords (`tools/mock-sync/data/users.json`).
- To add a user, compute `sha256(salt + password)` and add `{"userId", "passwordHash": "sha256:<salt>:<hex>", "name", "roles"}`.

## Keys and certificates

| Item | Where | Notes |
|---|---|---|
| Mock device keys (L0 and FTM/L1) | `devices/mock-mds/Biometric Devices/**` | MOSIP's public mock-device test keys; FTM root renewed locally because the upstream one expired in 2022 |
| Mock server TLS and signing keys | `tools/mock-sync/work/keys/` | Generated on first run, never committed (password `mocksync`) |
| Client trust store for the mock server | `tools/mock-sync/work/keys/client-truststore.p12` | Generated, never committed (password `changeit`) |
| Model signing key | outside the repository | Only the public key `models/model-signing-dev.pub.pem` is committed |

## Face data

- Frame packs come from openly licensed videos and synthetic attacks; see `eval/packs/sources.yaml` and `docs/eval/README.md`.
- No volunteer images are stored in git.
