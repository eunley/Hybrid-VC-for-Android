# Hybrid VC for Android

[简体中文](README.md) | English

A free voice changer for Android, with audio file conversion, real-time voice conversion, quick recording, and a local HTTP API.

The project draws on the model pipeline used by [ultimate-rvc-mobile](https://github.com/Sharl210/ultimate-rvc-mobile), with optimizations and acceleration for mobile devices. It currently combines shared HuBERT and RMVPE models with SVC enc/dec models. Voice models are primarily compatible with the **so-vits-svc 4.0** ecosystem and can be downloaded from the community and imported into the app.

This is a personal exploration project. Most of the code was **written with assistance from GPT and DeepSeek**, with testing and adjustments by me. My development experience and access to test devices are limited. Developers interested in improving or forking the project are welcome. Personally, I think the project is already fairly complete and does not need more features, but I may adopt and work on good suggestions.

## Features

### Import voice models without converting them on a computer

After you import a compatible so-vits-svc 4.0 voice model, the app converts its weights into a runnable ONNX model on your phone. No prior conversion on a computer is required. Using the NPU also requires compilation on the device.

The app does not train voice models. If the community does not have the voice you want, you may still need a computer to train one yourself.

### Inference optimized for mobile devices

On a **Snapdragon 8 Elite Gen 5 (SM8850)** device, testing with **3000 ms chunks** produced the following results:

| Inference backend | Approximate RTF |
| --- | ---: |
| CPU | 0.65 |
| NPU | 0.20 |

RTF is the ratio of processing time to audio duration. A lower value means faster processing. For example, an RTF of 0.20 means that processing 10 seconds of audio takes about 2 seconds. It is not the end-to-end latency of real-time mode.

These are measurements from my own device, not a performance guarantee for every phone. Actual speed depends on the model, chunk size, temperature, background load, acceleration backend, and other factors.

### Real-time voice conversion and quick recording

- **Root mode**: Supports direct audio capture, voice conversion, and audio injection without installing an additional mounting module. Availability depends on the system and target app.
- **Without root**: Supports quick recording, conversion, and playback. This does not provide direct microphone audio injection into other apps.
- Audio file conversion and batch processing are also supported for offline use.

### Local HTTP service

With the HTTP service enabled, scripts, other devices, or AI clients with HTTP tools can call the API to manage voice models, upload files, and convert audio. Voice model ZIP uploads support chunking, resumable transfers, and SHA-256 verification.

- The service is disabled by default and defaults to local-only access.
- Disable “Local access only” to allow devices on your local network to connect.
- You can set a custom access token or generate a random key. **Requests are not authenticated when the token is empty.** Once a token is set, every request must include `Authorization: Bearer <token>`.
- HTTP traffic is not encrypted. Use the service on a trusted network.

See [local-http-api.txt](assets/local-http-api.txt) for the API reference and [upload_role.py](assets/upload_role.py) for the voice model upload client. An AI client needs its own internet access to search for voice models, and imported models must use a format supported by the app.

## Usage and compatibility

The current build has primarily been tested on a **realme GT8 Pro (RMX5200), running Android 16 with a Snapdragon 8 Elite Gen 5 (SM8850)**. Compatibility and performance on other devices have not been thoroughly verified.

### Devices that may support NPU acceleration

The current APK bundles HTP runtimes for V69/V73/V75/V79/V81/V85. If the app recognizes one of the Qualcomm SoCs below and the voice model can be compiled for the NPU on the device, HTP acceleration may be available. This table means that a matching runtime and SoC mapping are packaged; it is **not per-device validation or a performance guarantee**. SoC suffixes `-AA/-AB/-AC/-AD` are normalized to the base model.

| SoC | Common name | Packaged HTP |
| --- | --- | --- |
| SM8950 | Snapdragon 8 Elite Gen 6 | V85 |
| SM8975 | Snapdragon 8 Elite Extreme Gen 6 | V85 |
| SM8850 / SM8850-AD | Snapdragon 8 Elite Gen 5 | V81 |
| SM8750 / SM8750-AC | Snapdragon 8 Elite (SM8750-AC is the Galaxy S25 OC variant) | V79 |
| SM8650 | Snapdragon 8 Gen 3 | V75 |
| SM8550 | Snapdragon 8 Gen 2 | V73 |
| SM8635 | Snapdragon 8s Gen 3 | V73 |
| SM7675 | Snapdragon 7+ Gen 3 | V73 |
| SM7750 | Snapdragon 7s / 7 Gen 4 | V73 |
| SM6450 | Snapdragon 6 Gen 1 | V73 |
| SM8450 | Snapdragon 8 Gen 1 | V69 |
| SM8475 | Snapdragon 8+ Gen 1 | V69 |
| SM7450 | Snapdragon 7 Gen 1 | V69 |
| SM7475 | Snapdragon 7+ Gen 2 | V69 |

This is not a complete Snapdragon SoC list; omission does not necessarily mean a device is unsupported. Development testing still centers on SM8850, and V85 has not been validated on real hardware. Even on the same SoC, vendor firmware, FastRPC/DSP permissions, Android version, available memory and model size can make NPU initialization or compilation fail, in which case CPU fallback may be needed.

Basic workflow:

1. Install the app and grant the required permissions.
2. Import a compatible so-vits-svc 4.0 voice model and wait for conversion to finish.
3. Select the voice model and inference backend. NPU inference requires on-device compilation; CPU inference does not.
4. Load the model, then use file conversion, real-time voice conversion, or quick recording.

The full APK includes base models such as HuBERT and RMVPE, so it is relatively large. The source repository does not include these external models or runtime dependencies. To build the app yourself, see [BUILDING.en.md](BUILDING.en.md).

## Frequently asked questions

### Why is compatibility worse on devices other than the test device?

Development and testing have mainly focused on my own SM8850 device. I do not have enough devices to cover different chips, systems, and vendor implementations. NPU support in particular also depends on hardware and runtime compatibility.

If you encounter a problem, please provide your device model, Android version, app version, selected backend, and steps to reproduce it. Remove tokens and personal information before sharing logs.

### Why does performance get worse under heavy load?

Phones have limited computing power, memory bandwidth, and cooling capacity. Background tasks competing for resources, thermal throttling, or processing that cannot keep up with incoming audio can affect the smoothness and stability of real-time conversion.

### Why is conversion quality sometimes disappointing?

Quality depends on the voice model, input audio quality, pitch settings, and the current inference pipeline. Background music, reverb, environmental noise, and multiple speakers can introduce noise or distortion. Clear, dry recordings of a single speaker are recommended.

I have not yet found a better solution that balances speed, power consumption, and quality on mobile devices. Adding vocal separation would require more computation, so a complete dry-vocal extraction pipeline has not been integrated. The current implementation also cannot guarantee the same results as a full desktop SVC/RVC pipeline.

Interested users can explore other SVC/RVC implementations in Termux themselves, but that is outside the functionality currently provided by this app.

### Why use a hybrid model pipeline?

This was the approach chosen during early AI-assisted development. Later testing showed reasonably acceptable speed on the available device, so it was kept. It is a trade-off in the current implementation, not a claim that it is the best solution for every device or use case.

### Why is GPU acceleration not available?

A GPU approach was tried during development, but on the current test device and configuration, its benefits were smaller than those of the NPU. It was therefore not offered as a primary acceleration option. This does not mean GPUs have no value on other devices.

### Will the project continue to receive frequent updates?

The main features are now close to what I intended. Future updates may be limited to a few fixes, or development may stop for a long time. This is a personal project, with no commitment to an update schedule or ongoing maintenance.

### Why does voice conversion stop working when denoising is enabled?

The lightweight denoising model may remove parts of the voice as well. Try turning denoising off.

## Open source and acknowledgements

Original project code is licensed under the [MIT License](LICENSE). Third-party code, icons, runtimes, and models retain their own licenses and usage conditions; they are not all covered by MIT.

See [THIRD_PARTY.md](THIRD_PARTY.md) for sources, available license evidence, and items that still need verification. Code provenance, model weight licensing, and rights to use a voice are separate issues. Please make sure you have permission to use and share the models you import.

If you find missing attribution, a license mismatch, or another rights issue, please open an Issue with the relevant files, sources, and explanation. I will investigate and add attribution, correct the issue, or remove the material as appropriate. Do not upload private audio or models you are not authorized to share in public Issues.

Thanks to the related open-source projects and community contributors, and to everyone who has helped with testing and feedback.

## Technology stack

| Layer | Technologies and purpose |
| --- | --- |
| Application | Java, with native Android Activity, Service, and View components for the UI and background tasks |
| UI and visual effects | Android Canvas and custom Views; RuntimeShader / AGSL on Android 13 and later for some glass and refraction effects |
| Localization | Android string resources and in-app Locale switching |
| Model inference | ONNX Runtime Java/JNI, with CPU inference and Qualcomm QNN / HTP (NPU) backends |
| Audio models | HuBERT for speech feature extraction, RMVPE for pitch extraction, and SVC enc/dec voice models |
| Voice model import | Weight parsing, mapping, and ONNX model generation implemented in Java, with on-device preparation of compiled artifacts for the NPU |
| Audio processing | Android audio decoding APIs, AudioRecord / AudioTrack, chunk scheduling, and crossfading |
| Native components | C / JNI, RNNoise denoising, and helper logic for root audio capture and injection |
| HTTP API | A lightweight Java Socket HTTP service, Bearer Token authentication, and resumable chunked uploads |
| Scripts and tests | A Python upload client, plus Java / Python API and protocol tests |
| Build tools | Termux, Shell, OpenJDK, Clang, aapt, D8/R8, zipalign, and apksigner; Gradle is not currently used |

For build dependencies, external model requirements, and instructions, see the [build guide](BUILDING.en.md).

Audio processing settings: Denoising sends the cleaned signal through voice conversion, then mixes the original-minus-clean residual back in to preserve background sound. No silence gate is used. The independent volume limiter is disabled by default. It limits output RMS over approximately 20 ms windows to that of the corresponding original input, with an absolute peak no greater than 1. File conversion, batch processing, and quick recording share the file settings; real-time settings are fixed when processing starts. HTTP uses the strict boolean fields `denoise` and `volumeLimit`, both defaulting to false. Final whole-file peak normalization is skipped when either option is enabled. With both disabled, the previous peak normalization to 0.95 is retained.

Volume comparison uses the processing pipeline's decoded mono 16 kHz input, resampled and aligned with the 44.1 kHz output. Residual mixing is not true vocal/accompaniment separation: it may retain some of the original voice, as well as noise removed by denoising. The limiter uses fixed, aligned 20 ms RMS windows; it does not guarantee a sample-by-sample or arbitrary sliding-window decibel limit.
