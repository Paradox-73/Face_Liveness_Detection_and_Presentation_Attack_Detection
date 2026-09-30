# Software bill of materials and licence review

## 1. Files

| File | Covers | Generated with |
|---|---|---|
| [liveness-bom.json](liveness-bom.json) | `liveness/` reactor: `liveness-core`, `liveness-onnx`, `face-match-sdk` and their compile, provided and runtime dependencies | `mvn -f liveness/pom.xml org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json -DoutputName=liveness-bom` |
| [mock-sync-bom.json](mock-sync-bom.json) | `tools/mock-sync` (everything packed into `mock-sync.jar`) | `mvn -f tools/mock-sync/pom.xml org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom -DoutputFormat=json -DoutputName=mock-sync-bom` |

- Format: CycloneDX 1.6 JSON, with package URLs, SHA hashes and the licences declared in each POM.
- Generated on 2026-09-30 from commit `c959af4` plus this documentation. The plugin is called by its coordinates,
  so no POM was changed; output lands in each module's `target/` and was copied here.
- Test-scope dependencies are not in the SBOMs (they are not shipped); they are listed in section 4.
- Components outside Maven (model files, Android Gradle dependencies, Python tools) are listed by hand in sections
  3-5.
- Regenerate after any dependency change and commit the new JSON.

## 2. Maven components (from the SBOMs)

### 2.1 `liveness/`

| Component | Version | Scope | Licence (as declared) | OSI-approved | Note |
|---|---|---|---|---|---|
| `io.mosip.liveness:liveness-core`, `liveness-onnx`, `face-match-sdk` | 1.0.0-SNAPSHOT | this project | MIT | yes | |
| `com.microsoft.onnxruntime:onnxruntime` | 1.20.0 | provided (shipped by the desktop client) | MIT | yes | jar contains `ThirdPartyNotices.txt` for the native libraries it bundles |
| `io.mosip.kernel:kernel-biometrics-api` | 1.2.0.2 | provided (already in the desktop client) | MPL-2.0 | yes | not redistributed by this project |
| `javax.xml.bind:jaxb-api` | 2.3.1 | provided (already in the desktop client) | CDDL-1.1 or GPL-2.0 with Classpath Exception | **GPL-2.0 option: yes; CDDL-1.1: no** (only CDDL-1.0 is OSI-approved) | **Flag:** not redistributed by this project; the dual licence allows the OSI-approved GPL-2.0 + Classpath Exception option |

### 2.2 `tools/mock-sync` (`mock-sync.jar`)

| Component group | Versions | Licence (as declared) | OSI-approved |
|---|---|---|---|
| Spring Boot, Spring Framework (web, webmvc, context, beans, aop, expression, core, jcl) | 2.7.18 / 5.3.31 | Apache-2.0 | yes |
| Apache Tomcat embed (core, el, websocket) | 9.0.83 | Apache-2.0 | yes |
| Jackson (core, databind, annotations, datatypes, yaml, parameter names) | 2.13.5 | Apache-2.0 | yes |
| springdoc-openapi (ui, webmvc-core, common), Swagger core/annotations/models, swagger-ui webjar | 1.7.0 / 2.2.9 / 4.18.2 | Apache-2.0 | yes |
| SnakeYAML, Commons Lang 3, log4j-api, log4j-to-slf4j, jakarta.validation-api | 1.30 / 3.12.0 / 2.17.2 / 2.0.2 | Apache-2.0 | yes |
| SLF4J API, jul-to-slf4j | 1.7.36 | MIT | yes |
| Logback classic and core | 1.2.12 | EPL-1.0 or LGPL-2.1 | yes (both) |
| jakarta.annotation-api | 1.3.5 | EPL-2.0 or GPL-2.0 with Classpath Exception | yes |
| Microsoft TSS.Java | 0.3.0 | MIT | yes |
| JNA | 4.4.0 | LGPL-2.1 or Apache-2.0 | yes (both) |
| Bouncy Castle (bcprov, bcpkix, bcutil jdk15on) | 1.70 | "Bouncy Castle Licence" | yes: the text is the MIT licence with Bouncy Castle's copyright line (`licenses/MIT-BouncyCastle.txt`) |
| jakarta.xml.bind-api, jakarta.activation-api | 2.3.3 / 1.2.2 | BSD-3-Clause (Eclipse Distribution License 1.0) | yes |

MockSync's data files keep their upstream MPL-2.0 licence (`tools/mock-sync/data/LICENSE-NOTE.md`).

## 3. Components outside the Maven SBOMs

### 3.1 Models (`models/`, shipped in `liveness-onnx-...-models.jar` and the APK)

| Model | Licence | OSI-approved | Use restrictions or caveats |
|---|---|---|---|
| YuNet face detector (OpenCV Zoo) | MIT | yes | none |
| MediaPipe Face Mesh V2 (PINTO conversion, fp16 weights) | Apache-2.0 | yes | model card: "not intended for human life-critical decisions"; used only to detect challenge actions |
| MiniFASNetV2, MiniFASNetV1SE (Minivision weights, yakhyo ONNX) | Apache-2.0 | yes | training data not disclosed by the authors |
| SFace (OpenCV Zoo, fp16 weights) | Apache-2.0 | yes | trained on research datasets per its paper; used for 1:1 matching and binding, not for the PAD decision |
| 30 MediaPipe canonical-face vertices embedded in `liveness-onnx` `mesh/CanonicalFace.java` | Apache-2.0 | yes | attribution in the source file |

Sources, commits and hashes: `models/SOURCES.md`.

### 3.2 Android additions (`clients/android/android/livenessmanager`, Gradle)

| Component | Version | Licence | OSI-approved |
|---|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.28.0 | MIT | yes |
| `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | 1.4.2 | Apache-2.0 | yes |
| `io.mosip.liveness:*` (this project) | 1.0.0-SNAPSHOT | MIT | yes |

Existing ARC dependencies the module also uses (licensed by upstream ARC): JP2ForAndroid 1.0.4 (BSD-2-Clause,
with OpenJPEG, BSD-2-Clause), MOSIP `biometrics-util` 1.3.0 (MPL-2.0), Dagger 2.41 (Apache-2.0), AndroidX
AppCompat / Material / ConstraintLayout (Apache-2.0).

### 3.3 Desktop additions (`clients/desktop/registration/registration-services/pom.xml`)

`liveness-core`, `liveness-onnx`, `liveness-onnx:models`, `face-match-sdk` (MIT) and `onnxruntime` 1.20.0 (MIT).
Everything else the desktop client ships is upstream MOSIP's choice.

## 4. Test and build tools (not shipped)

| Tool | Where | Licence | OSI-approved |
|---|---|---|---|
| JUnit 5 (Jupiter, BOM 5.10.5) | liveness, tools | EPL-2.0 | yes |
| JUnit 4, Mockito, Robolectric | clients (upstream test stacks) | EPL-1.0 / MIT / MIT | yes |
| ArchUnit | liveness | Apache-2.0 | yes |
| SLF4J NOP | liveness tests | MIT | yes |
| JaCoCo Maven plugin | liveness | EPL-2.0 | yes |
| animal-sniffer Maven plugin | liveness | MIT | yes |
| `com.toasttab.android:gummy-bears-api-28` signatures | liveness (Android API check) | not declared in the downloaded artefact | **not verified** |
| CycloneDX Maven plugin 2.9.1 | SBOM generation | Apache-2.0 | yes |
| `com.github.jai-imageio:jai-imageio-jpeg2000` 1.3.0 | `face-match-sdk` tests (the desktop client ships it upstream) | "BSD 3-clause with nuclear disclaimer" plus the JJ2000 licence | **no: the JJ2000 licence is not OSI-approved.** Not redistributed by this project |
| MOSIP kernel-core, keymanager, biosdk-provider, logger | test scope in MockSync and face-match-sdk | MPL-2.0 | yes |
| SnakeYAML 2.2 | `tools/eval/scenario-runner` | Apache-2.0 | yes |
| Python: PyYAML, Pillow, numpy, matplotlib, pytest | `tools/eval` | MIT, MIT-CMU (HPND), BSD-3-Clause, Matplotlib licence (PSF-based), MIT | yes except matplotlib's own licence text, which is PSF-based but not on the OSI list; used locally only |

## 5. External programs and data (not shipped)

| Item | Licence | Note |
|---|---|---|
| ffmpeg | LGPL-2.1+ or GPL-2.0+ depending on the build | optional, installed by the user for MockMDS webcam/video sources and frame-pack cutting; never bundled |
| Evaluation source clips (`eval/packs/sources.yaml`) | CC BY-SA 3.0 (Wikimedia Commons), Pexels License | downloaded at test time, never committed or distributed. CC licences are not software licences; the Pexels License is not an open licence. Used only for local regression tests |
| MockMDS upstream dependencies (`devices/mock-mds`) | as declared by MOSIP | not changed by this project |

## 6. Result of the review

- Every component this project **adds and ships** (engine jars, models, ONNX Runtime, CameraX, MockSync's runtime
  jars) is under an OSI-approved licence: MIT, Apache-2.0, BSD-3-Clause, EPL-1.0 / EPL-2.0, LGPL-2.1 or MPL-2.0 (or
  a dual licence with an OSI-approved option).
- Flagged, none of them shipped by this project:
  1. `jaxb-api` (provided): choose the GPL-2.0 + Classpath Exception option; CDDL-1.1 alone is not OSI-approved.
  2. `jai-imageio-jpeg2000` (test scope here; shipped by upstream MOSIP): JJ2000 licence is not OSI-approved.
  3. `gummy-bears-api-28` (build-time signature file): licence not declared in the artefact; check before relying
     on it outside development.
  4. Pexels-licensed evaluation clips: never redistribute.
- Model caveats are about training data and use, not licences; they are disclosed in `models/SOURCES.md`.
- Licence texts required for redistribution are in `licenses/`; attributions in `NOTICE`.
