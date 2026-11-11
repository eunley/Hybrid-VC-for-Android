package com.svc.voice;

/**
 * 等功率交叉淡入淡出（equal-power crossfade）。
 *
 * 拼接两段音频时，交叠区用 cos/sin 曲线：
 *   前段增益 = cos(θ)，后段增益 = sin(θ)，θ 从 0 到 π/2
 * 因为 cos²+sin²=1，两路功率之和恒为 1，响度不会在接缝处塌陷。
 *
 * （线性 1-t / t 会让中间点功率降到 0.5，听起来有个"凹坑"。）
 */
public final class XFade {

    private XFade() {}

    /**
     * 对一个 float[] 缓冲区执行等功率交叉并输出。
     *
     * @param out       目标缓冲（已有内容视为"已写好的前一段"）
     * @param outPos    out 里接下来要写的位置
     * @param in        本片新数据
     * @param overlap   交叠采样数（<=0 时直接追加）
     * @return out 的新写入位置
     */
    public static int blend(float[] out, int outPos, float[] in, int overlap) {
        if (overlap <= 0 || outPos < overlap) {
            System.arraycopy(in, 0, out, outPos, in.length);
            return outPos + in.length;
        }
        int back = outPos - overlap;           // 交叠区在 out 里的起点
        for (int i = 0; i < overlap; i++) {
            double th = (Math.PI / 2.0) * (i + 0.5) / overlap;
            float a = (float) Math.cos(th);    // 前一段淡出
            float b = (float) Math.sin(th);    // 后一段淡入
            out[back + i] = out[back + i] * a + in[i] * b;
        }
        int rest = in.length - overlap;
        if (rest > 0) System.arraycopy(in, overlap, out, outPos, rest);
        return outPos + rest;
    }

    /**
     * short[] 版（16bit PCM 用）。
     */
    public static int blend(short[] out, int outPos, short[] in, int overlap) {
        if (overlap <= 0 || outPos < overlap) {
            System.arraycopy(in, 0, out, outPos, in.length);
            return outPos + in.length;
        }
        int back = outPos - overlap;
        for (int i = 0; i < overlap; i++) {
            double th = (Math.PI / 2.0) * (i + 0.5) / overlap;
            float a = (float) Math.cos(th);
            float b = (float) Math.sin(th);
            out[back + i] = (short) (out[back + i] * a + in[i] * b);
        }
        int rest = in.length - overlap;
        if (rest > 0) System.arraycopy(in, overlap, out, outPos, rest);
        return outPos + rest;
    }

    /**
     * 原地对一段音频做"前段淡出 + 后段淡入"在同一个数组里（交错排布的流式场景）。
     * 用于把两个独立的等长片合成一片。
     *
     * @param prev 前一片尾部（长度 back）
     * @param next 后一片头部（长度 fwd）
     * @return 混合后的交叠区，长度 = min(back, fwd)
     */
    public static float[] mix(float[] prev, float[] next) {
        int n = Math.min(prev.length, next.length);
        float[] r = new float[n];
        for (int i = 0; i < n; i++) {
            double th = (Math.PI / 2.0) * (i + 0.5) / n;
            r[i] = (float) (prev[i] * Math.cos(th) + next[i] * Math.sin(th));
        }
        return r;
    }
}
