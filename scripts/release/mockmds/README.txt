MockMDS: SBI 0.9.5 device simulator with face liveness extensions
=================================================================

MOSIP's mock biometric device service (face, finger, iris) with the team's extensions for face
liveness testing: the face stream can come from a still image, a folder of JPEG frames, a video
file or a webcam (ffmpeg); faults can be injected into the stream (freeze, drop, disconnect,
stall, ...); the certification level (L0/L1) and a PAD capability can be switched at run time.

Contents
  mock-mds.jar, lib\         the simulator and its runtime libraries
  application.properties     settings (face frame source, capture mode, certification, PAD)
  Biometric Devices\         device descriptors, keys and stock images (upstream MockMDS, MPL-2.0)
  Profile\                   stock finger/iris/face profiles
  frames\sample-sequence\    30 frames that the MockMDS tests draw from the stock stream
                             image, for trying the SEQUENCE source
  runtime\                   Java 21 runtime (only in the -win-x64 zip)
  start-mockmds.bat / .sh    start it in the foreground
  docs\LIVENESS-EXTENSIONS.md  frame sources, faults, admin API, configuration keys

Start:   start-mockmds.bat          (Linux/macOS: bash start-mockmds.sh)
Port:    127.0.0.1, first free port from 4501 (the clients scan 4501-4600)
Check:   curl http://127.0.0.1:4501/admin/liveness
Switch the face stream to the sample frames:
  curl -X POST http://127.0.0.1:4501/admin/source -H "Content-Type: application/json" ^
       -d "{\"source\":\"SEQUENCE\",\"path\":\"frames/sample-sequence\",\"fps\":15,\"loop\":true}"
Frame folders must lie under a root listed in mosip.mock.sbi.face.source.allowed.roots
(default: this folder). Never put images of real people here without their consent.
