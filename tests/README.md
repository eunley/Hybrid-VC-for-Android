# Tests

Run commands from the repository root. JDK 21 provides the FP16 reference methods used by `TemplateCompatTest`. Generated classes, stubs, logs and test data must remain outside commits.

- HTTP protocol: `bash tests/run-wire-tests.sh`.
- Authentication: `bash tests/run-auth-tests.sh`.
- Audio pipeline: `bash tests/run-audio-tests.sh`.
- HTTP business logic: set `JSON_JAR` to an independently supplied JVM `org.json` jar, then run `bash tests/run-business.sh`.
- Chunked uploads: set `JSON_JAR`, then run `bash tests/run-chunk-tests.sh --mib 3` for a smaller run. Set `TEST_WORK` to a disposable work directory. The default is 513 MiB of uncompressed data for each random/compressible ZIP case. These tests start temporary loopback servers. Import is mocked to stream and validate the ZIP; they do not prove real role extraction or model publication.
- Playback progress and waveform timeline: set `ANDROID_JAR` to the Android API 34 jar and `PLAYBACK_TEST_OUT` to a disposable output directory, then run `bash tests/run-playback-tests.sh`. This JVM suite does not open Android audio devices. It covers the helper producer, application decoder, counters, PCM alignment, freshness and waveform timeline.

Template restoration and FP16 compatibility need external template assets containing `enc.onnx`, `enc.onnx.data` and `dec.onnx`. With `TEMPLATE_ASSETS_DIR` pointing to those assets, use a disposable directory:

```sh
work=$(mktemp -d "${TMPDIR:-/tmp}/svc-template-tests.XXXXXX")
javac -d "$work" src/com/svc/voice/{PB,OnnxTpl,ModelFiles,BundledTemplates}.java tests/TemplateCompatTest.java
java -Xmx256m -cp "$work" com.svc.voice.TemplateCompatTest "$TEMPLATE_ASSETS_DIR"
rm -rf "$work"
```

The template suite checks all 65,536 binary16 patterns, two million sampled float patterns, and fresh/partial/deleted template restoration. It is a host JVM check, not an Android 15 device acceptance test. Playback protocol tests cannot prove that another application has consumed the audio. V85 library inclusion is not V85 hardware validation.
