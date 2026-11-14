package com.svc.voice;

/**
 * Producer-side seam for the auxiliary waveform strip.
 *
 * <p>All positions are absolute sample counters on the shared 16 kHz axis:
 * <ul>
 *   <li>input: 16 kHz samples captured for conversion ({@link #inputWritten});</li>
 *   <li>output: 44.1 kHz samples committed by the conversion sink
 *       ({@code sink.audio(audio, start44)}), reported through {@link #outputCommitted};</li>
 *   <li>played: real consumption from {@code AudioTrack.getPlaybackHeadPosition()} (unsigned);</li>
 *   <li>injected: bytes handed to the virtual-mic sink only. This is a write cursor, never a
 *       claim that a receiving app read the audio.</li>
 * </ul>
 *
 * <p>Nothing here is allocated or scheduled when no window and no service is attached beyond a
 * handful of counters, so leaving the switch off costs a few volatile reads per block.
 */
public final class WaveProbe {

    private WaveProbe() { }

    /** Set by the auxiliary service while it exists. */
    private static volatile WaveStore store;

    static void attach(WaveStore s) { store = s; }
    static void detach(WaveStore s) { if (store == s) store = null; }
    /** True while a strip window exists (independent of whether it is redrawing). */
    public static boolean running() { return store != null; }

    /** True while the live conversion service is actually running. */
    public static boolean recording() { return RealtimeService.running; }

    /* ---- counters (volatile so the UI can read them without the store lock) ---- */
    private static volatile long inputEnd16;
    private static volatile long outputEnd44;
    private static volatile long played44;
    private static volatile long injected48;
    private static volatile boolean playbackReal;

    public static void reset() {
        inputEnd16 = 0;
        outputEnd44 = 0;
        played44 = 0;
        injected48 = 0;
        playbackReal = false;
        WaveStore s = store;
        if (s != null) s.clear();
    }

    /** 16 kHz input time whose values are about to be written; called once per mic block. */
    static void inputStart(long start16) {
        inputEnd16 = start16;
    }

    /** Stores the 16 kHz block that starts at {@link #inputStart}'s position. */
    static void inputBlock(float[] x, int n) {
        WaveStore s = store;
        // 开关关闭时 store 为 null：只推进坐标，不分配也不存图形。
        // 窗口存在但暂时不刷新（publishing=false）时仍然写入，保证重新显示时时间轴没有洞。
        if (s != null) s.pushInput(x, 0, n);
        inputEnd16 += n;
    }

    /** Advances the input coordinate without a stored block (strip closed). */
    static void skipInput(int n) {
        if (n > 0) inputEnd16 += n;
    }

    /** The converted sink committed [start44, start44+len). */
    static void outputCommitted(short[] pcm, long start44) {
        if (start44 + pcm.length > outputEnd44) outputEnd44 = start44 + pcm.length;
        WaveStore s = store;
        if (s != null) s.pushOutput(pcm, 0, pcm.length, start44);
    }

    /**
     * Real playback head, already unsigned. Pass -1 when the track is not playing.
     * Counters restart at 0 for every AudioTrack, so the screen reader keeps its own base.
     */
    static void played(long head44) {
        if (head44 >= 0) {
            played44 = head44;
            playbackReal = true;
        } else {
            playbackReal = false;
        }
    }

    /** Bytes accepted by the virtual-mic writer. Write cursor only. */
    static void injected(int bytes) {
        injected48 += bytes & ~1L;
    }

    /** Status line: conversion cursor + playback cursor + RTF. */
    static String cursorText() {
        StringBuilder b = new StringBuilder();
        b.append("in ").append(fmtMs(inputEnd16, WaveStore.RATE_IN));
        b.append(" · out ").append(fmtMs(outputEnd44, WaveStore.RATE_OUT));
        if (playbackReal) b.append(" · play ").append(fmtMs(played44, WaveStore.RATE_OUT));
        else if (injected48 > 0) {
            // Explicitly a write cursor: the helper does not report what the receiving app drained.
            b.append(" · vmic write ")
             .append(String.format(java.util.Locale.ROOT, "%.2fs", injected48 / 48000.0));
        }
        double rtf = LiveWindowState.snapshot()[7];
        b.append(" · RTF ").append(Double.isNaN(rtf) ? "--" : String.format(java.util.Locale.ROOT, "%.2f", rtf));
        return b.toString();
    }

    private static String fmtMs(long samples, int rate) {
        if (samples <= 0) return "0.0s";
        return String.format(java.util.Locale.ROOT, "%.2fs", samples / (double) rate);
    }

    /** Cursor values for tests / diagnostics. */
    public static long[] counters() {
        return new long[] {inputEnd16, outputEnd44, played44, injected48, playbackReal ? 1 : 0};
    }
}
