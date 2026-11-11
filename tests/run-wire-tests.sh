#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
mkdir -p tests/classes
javac --release 8 -Xlint:-options -d tests/classes src/com/svc/voice/LocalHttpWire.java
javac -cp tests/classes -d tests/classes tests/WireTest.java tests/WireDeadlineTest.java
java -Xmx32m -cp tests/classes com.svc.voice.WireTest
java -Xmx32m -cp tests/classes com.svc.voice.WireDeadlineTest
