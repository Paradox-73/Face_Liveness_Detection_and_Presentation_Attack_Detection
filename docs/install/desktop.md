# Desktop Registration Client: build and run locally

This page covers building the MOSIP desktop Registration Client in
`clients/desktop/` (upstream `mosip/registration-client` at `22fe01f`, version
1.2.0.2) and starting it on a Windows machine, either against the default MOSIP
host or against the local MockSync server in `tools/`.

## 1. Prerequisites

| Item | Version used | Notes |
|---|---|---|
| Windows | 11 x64 | The client ships with a Windows-only Java runtime and `run.bat`. |
| Git Bash | any | Runs `scripts/desktop-build.sh`. Needs `curl`, `unzip`, `sha256sum` (all bundled with Git for Windows). |
| Windows PowerShell | 5.1 | Runs `scripts/desktop-run.ps1`. |
| Maven | 3.9.9 | Must be on `PATH`. |
| JDK 11 with `javac` | Azul Zulu 11.0.8 with JavaFX (`zulu11.41.23-ca-fx-jdk11.0.8-win_x64`) | Optional: if `JAVA11_HOME`/`JAVA_HOME` do not point at a complete JDK 11, the build script downloads this one (267 MB, SHA-256 checked). |
| Disk | about 1.5 GB | Maven repository about 630 MB (build plus unit tests), run layout about 550 MB, downloads about 340 MB. |
| Network | Maven Central, `cdn.azul.com` | Only for the build. The client itself needs a server only for first login and sync. |

**Maven mirror for retired repositories.** The upstream parent POM lists the
retired `oss.sonatype.org` repository under the id `ossrh`. Route it to Maven
Central with the settings file the repository ships,
[`scripts/release/maven-settings.xml`](../../scripts/release/maven-settings.xml):

```bash
export MAVEN_ARGS="-s $PWD/scripts/release/maven-settings.xml"   # from the repository root
```

## 2. Build

From the repository root, in Git Bash:

```bash
bash scripts/desktop-build.sh               # server host dev.mosip.net (upstream default)
bash scripts/desktop-build.sh --mocksync    # server host localhost:8443 (MockSync)
bash scripts/desktop-build.sh --hostname api.example.org   # any other host[:port]
```

The script:

1. picks a JDK 11 that has `javac` and the Java crypto policy files, or downloads
   the Zulu 11.0.8 FX JDK into `<run root>/cache/`;
2. runs `mvn -B clean install -DskipTests -Dgpg.skip` in `clients/desktop/registration/`;
3. assembles the run layout (section 3). With `--mocksync` or `--hostname`, it
   rewrites `mosip.hostname` in `props/mosip-application.properties` of the run
   layout's `registration-services` jar with `jar uf`, as upstream `configure.sh`
   does when MOSIP packages the client.

Other options: `--fast` builds only `registration-client` and the modules it
needs; `--skip-build` re-assembles from the last build; `--run-dir DIR` changes
the output folder. `<run root>` is `$MOSIP_RUN_ROOT`, or the folder `run` next
to the repository.

The same Maven build without the script (server host `dev.mosip.net`, as upstream):

```bash
cd clients/desktop/registration
mvn -B clean install -DskipTests -Dgpg.skip
```

**Measured build times** (12-thread laptop, `MAVEN_OPTS=-Xmx1536m`):

| Case | Maven "Total time" |
|---|---|
| First build, empty `~/.m2` (all dependencies downloaded) | 5 min 33 s |
| Rebuild | 1 min 28 s to 1 min 55 s |

Rebuild times vary by about 20 s with other load on the machine.

### 2.1 Build changes compared with upstream

`registration-services/pom.xml` adds the liveness dependencies (`liveness-core`,
`liveness-onnx` and its `models` jar, `onnxruntime` 1.20.0, `face-match-sdk`) and
turns off the engine preload in tests; `registration-client/pom.xml` adds JUnit
for its tests. Nothing else in the Maven build differs from upstream: the server
host is set after the build (step 3 above).

## 3. Run layout

`scripts/desktop-build.sh` writes this folder outside the git tree
(default `<run root>/desktop`, for example `D:\mosip\run\desktop`):

| Path | Content |
|---|---|
| `jre\` | Zulu 11.0.8 JRE with JavaFX (`zulu11.41.23-ca-fx-jre11.0.8-win_x64`), the runtime MOSIP bundles. Kept across rebuilds, so imported certificates stay. |
| `lib\` | `registration-client-1.2.0.2.jar`, its 428 dependency jars, `logback.xml`, and `mock-sdk.jar` (MOSIP mock biometric SDK 1.1.5 from Maven Central, the version `configure.sh` installs). Replaced on every rebuild. |
| `run.bat` | Double-click launcher: `jre\bin\javaw -Xmx2048m -Dfile.encoding=UTF-8 -cp "lib/*" io.mosip.registration.controller.Initialization`. |
| `BUILD-INFO.txt` | Client version, `mosip.hostname`, `environment`, git commit, time of assembly. |
| `db\`, `.mosipkeys\`, `logs\` | Created by the client on first start (section 4). Kept across rebuilds. |

Compared with the official `reg-client.zip` built by `configure.sh`, the layout
has no jar signatures and no `MANIFEST.MF`. The client accepts this only with
`environment=LOCAL`, which is the value in this repository's
`props/mosip-application.properties`.

## 4. Run

From the repository root, in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1          # start in the background
powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -Stop    # close it
```

Options of `desktop-run.ps1`:

| Option | Effect |
|---|---|
| `-TrustCert <file>` | Imports a certificate (PEM or DER) into `jre\lib\security\cacerts` under alias `mocksync` (change with `-TrustAlias`). Needed once for MockSync's self-signed HTTPS certificate: the client uses the JRE's default trust store for every server call. |
| `-Reset` | Deletes `db\`, `.mosipkeys\` and `logs\` so the client starts as a fresh install. |
| `-Console` | Runs `java.exe` in the current window and waits, instead of `javaw.exe` in the background. |
| `-MaxHeap 2048m` | Java maximum heap. |
| `-RunDir <folder>` | Another run layout. |

What happens on start (observed on 2026-09-28):

- The pre-loader window lists the start-up steps, including
  `mosip.hostname : <host>` and `Machine is ONLINE/OFFLINE`. ONLINE means
  `GET https://<host>/v1/syncdata/actuator/health` succeeded.
- The login screen appears after 23-36 s (four starts measured; the slower ones
  ran next to a Maven test run). `logs\registration.log` then contains
  `Login screen loaded`.
- The first start creates the embedded Derby database in `db\reg` and stores its
  password, encrypted with the machine key, in `.mosipkeys\db.conf`.
- The first login needs a reachable server: it downloads the configuration,
  master data, users and certificates. Without MockSync or a MOSIP server the
  client stops at the login screen.

`-Stop` closes both client windows, which makes the client close its key store
(`Closed the Client Security Instance` in the log) and exit. It force-stops the
process only if that takes longer than 20 s, which happens when `-Stop` is used
before the login screen has appeared.

## 5. Machine keys (needed by the server before first login)

The client signs and decrypts with a machine key pair:

- **TPM 2.0 present** (for example this team's development laptop): the client
  uses TPM keys. The log shows `TPMClientCryptoServiceImpl ... Instantiating
  Platform TPM`, and `.mosipkeys\` contains only `db.conf`. No `readme.txt` is
  written.
- **No usable TPM**: the log shows `USING LOCAL CLIENT SECURITY INITIALIZED`, and
  the client writes `reg.key`, `reg.pub` and `readme.txt` (machine name, public
  key, key index) into `.mosipkeys\` under the working directory (the run layout
  folder).

To print the values in both cases, run `tools/mock-sync/scripts/MachineKeyTool.java` with a JDK 11 (Java's
source-file mode needs the compiler, which the bundled JRE lacks), from the run layout folder:

```powershell
cd <run root>\desktop
& "$env:JAVA11_HOME\bin\java.exe" -cp "lib\*" <repo>\tools\mock-sync\scripts\MachineKeyTool.java machine-keys.json
```

It prints one JSON object (also written to `machine-keys.json`) with `clientType` (TPM or LOCAL), `name` (lower-case
host name, as the client reports it), `publicKey` and `signPublicKey` (URL-safe Base64) and `keyIndex` (the value the
client sends in `GET /v1/syncdata/configs/{key_index}`). `tools/mock-sync/scripts/register-machine.ps1` runs the same
tool and posts the result to a running MockSync.

TPM keys are the same for every run folder on one machine, so `-Reset` does not
change them. Local keys are regenerated after `-Reset`, and the server must
then be given the new values.

## 6. Using the client with MockSync

1. `bash scripts/desktop-build.sh --mocksync`. The run folder's `lib/` then holds the liveness jars
   (`liveness-core`, `liveness-onnx`, `liveness-onnx-...-models` with the signed ONNX models), ONNX Runtime 1.20.0,
   the SFace face-match SDK (`face-match-sdk`, the FACE matcher) and MOSIP's mock SDK (`mock-sdk.jar`, finger and
   iris; downloaded once into `run/cache/`).
2. Start MockSync (`tools/mock-sync/scripts/run-mock-sync.sh`) and register the machine from section 5 with it.
3. `powershell -ExecutionPolicy Bypass -File scripts\desktop-run.ps1 -TrustCert <MockSync certificate>`
4. The pre-loader shows `mosip.hostname : localhost:8443` and `Machine is ONLINE`. The first login runs the initial
   sync and ends with a restart alert; after the restart log in again. MockSync's config selects the SFace SDK for
   FACE (`mosip.biometric.sdk.providers.face.mockvendor.*`); SDK keys apply only after a client restart.
5. Devices: `scripts/mockmds-run.sh` starts MockMDS on port 4501 with the evaluation frame packs
   (`run/frame-packs`) allowed as face sources. Operator onboarding asks for the biometrics in
   `mosip.registration.operator.onboarding.bioattributes` (MockSync override: ten fingers and face).
6. Model bundle version: the shipped minimum is 1.3.0 (`mosip.registration.liveness.model.min_version`, served by
   MockSync's `mock-sync-defaults.properties`), and the bundle in the engine jar is 1.3.0. A run folder built before
   that change holds bundle 1.0.1, which the client refuses (`MODEL_VERSION`; liveness fails closed). Rebuild the
   engine (`mvn -f liveness/pom.xml install`), rebuild the client, run `scripts/desktop-build.sh` again (it replaces only
   `lib/` and keeps the database and machine keys), restart MockSync, then run Synchronize Data.
7. Model-signing key: the client loads no model until `mosip.registration.liveness.model.trust_anchor_pem` names the
   key the bundle is signed with (unset or unknown: `CONFIG_INVALID`, liveness fails closed). The desktop client stores
   synced values in 512 characters and a PEM RSA 3072 key is about 600, so set the key's fingerprint
   (`sha256:<64 hex>`, `ModelTrustAnchors.fingerprint`). MockSync sends the development key's fingerprint
   `sha256:99538367853153c2969000626bcd6bdd28e7267d0649c0057662f85ee23e8b80`. A fingerprint picks only a key shipped
   in the client: the development key, or a key in a `liveness-model-trust-anchors.pem` file on the class path
   (see `docs/design/configuration.md`).

### 6.1 Scripted end-to-end demo

`scripts/demo-desktop.sh` runs every face liveness flow on the real client with no mouse or keyboard (it also works
on a locked screen): login, registration (passive pass, active challenges, attacks, device disconnect, maximum
attempts), reviewer and end-of-day supervisor authentication, a config change by sync, an L1 device, offline, and a
wrong person refused by the face match. It writes `driver.log`, `results.txt`, masked PNG snapshots and the
packets' liveness evidence to `run/demo/desktop-<time>/`. Results of the reference run:
`docs/test-report/desktop-e2e.md`.

```bash
bash scripts/demo-desktop.sh --onboard                            # once per machine, section 6.2; about 5 minutes
bash scripts/demo-desktop.sh --restore-golden --policy recorded   # about 20 minutes; 71 of 71 checks in two runs on 2026-10-20
bash scripts/demo-desktop.sh --flow tools/mock-sync/e2e/flows/demo-desktop.flow --out /tmp/demo
```

`--policy recorded` keeps the shipped policy but cuts the challenge pool to blink and smile, the actions the enrolled
face pack `s3` has footage of; without it the turn-right challenges cannot be answered and many checks fail. A turn
challenge needs a live face. A flow file of one section does not start the client by itself: wrap it in a file with
`include 00-start.flow`, `include 10-login.flow`, `section <nn>-<name>.flow` and `exit`.

### 6.2 Onboard the demo users and save the golden snapshot

The scripted demo needs `officer1` and `supervisor1` onboarded with face pack `s3` (their faces are what the demo's
face sign-in and supervisor checks match). One command does it on a fresh client (no `db/` yet: a new build, or after
`desktop-run.ps1 -Reset`), with the client stopped:

```bash
bash scripts/demo-desktop.sh --onboard        # about 5 minutes; then run the demo with --restore-golden
```

It starts MockSync and MockMDS when they are not running, registers the machine in MockSync
(`tools/mock-sync/scripts/register-machine.sh`), runs officer1's first login (the initial sync; the client then exits
to restart, flow `tools/mock-sync/e2e/flows/onboard-sync.flow`), starts the client again and onboards both users
(`flows/onboard-desktop.flow`: password login, then `lib/onboard.flow` with ten fingers and both irises from MockMDS
and the face from pack `s3` with liveness). It always uses the recorded challenge pool (blink and smile), because
`s3` has no head-turn footage. Output: `run/demo/desktop-<time>/1-sync/` and `2-onboard/` (`driver.log`, masked
snapshots). Measured on 2026-10-17 on the team laptop from an empty client and an empty MockSync `work/` folder:
4 of 4 checks, 5 minutes, MockSync logged `IDA auth for officer1: biometrics {FACE=1, FINGER=10, IRIS=2}`.

At the end it saves the golden snapshot: `run/desktop/db/` and `.mosipkeys/` copied to `run/golden/desktop/`.
`--restore-golden` copies them back before a demo run, and `--save-golden` (client stopped) saves the current state
again. A snapshot works only on the machine that made it: the database password is sealed with that machine's TPM
key (or its local key files), and MockSync must still know the machine (`tools/mock-sync/work/machines.json`). On
another machine, run `--onboard` there instead of copying a snapshot.

## 7. Unit tests

```bash
cd clients/desktop/registration
mvn -B -pl registration-services test -Dgpg.skip
```

- The active `unit-test` profile runs surefire 2.12.4 with JUnit 4. That version fails with a
  `StringIndexOutOfBoundsException` when `-Dtest` lists several `Class#method` selections joined with `+`: select one
  class or one method per run. Write new
  tests in this module with JUnit 4; JUnit 5 tests would not run.
- Baseline on 2026-09-28 (with the build changes in 2.1): 751 run, 0 failures,
  2 errors, 17 skipped, 9 min 3 s. Maven reports BUILD FAILURE because of the
  2 errors.
- Both errors are `java.lang.SecurityException: Trusted certificate is expired`
  in `ClientIntegrityValidatorTest.integrityCheckTest` and
  `ManifestCreatorTest.integrityCheckTest`. The test certificate
  `registration-services/src/test/resources/provider.pem` expired on
  2024-10-23. These tests fail on any run after that date.
- The upstream tests write into `registration-services/`: they empty the
  tracked `MANIFEST.MF` and create `lib/`, `SCHEMA_0.5.json` and
  `newProcess.json`. Restore `MANIFEST.MF` and delete the three new entries
  before committing.

## 8. Known issues

- **Incomplete JDK folders fail early.** A JDK without
  `conf/security/policy/unlimited/*.policy` makes Maven fail on the first
  download with `java.lang.SecurityException: Can not initialize cryptographic
  mechanism ... Missing mandatory jurisdiction policy files: unlimited`. A JDK
  without `bin/javac` cannot compile. `desktop-build.sh` checks both and falls
  back to downloading a complete JDK.
- **Jars that `configure.sh` downloads from MOSIP's internal artifactory are
  missing:** `icu4j.jar`, `kernel-transliteration-icu4j.jar` (transliteration of
  typed names), `clamav.jar`, `kernel-virusscanner-clamav.jar` (packet virus
  scan). The client looks these up only when the feature is used; the effect of
  their absence on registration has not been tested yet.
- **Upstream log noise on start:**
  - `startup-err.log` shows `ClassNotFoundException:
    io.mosip.registration.config.DerbySlf4jBridge`. `DaoConfig` points Derby's
    error stream at this class, which does not exist in the source tree. Derby
    keeps working and writes its messages to `startup.log`.
  - The first start logs `ERROR ... DaoConfig ... derby.connection.requireAuthentication
    : is not set to preferred value`, and later starts do not.
  - `SLF4J: The requested version 1.7.16 by your slf4j binding is not compatible
    with [1.6]` and Java illegal-reflective-access warnings.
- `mosip.client.upgrade.server.url` stays `https://dev.mosip.net` in every build.
  The client uses it only for software upgrades, which `environment=LOCAL` skips.
- The official `run.bat` also sets `-Xms2048m`; the generated one does not, so
  the client does not reserve 2 GB at start.
- The bundled JRE is Windows x64 only, so the run layout works only on Windows.
  The Maven build has not been tried on Linux or macOS.
