#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
work="${TEST_WORK:-$PWD/.build/test-work}"
python tests/make-stubs.py
mkdir -p tests/business-classes
javac -cp "${JSON_JAR:?Set JSON_JAR to an org.json JVM jar}" -d tests/business-classes $(find tests/stubs -name '*.java') src/com/svc/voice/Busy.java src/com/svc/voice/LocalHttpWire.java src/com/svc/voice/UploadStore.java tests/ChunkHttpTest.java
python tests/chunk-http.py "$@"
