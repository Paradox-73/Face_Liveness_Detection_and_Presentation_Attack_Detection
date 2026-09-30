# Release artefacts

One command builds every release artefact into `dist/` (gitignored, never committed):

```bash
. /e/Projects/MOSIP/toolchain/env.sh     # this project's workstation; elsewhere set JAVA11_HOME and JAVA21_HOME
bash scripts/release.sh                  # about 9 minutes on the project laptop
```

Install steps for a new computer: [INSTALL.md](INSTALL.md).

## What each artefact is

`<v>` is the release version: the Git tag without its `v` when one is given (`--version`), else
`git describe --tags --always --dirty` (for example `c959af4-dirty` for a build with uncommitted
changes).

| File | What it is | Size |
|---|---|---|
| `mosip-liveness-desktop-client-<v>-win-x64.zip` | Desktop Registration Client 1.2.0.2 built for MockSync (`-Pmocksync`, host `localhost:8443`), ready to run: `lib/` (435 jars, including `liveness-core`, `liveness-onnx`, the signed models jar, `onnxruntime-1.20.0.jar`, `face-match-sdk`, MOSIP `mock-sdk.jar`), Zulu 11.0.8 JRE with JavaFX, `start-client.bat`, `stop-client.bat`, MOSIP's `run.bat`, `setup-mocksync.bat` (trusts MockSync's HTTPS certificate in the bundled JRE and registers the machine keys), `tools/machine-key-tool.jar`, licences, `BUILD-INFO.txt` | 512 MB (658 MB unpacked) |
| `mosip-liveness-android-<v>-arm64-v8a.apk` | Android Registration Client release build for 64-bit ARM tablets (Android 9+), with the liveness module, ONNX Runtime and the signed models in its assets | 115 MB |
| `mosip-liveness-android-<v>-x86_64.apk` | The same for the x86_64 Android emulator | 117 MB |
| `mosip-liveness-mocksync-<v>-win-x64.zip` | MockSync, the local stand-in for the MOSIP server: `mock-sync.jar`, reference data, `config/application.properties` (device trust certificates from `certs/`), start scripts (`.bat`, `.sh`), `set-config` scripts, full documentation, demo logins, Java 21 runtime | 65 MB (84 MB unpacked) |
| `mock-sync-<v>.jar` | The MockSync executable jar alone (Java 11+); run it from a folder that holds `data/` from the zip | 31 MB |
| `mosip-liveness-mockmds-<v>-win-x64.zip` | MockMDS SBI 0.9.5 device simulator with the face-stream extensions: `mock-mds.jar`, runtime libraries, `application.properties`, device keys and profiles, start scripts, `docs/LIVENESS-EXTENSIONS.md` (frame sources, faults, admin API), 30 sample frames, Java 21 runtime | 194 MB (219 MB unpacked; 89 MB of it is upstream's OpenCV jar) |
| `liveness-core-1.0.0-SNAPSHOT.jar` | Liveness engine: sessions, policy, challenges, integrity checks, audit payloads (pure Java 11, no native code) | 0.3 MB |
| `liveness-onnx-1.0.0-SNAPSHOT.jar` | ONNX Runtime adapters: YuNet face detection, Face Mesh landmarks, MiniFASNet passive PAD, SFace embedding; signed model registry | 0.1 MB |
| `liveness-onnx-1.0.0-SNAPSHOT-models.jar` | The signed model bundle (5 ONNX models, `manifest.json`, `manifest.jws`) as class-path resources | 22.5 MB |
| `face-match-sdk-1.0.0-SNAPSHOT.jar` | MOSIP `IBioApi` face-match SDK built on SFace, loaded by the clients by class name | 0.04 MB |
| `SHA256SUMS.txt` | SHA-256 of every artefact above, in `sha256sum` format | |
| `manifest.json` | Version, Git commit, build time and platform, and for each artefact its size, SHA-256 and description | |

The engine jars keep their Maven file names, because the clients' builds and other Maven projects
use them under those coordinates (`io.mosip.liveness:*:1.0.0-SNAPSHOT`).

### Checksums of the tested build

Build `c959af4-dirty` (commit `c959af4`, plus the uncommitted release scripts), made on 2026-09-30
and used for the clean-install test below. Each new build has its own `SHA256SUMS.txt`.

```
8f37bb4d691b46e92b1293c3f0399d25fb7e7fd68099493d74abfe106e5d44f7  mosip-liveness-desktop-client-c959af4-dirty-win-x64.zip
6b755eb83509b5c9bff36e262ca4fc933829c8bc7ac2da984e03b15240947ed3  mosip-liveness-mockmds-c959af4-dirty-win-x64.zip
4431bfcf943e1f0dd3bfb9c1e30e0befe70ff1b373172a9526a4c3cbb57461b7  mosip-liveness-mocksync-c959af4-dirty-win-x64.zip
74c1b851600a6a6b706aa53dfadb1464e5de3f32587856a32b72699b9ffdf2dd  mosip-liveness-android-c959af4-dirty-arm64-v8a.apk
0d2e6363b09cc7c297e8e7c1e64d4c27a05815f15ffd9350323bdd8800cc265e  mosip-liveness-android-c959af4-dirty-x86_64.apk
7a9d14794b46fb4ee3f012a8a2ca0ef296d6abfb6118e9fdf06da554a80b9221  face-match-sdk-1.0.0-SNAPSHOT.jar
c2ebc8f31af630532e565d5f1d8b1379128342169a4db35640e75402afc8a0bb  liveness-core-1.0.0-SNAPSHOT.jar
5d550a924229ab9d87d4fb2b6a3c4c79f3ad18a3de6fc50e7a2141bdeb1277a8  liveness-onnx-1.0.0-SNAPSHOT.jar
57f65e956d3f60a8670f4d04028788bdc026a39bcab978e80d6b26fb72002eb3  liveness-onnx-1.0.0-SNAPSHOT-models.jar
7d30312150f3c04849257bf9f01fd7583f1f6e003d46113ade2b1c65886c91a1  mock-sync-c959af4-dirty.jar
```

## How the build works

`scripts/release.sh` (Git Bash on Windows; bash on Linux for the parts without Windows code):

| Part (`--only`) | Steps |
|---|---|
| `engine` | `mvn install` of `liveness/` with its tests (264 + 93 + 42), under the shared `liveness-build` lock, then copies the four jars |
| `desktop` | `scripts/desktop-build.sh --mocksync --run-dir <staging>` (Maven build of `clients/desktop`, fresh Zulu JRE from the checked download, MOSIP mock SDK), then adds the start/stop and MockSync setup scripts, compiles `tools/mock-sync/scripts/MachineKeyTool.java` into `tools/machine-key-tool.jar`, licences and model sources |
| `android` | `scripts/android-build.sh --release --abis <abi>` once per CPU type (`--android-abis`, default `arm64-v8a,x86_64`) |
| `mocksync` | copies `tools/mock-sync` sources to a staging folder (the running MockSync keeps its own `target/` jar open), `mvn package` with tests on JDK 11, then the zip |
| `mockmds` | the same for `devices/mock-mds` on JDK 21 (226 tests), with the runtime-scope libraries only (`dependency:copy-dependencies -DincludeScope=runtime`) |

Then it writes `SHA256SUMS.txt` and `manifest.json` for every file in `dist/`.

Options: `--only LIST`, `--version V`, `--out DIR`, `--android-abis LIST`, `--no-runtime`,
`--no-build` (engine and desktop: package the last build), `--skip-tests` (also leaves out the MockMDS
sample frames, which its tests generate), `--keep-staging`; details in
`bash scripts/release.sh --help`.

Repeatability:
- Every zip entry carries the same timestamp (`SOURCE_DATE_EPOCH`, by default the last commit's time),
  so the zip step adds no variation: zipping the same MockSync folder twice gave the same SHA-256.
- The liveness engine jars are byte-for-byte reproducible (`project.build.outputTimestamp` is fixed in
  `liveness/pom.xml`): `liveness-core` built on JDK 21 in this release has the same SHA-256
  (`c2ebc8f3...`) as the earlier build on JDK 11.0.8.
- The MockSync and MockMDS zips differed between two builds of the same commit in this test, because
  their poms (and the desktop client's) set no `project.build.outputTimestamp`, so Maven stamps the
  build time into the jars. Adding that property to those poms would make them reproducible too.
- The bundled Java 21 runtime is made with `jlink` from the build machine's JDK 21 (modules listed in
  `RUNTIME_MODULES` in the script; 53 MB). `--no-runtime` leaves it out; the start scripts then use
  `JAVA_HOME` or `java` on `PATH`.

## GitHub release workflow

`.github/workflows/release.yml` runs when a tag `v*` is pushed. On Ubuntu with JDK 11 and 21 it runs
`scripts/release.sh --only engine,mocksync,mockmds --no-runtime --version <tag>` and attaches the jars,
the MockSync and MockMDS zips (without a bundled runtime), `SHA256SUMS.txt` and `manifest.json` to a
GitHub Release for the tag. A tag with a hyphen (`v1.0.0-rc1`) makes a pre-release. The desktop zip and
the APKs need Windows and the Android SDK; build them with `scripts/release.sh` and upload them to the
same release by hand.

## Clean-install test (2026-09-30)

Done on the project laptop (Windows 11, TPM 2.0), in `E:/Projects/MOSIP/cleaninstall/`, outside the
repository, following only [INSTALL.md](INSTALL.md):

| Step | Result |
|---|---|
| 2. Checksums | the PowerShell check printed `OK` for all three zips |
| 3. Unpack | `tar -xf` unpacked the three zips in 4.2 s |
| 4. MockSync | on 8443/8080 it stopped with `Web server failed to start. Port 8443 was already in use.`, because the team's shared MockSync was running; started with the troubleshooting row's `--server.port=9443 --mocksync.http-port=9080`: `Started MockSyncApplication in 2.6 seconds` on the bundled Java 21 runtime, `/admin/machines` returned `[]`, `getcacertificates` served the MockMDS roots from `certs/` |
| 5. MockMDS | took port 4502 (4501 held by the shared MockMDS); `/admin/liveness` returned `Success`, `deviceStatus` `Ready`; the documented `curl.exe` call switched the face source to the 30 sample frames |
| 6. Setup | against the packaged MockSync: certificate imported, `Registered machine 'batcomputer' (machine id 10001, centre 10001, key type TPM)`, key index equal to the one the installed client uses. Then `-SkipRegister` against the shared MockSync on 8443, whose registry already held this machine |
| 7. Client | pre-loader `Machine is ONLINE`, `Login screen loaded` 21 s after start; `stop-client.bat` closed it |

![Pre-loader of the clean install: build setup, integrity check, host localhost:8443, Machine is ONLINE](screenshots/clean-install-preloader.png)

![Login screen of the clean install](screenshots/clean-install-login.jpg)

Not covered by the test: a login through the client GUI (no input automation on the locked test
session; the full login, sync and liveness flows of the same client build are in
`docs/test-report/desktop-e2e.md`), the APKs on a device (the shared emulator runs the team's debug
build; the APKs were checked with `aapt dump badging` and `apksigner verify`: package
`io.mosip.registration_client`, minimum SDK 28, one CPU type each, ONNX Runtime and the 5 models
present, signed with the Android debug certificate), and the GitHub workflow itself (it runs only
when a tag is pushed).

## Known limitations

- The release APKs use upstream's server address (`https://api-internal.sandbox.xyz.net`) and the
  Android debug signing key. They cannot reach MockSync; the MockSync demo uses the debug APK
  (`docs/install/android.md`).
- The desktop zip is built for MockSync on `localhost:8443`. Another host needs a build with
  `scripts/desktop-build.sh --hostname`.
- The zips' Java runtimes are Windows x64. On Linux or macOS the MockSync and MockMDS start scripts
  (`.sh`) use `JAVA_HOME` or `java` on `PATH` (MockSync: Java 11+, MockMDS: Java 21+); make them
  executable or run them with `bash`, because zip files keep no permissions.
- `start-client.bat` started from a program that captures its output (not from a console window) waits
  until the client exits, because the client inherits the output handles.
