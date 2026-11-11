package com.svc.voice;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** 解码任意音频为 16k 单声道 float；写 44.1k WAV */
public final class AudioIO {

    /** 解码文件到指定采样率单声道 float */
    public static float[] decode(String path, int targetSr) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                fmt = f;
                break;
            }
        }
        if (track < 0) { ex.release(); throw new IOException("没有音频轨道"); }
        ex.selectTrack(track);
        int srcSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        String mime = fmt.getString(MediaFormat.KEY_MIME);

        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(fmt, null, null, 0);
        dec.start();

        List<float[]> parts = new ArrayList<>();
        long total = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;
        while (!eos) {
            int inIdx = dec.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
                ByteBuffer ib = dec.getInputBuffer(inIdx);
                int sz = ex.readSampleData(ib, 0);
                if (sz < 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eos = true;
                } else {
                    dec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                    ex.advance();
                }
            }
            int outIdx = dec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer ob = dec.getOutputBuffer(outIdx);
                if (ob != null && info.size > 0) {
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    short[] s = new short[info.size / 2];
                    ob.order(ByteOrder.nativeOrder()).asShortBuffer().get(s);
                    float[] f = new float[s.length / ch];
                    for (int i = 0, j = 0; i + ch <= s.length; i += ch, j++) {
                        int acc = 0;
                        for (int c = 0; c < ch; c++) acc += s[i + c];
                        f[j] = acc / (float) ch / 32768f;
                    }
                    parts.add(f);
                    total += f.length;
                }
                dec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = dec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    srcSr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
        }
        dec.stop(); dec.release(); ex.release();

        float[] all = new float[(int) total];
        int p = 0;
        for (float[] f : parts) { System.arraycopy(f, 0, all, p, f.length); p += f.length; }
        parts.clear();
        if (srcSr == targetSr) return all;
        return resample(all, srcSr, targetSr);
    }

    public static float[] resample(float[] in, int srcSr, int dstSr) {
        int n = (int) Math.round((double) in.length * dstSr / srcSr);
        float[] out = new float[n];
        double step = (double) srcSr / dstSr;
        for (int i = 0; i < n; i++) {
            double x = i * step;
            int i0 = (int) Math.floor(x);
            if (i0 >= in.length - 1) { out[i] = in[in.length - 1]; continue; }
            double fr = x - i0;
            out[i] = (float) (in[i0] * (1 - fr) + in[i0 + 1] * fr);
        }
        return out;
    }

    /** 流式写 WAV：8KB 一块，长音频也不会爆内存 */
    public static void writeWav(String path, float[] data, int sr) throws IOException {
        try (java.io.BufferedOutputStream fo = new java.io.BufferedOutputStream(
                new FileOutputStream(path), 1 << 16)) {
            writeWavHeader(fo, data.length, sr);
            byte[] blk = new byte[8192];
            int bp = 0;
            for (int i = 0; i < data.length; i++) {
                float v = Math.max(-1f, Math.min(1f, data[i]));
                short sv = (short) (v * 32767);
                blk[bp++] = (byte) (sv & 0xff);
                blk[bp++] = (byte) ((sv >> 8) & 0xff);
                if (bp >= blk.length) { fo.write(blk, 0, bp); bp = 0; }
            }
            if (bp > 0) fo.write(blk, 0, bp);
        }
    }

    private static void writeWavHeader(java.io.OutputStream fo, int n, int sr) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()); bb.putInt(36 + n * 2); bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes()); bb.putInt(16); bb.putShort((short) 1); bb.putShort((short) 1);
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort((short) 2); bb.putShort((short) 16);
        bb.put("data".getBytes()); bb.putInt(n * 2);
        fo.write(bb.array());
    }

    public static float[] pcm16ToFloat(byte[] buf, int len, int channels) {
        int n = len / 2;
        short[] s = new short[n];
        ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        float[] f = new float[n / channels];
        for (int i = 0, j = 0; i + channels <= n; i += channels, j++) {
            int acc = 0;
            for (int c = 0; c < channels; c++) acc += s[i + c];
            f[j] = acc / (float) channels / 32768f;
        }
        return f;
    }


    /** 流式 WAV 写入：内存恒定，close() 时回填头 */
    public static final class WavWriter implements java.io.Closeable {
        private final java.io.RandomAccessFile raf;
        private final int sr;
        private final byte[] blk = new byte[8192];
        private int bp = 0;
        private long n = 0;
        public double peak = 0;

        public WavWriter(String path, int sr) throws IOException {
            this.sr = sr;
            File f = new File(path);
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            raf = new java.io.RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(new byte[44]);
        }

        public void write(float[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                float v = b[i];
                double av = Math.abs(v);
                if (av > peak) peak = av;
                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                short sv = (short) (v * 32767);
                blk[bp++] = (byte) (sv & 0xff);
                blk[bp++] = (byte) ((sv >>> 8) & 0xff);
                n++;
                if (bp >= blk.length) { raf.write(blk, 0, bp); bp = 0; }
            }
        }

        /** 若峰值不合适，就地重写一遍（流式，不整段读入） */
        public void normalize(double target) throws IOException, java.io.EOFException {
            if (peak <= 0) return;
            if (bp > 0) { raf.write(blk, 0, bp); bp = 0; }
            if (bp > 0) { raf.write(blk, 0, bp); bp = 0; }
            double g = target / peak;
            if (g > 0.99 && g < 1.01) return;
            long bytes = n * 2;
            byte[] buf = new byte[1 << 16];
            long pos = 0;
            raf.seek(44);
            while (pos < bytes) {
                int want = (int) Math.min(buf.length, bytes - pos);
                raf.readFully(buf, 0, want);
                raf.seek(44 + pos);
                for (int i = 0; i < want; i += 2) {
                    int lo = buf[i] & 0xff, hi = buf[i + 1];
                    int sv = (short) ((hi << 8) | lo);
                    int nv = (int) Math.round(sv * g);
                    if (nv > 32767) nv = 32767; else if (nv < -32768) nv = -32768;
                    buf[i] = (byte) (nv & 0xff);
                    buf[i + 1] = (byte) ((nv >> 8) & 0xff);
                }
                raf.write(buf, 0, want);
                raf.seek(44 + pos + want);
                pos += want;
            }
            peak = target;
        }

        @Override
        public void close() throws IOException {
            if (bp > 0) { raf.write(blk, 0, bp); bp = 0; }
            raf.seek(0);
            ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            int len = (int) Math.min(Integer.MAX_VALUE - 36, n * 2);
            bb.put("RIFF".getBytes()); bb.putInt(36 + len); bb.put("WAVE".getBytes());
            bb.put("fmt ".getBytes()); bb.putInt(16); bb.putShort((short) 1); bb.putShort((short) 1);
            bb.putInt(sr); bb.putInt(sr * 2); bb.putShort((short) 2); bb.putShort((short) 16);
            bb.put("data".getBytes()); bb.putInt(len);
            raf.write(bb.array());
            raf.close();
        }
    }



}
