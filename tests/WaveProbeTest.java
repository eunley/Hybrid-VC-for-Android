package com.svc.voice;

/** Verifies the producer seam: sink start is absolute, cursors stay unsigned, no fake play progress. */
public final class WaveProbeTest {
    private static int checks, fails;
    private static void eq(long a, long b, String w) {
        checks++;
        if (a != b) { fails++; System.out.println("FAIL " + w + " expected=" + b + " actual=" + a); }
    }
    private static void check(boolean ok, String w) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + w); }
    }

    public static void main(String[] a) {
        WaveStore s = new WaveStore();
        WaveProbe.attach(s);
        WaveProbe.reset();

        // input: 10 blocks of 320 samples advance the absolute 16k coordinate
        float[] blk = new float[320];
        for (int i = 0; i < blk.length; i++) blk[i] = 0.4f;
        for (int i = 0; i < 10; i++) { WaveProbe.inputStart(i * 320L); WaveProbe.inputBlock(blk, 320); }
        eq(WaveProbe.counters()[0], 3200, "input coordinate");

        // sink delivers a 44.1k block whose absolute start is later than the input origin
        short[] pcm = new short[882];
        java.util.Arrays.fill(pcm, (short) 12000);
        WaveProbe.outputCommitted(pcm, 44100L);          // absolute 1.000 s
        eq(WaveProbe.counters()[1], 44100L + 882, "output absolute end");
        eq(s.outputHeadCell(), 50, "output cell index follows the absolute start");

        // AudioTrack head: unsigned, survives the sign flip
        WaveProbe.played(WaveStore.unsigned32(-2));
        eq(WaveProbe.counters()[2], 4294967294L, "played cursor unsigned");
        eq(WaveProbe.counters()[4], 1, "playback marked real");
        WaveProbe.played(-1);
        eq(WaveProbe.counters()[4], 0, "stopped track not reported as playing");

        // virtual mic path: write cursor only, never claimed as playback
        long before = WaveProbe.counters()[3];
        WaveProbe.injected(3840);
        eq(WaveProbe.counters()[3], before + 3840, "vmic write cursor accumulates");
        check(WaveProbe.counters()[4] == 0, "vmic writes do not fabricate a playback cursor");

        String text = WaveProbe.cursorText();
        check(text.contains("play ?"), "unknown playback while only writing: " + text);
        check(!text.contains("vmic write"), "writes never replace a playback report: " + text);
        eq(WaveProbe.playbackPosition16(), -1, "hide cursor without playback");

        WaveProbe.virtualMic(true);
        WaveProbe.vmicPlayed(-1);
        WaveProbe.injected(96000);
        eq(WaveProbe.playbackPosition16(), -1, "injection writes do not render a cursor");
        WaveProbe.vmicPlayed(24000);
        eq(WaveProbe.playbackPosition16(), 8000, "actual helper head maps 48k to 16k");
        check(WaveProbe.cursorText().contains("system AudioTrack play 0.50s"), "accurate system label");
        WaveProbe.injected(192000);
        eq(WaveProbe.playbackPosition16(), 8000, "stalled playback not advanced by more writes");
        WaveProbe.vmicPlayed(-1);
        eq(WaveProbe.playbackPosition16(), -1, "disconnect hides cursor");
        check(WaveProbe.cursorText().contains("play ?"), "disconnect labels unknown");
        WaveProbe.vmicPlayed(48000);
        WaveProbe.reset();
        WaveProbe.virtualMic(true);
        eq(WaveProbe.playbackPosition16(), -1, "restart drops old progress");
        WaveProbe.played(44100);
        eq(WaveProbe.playbackPosition16(), -1, "injection never falls back to local empty track");
        WaveProbe.virtualMic(false);
        eq(WaveProbe.playbackPosition16(), 16000, "ordinary AudioTrack actual head preserved");

        // detach: writes stop touching the store but the coordinate keeps advancing
        WaveProbe.detach(s);
        long cellsBefore = s.inputHeadCell();
        WaveProbe.inputBlock(blk, 320);
        WaveProbe.skipInput(320);
        eq(s.inputHeadCell(), cellsBefore, "detached store receives no new cells");

        System.out.println("WaveProbeTest checks=" + checks + " fails=" + fails);
        if (fails != 0) System.exit(1);
    }
}
