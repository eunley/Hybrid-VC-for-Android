package com.svc.voice;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;

/** mel128 / FFT / f0 后处理 / 线性插值（对齐 numpy、PyTorch） */
public final class Feats {
    public final int nFft, nMels, nBins, sr, hop;
    public final float[] win;
    public final float[] fb;

    private final float[] re, im;
    private final int[] rev;

    public Feats(String melBin) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(melBin));
        nFft = in.readInt();
        nMels = in.readInt();
        nBins = in.readInt();
        sr = in.readInt();
        hop = in.readInt();
        win = new float[nFft];
        fb = new float[nMels * nBins];
        for (int i = 0; i < nFft; i++) win[i] = in.readFloat();
        for (int i = 0; i < nMels * nBins; i++) fb[i] = in.readFloat();
        in.close();
        re = new float[nFft];
        im = new float[nFft];
        rev = new int[nFft];
        int bits = Integer.numberOfTrailingZeros(nFft);
        for (int i = 0; i < nFft; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    private void fft(float[] ar, float[] ai) {
        int n = nFft;
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = ar[i]; ar[i] = ar[j]; ar[j] = t;
                t = ai[i]; ai[i] = ai[j]; ai[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = ar[b] * cr - ai[b] * ci;
                    float xi = ar[b] * ci + ai[b] * cr;
                    ar[b] = ar[a] - xr;
                    ai[b] = ai[a] - xi;
                    ar[a] += xr;
                    ai[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    /** 返回 mel 谱，mel-major（长度 nMels*nFrames），nFramesOut[0]=帧数 */
    public float[] mel(float[] x, int[] nFramesOut) {
        int pad = nFft / 2;
        int n = x.length;
        float[] y = new float[n + 2 * pad];
        System.arraycopy(x, 0, y, pad, n);
        if (n > pad + 1) {
            for (int i = 0; i < pad; i++) {
                y[pad - 1 - i] = x[i + 1];
                y[n + pad + i] = x[n - 2 - i];
            }
        }
        int frames = 1 + (y.length - nFft) / hop;
        if (frames < 1) frames = 1;
        float[] out = new float[nMels * frames];
        float[] spec = new float[nBins];
        for (int f = 0; f < frames; f++) {
            int off = f * hop;
            for (int i = 0; i < nFft; i++) {
                int s = off + i;
                re[i] = (s < y.length ? y[s] : 0f) * win[i];
                im[i] = 0f;
            }
            fft(re, im);
            for (int k = 0; k < nBins; k++) spec[k] = re[k] * re[k] + im[k] * im[k];
            for (int m = 0; m < nMels; m++) {
                int base = m * nBins;
                float acc = 0f;
                for (int k = 0; k < nBins; k++) acc += spec[k] * fb[base + k];
                out[m * frames + f] = (float) Math.log10(Math.max(acc, 1e-10));
            }
        }
        nFramesOut[0] = frames;
        return out;
    }

    /** rmvpe 输出 [1,nbin,T] -> f0(Hz)[T]，conf<0.03 置 0 */
    public static float[] rmvpeF0(float[] sal, int nbin, int T) {
        float[] f0 = new float[T];
        float[] cents = new float[nbin];
        for (int i = 0; i < nbin; i++) cents[i] = i * 20f;
        for (int t = 0; t < T; t++) {
            int base = t * nbin;
            int best = 0;
            float bv = -1e30f;
            for (int k = 0; k < nbin; k++) {
                float v = sal[base + k];
                if (v > bv) { bv = v; best = k; }
            }
            int lo = Math.max(0, best - 4), hi = Math.min(nbin, best + 5);
            float sw = 0f, sc = 0f;
            for (int k = lo; k < hi; k++) {
                float w = sal[base + k];
                sw += w;
                sc += w * cents[k];
            }
            float c = sw > 0 ? sc / sw : cents[best];
            f0[t] = bv < 0.03f ? 0f : (float) (30.0 * Math.pow(2.0, c / 1200.0));
        }
        return f0;
    }

    /** 等价 np.interp(linspace(0,1,dst), linspace(0,1,srcLen), src) */
    public static float[] interpNorm(float[] src, int dst) {
        int n = src.length;
        float[] out = new float[dst];
        if (n == 1) {
            for (int i = 0; i < dst; i++) out[i] = src[0];
            return out;
        }
        for (int i = 0; i < dst; i++) {
            double p = dst == 1 ? 0 : (double) i / (dst - 1);
            double x = p * (n - 1);
            int i0 = (int) Math.floor(x);
            if (i0 < 0) i0 = 0;
            if (i0 > n - 2) i0 = n - 2;
            double fr = x - i0;
            out[i] = (float) (src[i0] * (1 - fr) + src[i0 + 1] * fr);
        }
        return out;
    }

    /** 等价 F.interpolate(c[ch][srcT] -> size=dstT, mode=linear, align_corners=False) */
    public static float[] interpChannels(float[] c, int ch, int srcT, int dstT) {
        float[] out = new float[ch * dstT];
        double scale = (double) srcT / dstT;
        for (int j = 0; j < dstT; j++) {
            double x = (j + 0.5) * scale - 0.5;
            if (x < 0) x = 0;
            int i0 = (int) Math.floor(x);
            if (i0 > srcT - 1) i0 = srcT - 1;
            int i1 = Math.min(i0 + 1, srcT - 1);
            float fr = (float) (x - i0);
            for (int k = 0; k < ch; k++) {
                out[k * dstT + j] = c[k * srcT + i0] * (1 - fr) + c[k * srcT + i1] * fr;
            }
        }
        return out;
    }
}