#!/data/data/com.termux/files/usr/bin/bash
# JVM-only protocol/counter/producer tests; no Android runtime or APK build.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${ANDROID_JAR:=$repo/libs/android-34/android.jar}"
: "${PLAYBACK_TEST_OUT:=${TMPDIR:-/data/user/0/com.termux/files/home/tmp}/svc-playback-test-classes}"
mkdir -p "$PLAYBACK_TEST_OUT/stubs/com/svc/voice" "$PLAYBACK_TEST_OUT/classes"
cat > "$PLAYBACK_TEST_OUT/stubs/com/svc/voice/RealtimeService.java" <<'STUB'
package com.svc.voice;
public final class RealtimeService { public static volatile boolean running; }
STUB
cat > "$PLAYBACK_TEST_OUT/stubs/com/svc/voice/LiveWindowState.java" <<'STUB'
package com.svc.voice;
public final class LiveWindowState {
    public static double[] snapshot() { return new double[] {0,0,0,0,0,0,0,Double.NaN,0}; }
}
STUB
javac -J-Xmx256m --release 8 -cp "$ANDROID_JAR" -d "$PLAYBACK_TEST_OUT/classes" \
    "$repo/hsrc/VmicPolicy.java" "$repo/src/com/svc/voice/VmicProgressWire.java" \
    "$repo/src/com/svc/voice/WaveStore.java" "$repo/src/com/svc/voice/WaveProbe.java" \
    "$PLAYBACK_TEST_OUT/stubs/com/svc/voice/RealtimeService.java" \
    "$PLAYBACK_TEST_OUT/stubs/com/svc/voice/LiveWindowState.java" \
    "$repo/tests/PlaybackProgressTest.java" "$repo/tests/WaveProbeTest.java" \
    "$repo/tests/WaveTimelineTest.java"
java -Xmx64m -cp "$PLAYBACK_TEST_OUT/classes:$ANDROID_JAR" PlaybackProgressTest
java -Xmx64m -cp "$PLAYBACK_TEST_OUT/classes" com.svc.voice.WaveProbeTest
java -Xmx64m -cp "$PLAYBACK_TEST_OUT/classes" com.svc.voice.WaveTimelineTest
