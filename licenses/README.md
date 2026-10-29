# Licence texts

Full texts of the licences that apply to third-party components and model files this project adds. The project's
own code is MPL-2.0 (`../LICENSE`); attributions are in `../NOTICE`; the review with every component and version is
`../docs/sbom/README.md`.

| File | Licence | Applies to |
|---|---|---|
| `Apache-2.0.txt` | Apache License 2.0 | MediaPipe Face Mesh V2 model and canonical-face data, MiniFASNetV2 / V1SE models, SFace model, AndroidX CameraX 1.4.2, and in `mock-sync.jar`: Spring Boot / Framework, Tomcat embed, Jackson, springdoc-openapi, Swagger, SnakeYAML, Commons Lang 3, log4j-api / log4j-to-slf4j, jakarta.validation-api, JNA (Apache-2.0 option) |
| `MIT-YuNet.txt` | MIT, "Copyright (c) 2020 Shiqi Yu" | YuNet face detector model |
| `MIT-onnxruntime.txt` | MIT, "Copyright (c) Microsoft Corporation" | ONNX Runtime Java 1.20.0 and ONNX Runtime Android 1.20.0. The native libraries bundled in those artefacts come with Microsoft's `ThirdPartyNotices.txt`, which is inside the jar and AAR |
| `MIT-TSS.MSR.txt` | MIT, "Copyright (c) Microsoft Corporation" | Microsoft TSS.Java 0.3.0 (in `mock-sync.jar`) |
| `MIT-BouncyCastle.txt` | Bouncy Castle licence (MIT text), "Copyright (c) 2000-2021 The Legion of the Bouncy Castle Inc." | bcprov, bcpkix, bcutil 1.70 (in `mock-sync.jar`) |
| `MPL-2.0.txt` | Mozilla Public License 2.0 | the imported MOSIP desktop client and MockMDS (their own copies: `clients/desktop/LICENSE`, `devices/mock-mds/LICENSE`); MOSIP reference data in `tools/mock-sync/data/upstream/`; MOSIP `kernel-biometrics-api` and `biometrics-util` |
| `EPL-1.0.txt` | Eclipse Public License 1.0 | Logback 1.2.12 (EPL-1.0 option of its EPL-1.0 / LGPL-2.1 dual licence), in `mock-sync.jar` |
| `EPL-2.0.txt` | Eclipse Public License 2.0 | jakarta.annotation-api 1.3.5 (EPL-2.0 option), in `mock-sync.jar`; JUnit 5 (tests only) |
| `BSD-3-Clause.txt` | BSD 3-Clause template | jakarta.xml.bind-api 2.3.3 and jakarta.activation-api 1.2.2 (Eclipse Distribution License 1.0, "Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors"), in `mock-sync.jar` |
| `BSD-2-Clause.txt` | BSD 2-Clause template | JP2ForAndroid 1.0.4 and the OpenJPEG code inside it (an upstream ARC dependency that the built-in camera device now also uses for JPEG 2000 encoding) |
| `MIT.txt` | MIT template | SLF4J API and jul-to-slf4j 1.7.36 ("Copyright (c) QOS.ch"), in `mock-sync.jar` |

Where a licence file is a template (`<year> <owner>`), the copyright line of the component is the one given in this
table or in `NOTICE`.

The Android client (`clients/android/LICENSE`, MIT, "Copyright (c) 2022 Modular Open Source Identity Platform")
and the desktop client and MockMDS (MPL-2.0) keep their own licence files in their folders.
