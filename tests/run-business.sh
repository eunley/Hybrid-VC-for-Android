#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
python tests/make-stubs.py
mkdir -p tests/business-classes
UPLOAD_SOURCE=
if [ -f src/com/svc/voice/UploadStore.java ]; then UPLOAD_SOURCE=src/com/svc/voice/UploadStore.java; fi
javac -cp "${JSON_JAR:?Set JSON_JAR to an org.json JVM jar}" -d tests/business-classes $(find tests/stubs -name '*.java') $UPLOAD_SOURCE src/com/svc/voice/Busy.java src/com/svc/voice/LocalHttpWire.java src/com/svc/voice/RoleStore.java src/com/svc/voice/LocalHttpApi.java tests/BusinessTest.java tests/ChunkHttpTest.java
java -cp "tests/business-classes:${JSON_JAR:?Set JSON_JAR to an org.json JVM jar}" BusinessTest
