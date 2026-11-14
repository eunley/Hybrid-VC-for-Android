package com.svc.voice;

/** Pure-JVM verification of the shared absolute timeline, 20 ms cells and unsigned cursors. */
public final class WaveTimelineTest {

    private static int checks, fails;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    private static void eq(long a, long b, String what) {
        check(a == b, what + " expected=" + b + " actual=" + a);
    }

    private static float[] tone(int n, float amp) {
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (amp * Math.sin(2 * Math.PI * 220 * i / 16000.0));
        return x;
    }

    public static void main(String[] a) {
        // ---- 1. coordinate mapping 16k <-> 44.1k ----
        eq(WaveStore.toOutput44(16000), 44100, "1s 16k -> 44.1k");
        eq(WaveStore.toInput16(44100), 16000, "1s 44.1k -> 16k");
        eq(WaveStore.toInput16(0), 0, "origin shared");

        // ---- 2. 20 ms aggregation: peak and RMS per cell ----
        WaveStore s = new WaveStore();
        float[] pk = new float[WaveStore.CAPACITY];
        float[] rm = new float[WaveStore.CAPACITY];
        s.pushInput(tone(WaveStore.IN_PER_CELL, 1f), 0, WaveStore.IN_PER_CELL);
        eq(s.inputHeadCell(), 0, "first 20ms cell index");
        s.cells(WaveStore.SOURCE_INPUT, 0, 1, pk, rm);
        check(pk[0] > 0.9f && pk[0] <= 1f, "peak of full-scale tone " + pk[0]);
        check(rm[0] > 0.6f && rm[0] < 0.8f, "sine rms ~0.707 " + rm[0]);
        s.pushInput(tone(WaveStore.IN_PER_CELL, 0.5f), 0, WaveStore.IN_PER_CELL);
        eq(s.inputHeadCell(), 1, "second cell index");
        s.cells(WaveStore.SOURCE_INPUT, 1, 1, pk, rm);
        check(pk[0] > 0.45f && pk[0] <= 0.52f, "half-scale peak " + pk[0]);
        check(rm[0] > 0.3f && rm[0] < 0.4f, "half-scale rms " + rm[0]);

        // ---- 3. one axis, no per-trace left alignment ----
        WaveStore t = new WaveStore();
        // input runs 1.0 s ahead of converted output
        for (int i = 0; i < 50; i++) t.pushInput(tone(WaveStore.IN_PER_CELL, 0.8f), 0, WaveStore.IN_PER_CELL);
        short[] out = new short[882];
        for (int i = 0; i < 882; i++) out[i] = (short) 8000;
        t.pushOutput(out, 0, out.length, 0);
        eq(t.inputHeadCell(), 49, "input head cell");
        eq(t.outputHeadCell(), 0, "output head cell");
        long[] vp = t.viewport(100);
        eq(vp[3], 49, "viewport newest = input");
        eq(vp[0], 49 + 1 - 100, "viewport first");
        eq(vp[1], 49, "input head inside window");
        eq(vp[2], 0, "output head keeps its own boundary");
        check(vp[2] < vp[1], "older trace is not moved to the right edge");
        // both traces are drawn on the same first cell -> the output occupies only its own cells
        check(t.cells(WaveStore.SOURCE_OUTPUT, vp[0], 10, pk, rm), "output cells readable");
        check(pk[0] == 0 && pk[1] == 0, "output absent before its boundary");
        check(t.cells(WaveStore.SOURCE_OUTPUT, 0, 1, pk, rm) && Math.abs(pk[0] - 0.2441f) < 0.001f, "output present at its own boundary " + pk[0]);

        // ---- 4. unsigned 32-bit playback head ----
        eq(WaveStore.unsigned32(-1), 0xFFFFFFFFL, "unsigned -1");
        eq(WaveStore.unsigned32(Integer.MIN_VALUE), 0x80000000L, "unsigned MIN_VALUE");
        eq(WaveStore.unsignedDelta(WaveStore.unsigned32(Integer.MIN_VALUE + 100), WaveStore.unsigned32(Integer.MIN_VALUE)),
                100, "delta across sign flip");
        eq(WaveStore.unsignedDelta(3L, 0xFFFFFFFDL), 6, "wrap forward delta");
        check(WaveStore.unsigned32(-100) > WaveStore.unsigned32(100), "unsigned ordering after sign flip");

        // ---- 5. publishing gate keeps coordinates continuous ----
        WaveStore g = new WaveStore();
        g.setPublishing(false);
        g.pushInput(tone(WaveStore.IN_PER_CELL, 0.5f), 0, WaveStore.IN_PER_CELL);
        g.pushOutput(out, 0, out.length, 0);
        eq(g.inputEnd16(), WaveStore.IN_PER_CELL, "coordinate advances while switch is off");
        eq(g.outputEnd44(), 882, "output coordinate advances while switch is off");
        check(g.cells(WaveStore.SOURCE_INPUT, 0, 1, pk, rm), "cells kept while not displayed");
        check(pk[0] > 0.4f, "silent gap does not erase the trace " + pk[0]);
        g.setPublishing(true);
        long rev = g.revision();
        g.pushInput(tone(WaveStore.IN_PER_CELL, 0.5f), 0, WaveStore.IN_PER_CELL);
        check(g.revision() != rev, "revision changes after switch on");
        eq(g.inputHeadCell(), 1, "cells stored after switch on at the true absolute index");

        // ---- 6. bounded ring ----
        WaveStore b = new WaveStore();
        int total = (WaveStore.CAPACITY + 40) * WaveStore.IN_PER_CELL;
        b.pushInput(tone(total, 0.3f), 0, total);
        eq(b.inputHeadCell(), WaveStore.CAPACITY + 39, "ring head tracks absolute index");
        check(b.cells(WaveStore.SOURCE_INPUT, 0, 1, pk, rm) && pk[0] == 0, "oldest cells evicted to 0");
        check(b.cells(WaveStore.SOURCE_INPUT, WaveStore.CAPACITY + 39, 1, pk, rm), "newest cells kept");

        // ---- 6b. coordinate-only advance keeps the axis honest (no packing) ----
        WaveStore sk = new WaveStore();
        sk.skipInput(WaveStore.IN_PER_CELL * 10 + 37);
        eq(sk.inputEnd16(), WaveStore.IN_PER_CELL * 11, "skip snaps to the next cell boundary");
        sk.pushInput(tone(WaveStore.IN_PER_CELL, 0.9f), 0, WaveStore.IN_PER_CELL);
        eq(sk.inputHeadCell(), 11, "first stored cell keeps its true absolute index");
        long[] vsk = sk.viewport(20);
        eq(vsk[0], 12 - 20, "viewport keeps the gap before the trace");

        // ---- 7. output gap is materialized as silence, overlap is ignored ----
        WaveStore gp = new WaveStore();
        gp.pushOutput(out, 0, 882, 0);
        eq(gp.outputHeadCell(), 0, "first output cell");
        gp.pushOutput(out, 0, 882, 882L * 3);
        eq(gp.outputHeadCell(), 3, "gap advanced on the same axis");
        eq(gp.outputEnd44(), 882L * 4, "gap fills absolute range");
        long before = gp.outputEnd44();
        gp.pushOutput(out, 0, 882, 0);
        eq(gp.outputEnd44(), before, "re-emitted overlap ignored");

        System.out.println("WaveTimelineTest checks=" + checks + " fails=" + fails);
        if (fails != 0) System.exit(1);
    }
}
