# Licence of the data files

MockSync's code (`tools/mock-sync/src`, `scripts`) is MIT, like the rest of `tools/`.
The files below are copied or converted from MOSIP repositories and keep their upstream licence,
the **Mozilla Public License 2.0** (MPL-2.0, <https://mozilla.org/MPL/2.0/>).
The licence was checked with the GitHub licence API on 2026-09-28 (`spdx_id: MPL-2.0` for both repositories).

| Path | Source | Change |
|---|---|---|
| `upstream/mosip-data/*.json` | [mosip/mosip-data](https://github.com/mosip/mosip-data) branch `1.2.0.1`, commit `6985016c009ec0334b5e036f45e1c2847d1f5f0b`, folder `mosip_master/xlsx/` | converted from `.xlsx` to JSON by `scripts/fetch-data.py`: one object per row; `TRUE`/`FALSE` in `is_*` columns to booleans; `NULL` and `now()` to null; `template` keeps modules 10002 and 10008 only |
| `upstream/mosip-config/registration-default.properties`, `application-default.properties`, `applicanttype.mvel` | [mosip/mosip-config](https://github.com/mosip/mosip-config) tag `v1.2.0.1`, commit `d4c4f7edf4ef0dc9218a5e036ebe3e20ae99d04f` | none (byte copies) |
| `client-seed/initial-global-params.properties` | `clients/desktop/registration/registration-services/src/main/resources/initial.sql` in this repository (mosip/registration-client, MPL-2.0) | the `GLOBAL_PARAM` key/value pairs extracted into a properties file |

`SOURCES.json` records the SHA-256 of every downloaded source file.
Run `python scripts/fetch-data.py` to download the same commits again and regenerate these files.

MockSync's own data files (`users.json`, `mock-sync-defaults.properties`) are MIT.
The passwords in `users.json` are synthetic test values for a local mock server.
