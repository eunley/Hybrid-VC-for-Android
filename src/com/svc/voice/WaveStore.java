package com.svc.voice;

/**
 * Bounded ring of 20 ms peak/RMS cells on a single absolute timeline.
 *
 * <p>The timeline unit is the absolute 16 kHz input sample. Converted output is measured in
 * absolute 44.1 kHz samples and mapped onto the same axis with {@link #toInput16(long)}, so the
 * two traces share one time axis instead of each being left-aligned at the window edge.
 *
 * <p>Pure Java (no android imports) so the timeline can be verified by a plain JVM unit test.
 */
public final class WaveStore {

    /** Cell duration: 20 ms. */
    public static final int RATE_IN = 16000;
    public static final int RATE_OUT = 44100;
    public static final int CELL_MS = 20;
    public static final int IN_PER_CELL = RATE_IN * CELL_MS / 1000;    // 320
    public static final int OUT_PER_CELL = RATE_OUT * CELL_MS / 1000;  // 882
    /** Cells retained per channel (3 s). */
    public static final int CAPACITY = 150;

    /* ---- coordinate helpers (pure) ---- */
    public static long toInput16(long sample44) {
        return Math.round(sample44 * (double) RATE_IN / RATE_OUT);
    }

    public static long toOutput44(long sample16) {
        return Math.round(sample16 * (double) RATE_OUT / RATE_IN);
    }

    /**
     * Unsigned 32-bit playback head. AudioTrack counters are unsigned; a signed read turns
     * 0x80000000 into a negative number and would make the cursor jump backwards.
     */
    public static long unsigned32(int raw) {
        return raw & 0xFFFFFFFFL;
    }

    /** Delta between two unsigned sample counters of a channel whose ring is <= 2^31 samples. */
    public static long unsignedDelta(long now, long base) {
        return (now - base) & 0xFFFFFFFFL;
    }

    /** One trace: parallel peak and rms cells addressed by absolute cell index. */
    static final class Trace {
        final long[] peak = new long[CAPACITY];
        final float[] rms = new float[CAPACITY];
        boolean hasHead;
        long headCell;

        void clear() {
            hasHead = false;
            headCell = 0;
            java.util.Arrays.fill(peak, 0L);
            java.util.Arrays.fill(rms, 0f);
        }

        void put(long index, long pk, float r) {
            int slot = (int) (index % CAPACITY);
            peak[slot] = pk;
            rms[slot] = r;
            if (!hasHead || index > headCell) {
                hasHead = true;
                headCell = index;
            }
        }

        boolean get(long index, long[] outPeak, float[] outRms) {
            if (!hasHead || index > headCell || index <= headCell - CAPACITY) return false;
            int slot = (int) (index % CAPACITY);
            outPeak[0] = peak[slot];
            outRms[0] = rms[slot];
            return true;
        }
    }

    private final Trace input = new Trace();
    private final Trace output = new Trace();

    /* Cell accumulators, guarded by this. index() = next cell that will be stored. */
    private long inTotal;
    private int inFill;
    private long inPeak, inSumSq;
    private long outTotal;
    private int outFill;
    private long outPeak, outSumSq;

    private boolean publishing = true;
    private long revision;

    public synchronized void clear() {
        input.clear();
        output.clear();
        inTotal = outTotal = 0;
        inFill = outFill = 0;
        inPeak = outPeak = 0;
        inSumSq = outSumSq = 0;
        publishing = true;
        revision++;
    }

    /** Display gate: while false, samples are still stored so the timeline stays continuous. */
    public synchronized void setPublishing(boolean on) {
        if (publishing == on) return;
        publishing = on;
        revision++;
    }

    public synchronized boolean publishing() { return publishing; }

    public synchronized long revision() { return revision; }

    /** Store 16 kHz float PCM starting at the current absolute input position. */
    public synchronized void pushInput(float[] x, int off, int n) {
        for (int i = 0; i < n; i++) {
            long a = Math.abs(Math.round(x[off + i] * 32767f));
            if (a > 32767L) a = 32767L;
            inPeak = Math.max(inPeak, a);
            inSumSq += a * a;
            inTotal++;
            if (++inFill >= IN_PER_CELL) flushInputCell();
        }
        revision++;
    }

    /**
     * Advance the input coordinate by n samples without energy.
     *
     * <p>When the strip is closed no cells are kept, so the coordinate is snapped to the next cell
     * boundary: reopening then shows a true gap instead of tiles that silently packed themselves
     * against the window edge on a compressed axis.
     */
    public synchronized void skipInput(int n) {
        if (n <= 0) return;
        inTotal += n;
        long rem = inTotal % IN_PER_CELL;
        if (rem != 0) inTotal += IN_PER_CELL - rem;
        inFill = 0;
        inPeak = 0;
        inSumSq = 0;
        revision++;
    }

    /** Store 44.1 kHz int16 PCM occupying absolute output range [start44, start44 + n). */
    public synchronized void pushOutput(short[] x, int off, int n, long start44) {
        if (n <= 0) return;
        if (start44 < outTotal) return;                    // re-emitted overlap already stored
        if (start44 > outTotal) {
            // Materialize a gap as silence cells on the same axis (bounded).
            long gap = Math.min(start44 - outTotal, (long) RATE_OUT * 5);
            for (long i = 0; i < gap; i++) {
                outTotal++;
                if (++outFill >= OUT_PER_CELL) flushOutputCell();
            }
            if (start44 > outTotal) { outTotal = start44; outFill = 0; outPeak = 0; outSumSq = 0; }
        }
        for (int i = 0; i < n; i++) {
            long a = Math.abs((long) x[off + i]);
            outPeak = Math.max(outPeak, a);
            outSumSq += a * a;
            outTotal++;
            if (++outFill >= OUT_PER_CELL) flushOutputCell();
        }
        revision++;
    }

    private void flushInputCell() {
        float rms = (float) Math.sqrt(inSumSq / (double) (IN_PER_CELL * 32767.0 * 32767.0));
        input.put(inTotal / IN_PER_CELL - 1, inPeak, Math.min(1f, rms));
        inPeak = 0;
        inSumSq = 0;
        inFill = 0;
    }

    private void flushOutputCell() {
        float rms = (float) Math.sqrt(outSumSq / (double) (OUT_PER_CELL * 32767.0 * 32767.0));
        output.put(outTotal / OUT_PER_CELL - 1, outPeak, Math.min(1f, rms));
        outPeak = 0;
        outSumSq = 0;
        outFill = 0;
    }

    /* ---- read side ---- */

    public synchronized long inputEnd16() { return inTotal; }

    public synchronized long outputEnd44() { return outTotal; }

    public static final int SOURCE_INPUT = 0;
    public static final int SOURCE_OUTPUT = 1;
    public static final int SOURCE_NONE = 2;

    /** Fills per-cell peak (0..1) and rms (0..1) for one channel; false when it has no data there. */
    public synchronized boolean cells(int which, long firstCell, int count,
                                     float[] outPeak, float[] outRms) {
        if (count <= 0) return false;
        Trace t = which == SOURCE_OUTPUT ? output : input;
        if (!t.hasHead) return false;
        long[] pk = new long[1];
        float[] rm = new float[1];
        for (int i = 0; i < count; i++) {
            long idx = firstCell + i;
            if (idx >= 0 && t.get(idx, pk, rm)) {
                outPeak[i] = (float) (pk[0] / 32767.0);
                outRms[i] = rm[0];
            } else {
                outPeak[i] = 0;
                outRms[i] = 0;
            }
        }
        return true;
    }

    public synchronized long inputHeadCell() { return input.hasHead ? input.headCell : -1; }

    public synchronized long outputHeadCell() { return output.hasHead ? output.headCell : -1; }

    public synchronized long newestCell() {
        long a = input.headCell, b = output.headCell;
        if (!input.hasHead) return output.hasHead ? b : Long.MIN_VALUE;
        if (!output.hasHead) return a;
        return Math.max(a, b);
    }

    /**
     * Head-aligned viewport: the newer trace stays flush right, so an older trace keeps its own
     * leading boundary instead of being stretched to the left edge.
     *
     * @return {firstCell, inputHeadCell, outputHeadCell, newestCell}
     */
    public synchronized long[] viewport(int spanCells) {
        long newest = newestCell();
        if (newest == Long.MIN_VALUE) return new long[] {0, -1, -1, -1};
        long first = newest + 1 - Math.max(1, spanCells);
        return new long[] {first, input.hasHead ? input.headCell : -1,
                output.hasHead ? output.headCell : -1, newest};
    }
}
