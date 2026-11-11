# Hybrid VC for Android

Android voice conversion application with file conversion, realtime processing, quick recording and a local HTTP API. Built primarily in Java, with ONNX Runtime, Qualcomm QNN and RNNoise.

The maintained project introduction is the [Chinese README](README.md). See [build instructions](BUILDING.en.md), [API reference](assets/local-http-api.txt), [security notes](SECURITY.md) and [third-party inventory](THIRD_PARTY.md).

Original code is MIT; third-party components retain their own licenses. External inference models, ORT/QNN libraries and signing credentials are not included. RNNoise generated default model data is included and separately identified in the third-party inventory.

Denoise mode converts the cleaned signal then mixes back the original-minus-clean residual. An independent, default-off volumeLimit option limits fixed 20 ms RMS windows against the original decoded input and prevents clipping. This is not vocal/accompaniment separation.
