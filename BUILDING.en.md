# Build instructions

The repository includes src/, res/ and AndroidManifest.xml. Prepare external dependencies as described below.

## Termux build

Use an arm64 Android Termux private ext4 workspace. Provide JDK (21 recommended), clang, Python 3, zip, aapt1, zipalign and apksigner on PATH. This is an on-device arm64-v8a build, not a desktop cross-compilation setup. aapt2 is not a drop-in replacement.

Acquire dependencies legally from official sources yourself: Android API 34 at libs/android-34/android.jar (ANDROID_JAR), D8/R8 at libs/r8.jar (R8_JAR), matching QNN-capable ONNX Runtime Java classes at libs/ort/classes.jar (ORT_JAR) and JNI libraries at libs/ort/jni/arm64-v8a (ORT_NATIVE_DIR). Supply QNN HTP, HtpPrepare, HtpV81Skel, HtpV81Stub, System and GPU libraries under libs/qnnsdk/jni/arm64-v8a (QNN_NATIVE_DIR). LIBS_DIR overrides the dependency root. Existing exact versions and provenance are unconfirmed. V81 compatibility depends on the device. Do not commit the production libs directory.

Run `bash build-termux.sh` after final synchronization. Output is `.build/hybrid-vc.apk`; policy.dex is built from hsrc in temporary assets. A separate `.private/debug.jks` is generated locally by default, with the conventional local-only debug password. For your own signing key set KEYSTORE_PATH, KEYSTORE_ALIAS, KEYSTORE_PASSWORD and optionally KEY_PASSWORD. Passwords are supplied to the signer through environment variables. The production key is excluded; debug builds generally cannot update production-signed installations.

Obtain hubert.onnx, rmvpe.onnx and mel.bin separately with the required rights, and set MODEL_ASSETS_DIR to their containing directory. BundledModels requires specific sizes/hashes. Alternative models require compatibility work. An APK may build without these assets but inference initialization will be incomplete. No models, role packages or automatic download are included.

Full compilation can require substantial memory. Run actual heavy builds detached from MCP and track/clean up those tasks. This preparation performed static checks only and started no background builds.

## Tests and localization review

After synchronization, run `bash tests/run-wire-tests.sh`. Business/chunk tests require an independently supplied JVM org.json jar via JSON_JAR. Chunk tests can generate 513 MiB by default: inspect options first and set TEST_WORK. Exclude generated files.

Review translated resource keys, format placeholders, plurals, escaping, RTL, fallback and real-device layouts. No translations were copied, changed or visually accepted in this preparation task.

See assets/local-http-api.txt and assets/upload_role.py for API usage. Read SECURITY.md before enabling listeners/uploads or privileged virtual microphone features. Resolve all project authorization, GPL artwork, model and QNN questions in THIRD_PARTY.md before release. This inventory is not a completed legal audit.
