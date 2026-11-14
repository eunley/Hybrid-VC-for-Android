# Build instructions

The repository includes src/, res/ and AndroidManifest.xml. Prepare external dependencies as described below.

## Termux build

Use an arm64 Android Termux private ext4 workspace. Provide JDK (21 recommended), clang, Python 3, zip, aapt1, zipalign and apksigner on PATH. This is an on-device arm64-v8a build, not a desktop cross-compilation setup. aapt2 is not a drop-in replacement.

Acquire dependencies legally from official sources yourself: Android API 34 at libs/android-34/android.jar (ANDROID_JAR), D8/R8 at libs/r8.jar (R8_JAR), matching QNN-capable ONNX Runtime Java classes at libs/ort/classes.jar (ORT_JAR) and JNI libraries at libs/ort/jni/arm64-v8a (ORT_NATIVE_DIR). Supply same-version QNN HTP, HtpPrepare, System and GPU, plus Skel/Stub pairs for V69/73/75/79/81/85 under libs/qnnsdk/jni/arm64-v8a (QNN_NATIVE_DIR): 16 packaged libraries. LIBS_DIR overrides the dependency root.

QNN is pinned to the full official **QAIRT 2.51.0.260929** SDK, build `v2.51.0.260929181845`. The source URL, locally computed original ZIP SHA-256, archive entry paths and library SHA-256 values are in `scripts/qnn-sdk-manifest.json`. The Maven `qnn-runtime:2.51.0` AAR omits V85 and is not a substitute. Download the [official complete SDK](https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.51.0.260929/v2.51.0.260929.zip), extract the four backend libraries and Stubs from `lib/aarch64-android/`, and each Skel from `lib/hexagon-v{arch}/unsigned/`.

Before building, `scripts/verify-qnn.py` checks required files, exact sizes and SHA-256. Missing V85, mixed releases and unverified extra QNN libraries stop the build. The manifest also permits the matching V68 pair, but the build does not package V68. Do not commit the production libraries.

ORT 1.29 explicitly parses `htp_arch=68/69/73/75/81` only. For V79/V85, the application omits that option and lets QNN detect the architecture, while checking both matching Skel and Stub are packaged. The test device is SM8850/V81; execution on V85 hardware remains untested. QNN provenance is confirmed; prepare other dependencies from their own sources.

Run `bash build-termux.sh` after final synchronization. Output is `.build/hybrid-vc.apk`; policy.dex is built from hsrc in temporary assets. A separate `.private/debug.jks` is generated locally by default, with the conventional local-only debug password. For your own signing key set KEYSTORE_PATH, KEYSTORE_ALIAS, KEYSTORE_PASSWORD and optionally KEY_PASSWORD. Passwords are supplied to the signer through environment variables. The production key is excluded; debug builds generally cannot update production-signed installations.

Obtain hubert.onnx, rmvpe.onnx and mel.bin separately with the required rights, and set MODEL_ASSETS_DIR to their containing directory. BundledModels requires specific sizes/hashes. Alternative models require compatibility work. An APK may build without these assets but inference initialization will be incomplete. No models, role packages or automatic download are included.

Full compilation can require substantial memory. Run actual heavy builds detached from MCP and track/clean up those tasks. This preparation performed static checks only and started no background builds.

## Tests and localization review

See [tests/README.md](tests/README.md) for test entry points and dependencies. After synchronization, run `bash tests/run-wire-tests.sh`. Business/chunk tests require an independently supplied JVM org.json jar via JSON_JAR. Chunk tests can generate 513 MiB by default: inspect options first and set TEST_WORK. Exclude generated files.

Review translated resource keys, format placeholders, plurals, escaping, RTL, fallback and real-device layouts. No translations were copied, changed or visually accepted in this preparation task.

See assets/local-http-api.txt and assets/upload_role.py for API usage. Read SECURITY.md before enabling listeners/uploads or privileged virtual microphone features. Resolve all project authorization, GPL artwork, model and QNN questions in THIRD_PARTY.md before release. This inventory is not a completed legal audit.

Template assets are required: set `TEMPLATE_ASSETS_DIR` to a directory containing `enc.onnx`, `enc.onnx.data`, and `dec.onnx`. They are packaged under `assets/tpl`; missing files in the active tpl directory are restored before UI or HTTP role import. Existing nonempty user templates are preserved. `dec.onnx` has inline weights and needs no `.data` file. Obtain appropriately licensed templates separately; expected sizes and SHA-256 are in `BundledTemplates.java`.
