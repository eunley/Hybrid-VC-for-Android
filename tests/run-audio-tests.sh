#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
work=$(mktemp -d "${TMPDIR:-/tmp}/svc-audio.XXXXXX")
trap 'rm -rf "$work"' EXIT
javac -d "$work" src/com/svc/voice/{DenoiseGate,RnNoise,AudioMix,ChunkPipeline}.java tests/audio/AudioCoreTest.java
java -Xmx64m -cp "$work" com.svc.voice.AudioCoreTest
