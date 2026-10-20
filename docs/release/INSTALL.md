# Installing the release on a new computer

This page installs the release artefacts on a Windows computer that has never built the project. It
needs no Java, Maven, Git or internet access: every package carries its own Java runtime.

At the end, three programs run on the same computer:

| Program | What it is | Package |
|---|---|---|
| MockSync | Local stand-in for the MOSIP server (login, sync, onboarding, packet upload, liveness settings) | `mosip-liveness-mocksync-<version>-win-x64.zip` |
| MockMDS | Simulated biometric device (SBI 0.9.5) whose face camera can play frames, videos or a webcam | `mosip-liveness-mockmds-<version>-win-x64.zip` |
| Registration Client | MOSIP desktop Registration Client 1.2.0.2 with face liveness and presentation attack detection | `mosip-liveness-desktop-client-<version>-win-x64.zip` |

`<version>` is the release version in the file names, for example `1.0.0`. What every file contains:
[README.md](README.md).

## 1. What you need

- Windows 10 or 11, 64-bit, with Windows PowerShell 5.1 (part of Windows).
- 8 GB RAM (in the release test the three programs used about 1.2 GB together: client 860 MB,
  MockSync 170 MB, MockMDS 125 MB) and 2 GB free disk space.
- Free local ports: 8443 and 8080 (MockSync), one port from 4501 to 4600 (MockMDS).
- Optional: a TPM 2.0 security chip. The client stores its machine keys in the TPM when there is one,
  and in the `.mosipkeys\` folder when there is none. Both work.

## 2. Download and check the files

1. Download the three zip files and `SHA256SUMS.txt` into one folder, for example `C:\mosip\downloads`.
2. Check that no file was damaged or changed. In PowerShell, in that folder:

   ```powershell
   Get-ChildItem *.zip | ForEach-Object {
     $expected = (Select-String -Path SHA256SUMS.txt -SimpleMatch $_.Name).Line.Split(' ')[0]
     $actual = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
     '{0}  {1}' -f $(if ($actual -eq $expected) { 'OK  ' } else { 'FAIL' }), $_.Name
   }
   ```

   Every line must start with `OK`. In Git Bash or Linux, `sha256sum -c --ignore-missing SHA256SUMS.txt`
   does the same.

## 3. Unpack

Unpack all three zips into one folder, for example `C:\mosip`. Each zip holds one folder named like
the zip. The Windows `tar` command is the fastest way (a few seconds for the 512 MB client zip):

```powershell
cd C:\mosip
Get-ChildItem downloads\*.zip | ForEach-Object { tar -xf $_.FullName -C . }
```

`Expand-Archive downloads\<file>.zip -DestinationPath .` or Explorer's "Extract all" also work, but
take minutes for the client zip. If Explorer was used on a downloaded zip, Windows may block the
`.bat` files; unblock them once with `Get-ChildItem -Recurse C:\mosip | Unblock-File`.

Result:

```
C:\mosip\mosip-liveness-mocksync-<version>-win-x64\
C:\mosip\mosip-liveness-mockmds-<version>-win-x64\
C:\mosip\mosip-liveness-desktop-client-<version>-win-x64\
```

## 4. Start MockSync

Open a Command Prompt or PowerShell window and run:

```powershell
C:\mosip\mosip-liveness-mocksync-<version>-win-x64\start-mocksync.bat
```

- The first start creates `work\` next to the script: server keys, the HTTPS certificate, registered
  machines, setting overrides and uploaded packets.
- Ready when the window shows `Started MockSyncApplication in ... seconds` (about 5 seconds).
- Check: open <http://127.0.0.1:8080/admin/machines> in a browser. It shows `[]` on a new install.
- Keep the window open. Ctrl+C stops MockSync.

## 5. Start MockMDS

Open a second window and run:

```powershell
C:\mosip\mosip-liveness-mockmds-<version>-win-x64\start-mockmds.bat
```

- Check: <http://127.0.0.1:4501/admin/liveness> answers with `"errorInfo":"Success"` and
  `"deviceStatus":"Ready"`. When port 4501 is taken, MockMDS takes the next free port up to 4600, and
  the client finds it there.
- The face camera shows a still image by default. To play the sample frames instead:

  ```powershell
  curl.exe -X POST http://127.0.0.1:4501/admin/source -H "Content-Type: application/json" `
    -d '{\"source\":\"SEQUENCE\",\"path\":\"frames/sample-sequence\",\"fps\":15,\"loop\":true}'
  ```

  Other sources (video file, webcam through ffmpeg), injected faults and the L1 device mode are
  described in `docs\LIVENESS-EXTENSIONS.md` inside the MockMDS folder.
- Keep the window open. Ctrl+C stops MockMDS.

## 6. Connect the Registration Client to MockSync

With MockSync running, run once:

```powershell
C:\mosip\mosip-liveness-desktop-client-<version>-win-x64\setup-mocksync.bat
```

Expected output:

```
Trusted MockSync HTTPS certificate (...\certs\mocksync-tls.pem) in ...\jre\lib\security\cacerts
Registered machine '<your computer name>' (machine id 10001, centre 10001, key type TPM)
```

- The first line: the client's own Java runtime now trusts MockSync's HTTPS certificate. Nothing is
  added to Windows or to any other Java installation.
- The second line: MockSync now knows this computer's machine keys. The key type is `LOCAL` on a
  computer without a TPM.
- Run it again whenever MockSync's `work\` folder was deleted, because MockSync then has a new
  certificate and no registered machines.

## 7. Start the Registration Client

```powershell
C:\mosip\mosip-liveness-desktop-client-<version>-win-x64\start-client.bat
```

- A splash window ("Client Pre-Loader") appears, then the MOSIP login screen with a user name field.
  This took 21 to 23 seconds in the release test. The splash window lists the start-up checks and
  shows `Machine is ONLINE` when the client reached MockSync.
- The client writes its log to `logs\registration.log` in its folder. The line
  `Login screen loaded` confirms the login screen.
- The client keeps its state next to `start-client.bat`: `db\` (encrypted database), `.mosipkeys\`
  and `logs\`.
- `stop-client.bat` closes it. `start-client.bat -Reset` deletes `db\`, `.mosipkeys\` and `logs\` to
  start again as a new installation (run `setup-mocksync.bat` again afterwards on a computer
  without a TPM, because new local keys are created).

## 8. First login

The demo logins are in `docs\TEST-DATA.md` inside the MockSync and client folders (they exist only in
MockSync).

1. Log in as `officer1`. The client downloads its settings, master data and certificates from
   MockSync (the initial sync) and then shows "Restart Application". Confirm; the client closes.
2. Run `start-client.bat` again and log in as `officer1` again. The home screen says the operator is
   not onboarded yet.
3. Operator onboarding, resident registration with face liveness, face login and supervisor
   approval are walked through in the end-to-end report `docs/test-report/desktop-e2e.md` of the
   source repository.

Face liveness settings (mode, thresholds, challenges, retries) come from MockSync. Change one and the
client receives it at its next login or Sync Data:

```powershell
cd C:\mosip\mosip-liveness-mocksync-<version>-win-x64
.\set-config.ps1 -Set @{ 'mosip.registration.liveness.passive.threshold' = '85' }
.\set-config.ps1                    # lists overrides and the liveness settings in effect
```

## 9. Android client (optional)

The release APKs are production builds of the Android Registration Client with the liveness module:

| File | Device |
|---|---|
| `mosip-liveness-android-<version>-arm64-v8a.apk` | 64-bit ARM tablets and phones (Android 9 or newer) |
| `mosip-liveness-android-<version>-x86_64.apk` | Android emulator on an x86_64 computer |

Install with `adb install -r <file>.apk` (Android SDK platform tools), or copy the file to the tablet
and open it.

- The release build talks to the server address compiled into it (upstream's
  `https://api-internal.sandbox.xyz.net`) and allows HTTPS only, so it does not reach MockSync.
- For a demo against MockSync, build the debug APK, which points at `http://127.0.0.1:8080` through
  `adb reverse`: see [docs/install/android.md](../install/android.md).
- The APKs are signed with the Android debug key. Sign with your own key for any real deployment
  (see section 10).

## 10. Using a real MOSIP server instead of MockSync

The client zip is built for MockSync (`mosip.hostname=localhost:8443`, see `BUILD-INFO.txt`). For a
real MOSIP environment, build the client for that host from source and package it:

```bash
bash scripts/desktop-build.sh --hostname api-internal.example.org --run-dir <folder>
```

For Android, set `serverBaseURL` in `clients/android/android/build.gradle` and a signing key
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword` in `clients/android/android/key.properties`),
then run `bash scripts/release.sh --only android`.

## 11. Uninstall

Stop the three programs (`stop-client.bat`, Ctrl+C in the two windows) and delete `C:\mosip`. Nothing
was installed elsewhere. On a computer with a TPM, the client's machine keys stay in the TPM, as for
any MOSIP client installation.

## 12. Troubleshooting

| What you see | Cause and fix |
|---|---|
| MockSync window: `Port 8443 was already in use` | Another MockSync (or other program) uses the port. Stop it, or run MockSync on other ports with `start-mocksync.bat --server.port=9443 --mocksync.http-port=9080` and `setup-mocksync.bat -AdminUrl http://127.0.0.1:9080`. The client zip always calls port 8443, so it needs the MockSync that runs there. |
| `setup-mocksync.bat`: `MockSync does not answer at http://127.0.0.1:8080` | Start MockSync first (step 4) and wait for the `Started` line. |
| Client log: `PKIX path building failed` or login says the server cannot be reached | The client does not trust the running MockSync's certificate, usually because `work\` was deleted. Run `setup-mocksync.bat` again and restart the client. |
| Login fails with a machine error (`KER-SNC-155`, machine not found) | The computer is not registered in this MockSync. Run `setup-mocksync.bat` again. |
| Face capture says the camera is not available | MockMDS is not running, or the client started before it. Start MockMDS, then use Retry (or restart the client). |
| With a looped `SEQUENCE` source, a face capture sometimes ends with "Face verification could not be completed" and Retry then passes | The frame integrity check saw the same recorded frames come round again: audit event `REG-EVT-LV08` with reason `LOOP`. A looped clip shorter than the 300-frame loop window (20 s at 15 fps) repeats inside one capture, and each `/admin/source` switch starts the clip again at its first frame. This is the replay check working on a replayed clip, not a fault. Select Retry, or use a clip longer than 20 s or a webcam (`FFMPEG` source), or play the clip with `"pingpong":true` and switch back to it with `"resume":true` (`devices/mock-mds/LIVENESS-EXTENSIONS.md`): later back-and-forth sweeps play slower, so frames do not repeat in the same order. The scripted desktop demo plays its packs this way. |
| `start-client.bat`: `Client already running` | A client from this folder is still running. `stop-client.bat` first. |
| The login screen does not appear after 2 minutes | Read `logs\registration.log` and `startup-err.log` in the client folder. |
