#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
work=$(mktemp -d "${TMPDIR:-/tmp}/auth-tests.XXXXXX")
trap 'rm -rf "$work"' EXIT
javac --release 8 -d "$work" src/com/svc/voice/LocalHttpWire.java
javac -cp "$work" -d "$work" tests/AuthTest.java
java -Xmx32m -cp "$work" com.svc.voice.AuthTest
python tests/auth-client-test.py
