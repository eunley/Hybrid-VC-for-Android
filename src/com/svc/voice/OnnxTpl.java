package com.svc.voice;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * ONNX 模型模板：读取 initializer 权重、替换、重新生成 external data。
 * 只处理 float32，足够覆盖 so-vits-svc 的推理图。
 */
public final class OnnxTpl {

    private final List<PB.Field> model;
    private final List<PB.Field> graph;
    private final List<PB.Field> initList;
    private final LinkedHashMap<String, PB.Field> byName = new LinkedHashMap<>();
    private final LinkedHashMap<String, float[]> overrides = new LinkedHashMap<>();
    private final LinkedHashMap<String, long[]> dimOverrides = new LinkedHashMap<>();
    private final byte[] oldData;      // 原 .data 全部内容
    private boolean saved = false;     // save() 会原地改写内部结构，之后禁止读取

    /**
     * 小于此字节数的张量一律【内联】写 raw_data，不放 external。
     * 原因：ORT 在形状推断阶段读不到 external 数据，
     * 会报 "Cannot parse data from external tensors"（Resize/Pad 等节点的常量就会踩到）。
     * 原版导出工具也是这么做的：大权重 external，小常量内联。
     */
    public static int INLINE_LIMIT = 4096;

    private OnnxTpl(List<PB.Field> model, byte[] oldData) throws IOException {
        this.model = model;
        this.oldData = oldData;
        PB.Field g = PB.find(model, 7);
        if (g == null) throw new IOException("不是合法的 ONNX ModelProto（无 graph）");
        this.graph = PB.parse(g.bytes);
        this.initList = PB.findAll(graph, 5);
        for (PB.Field it : initList) {
            List<PB.Field> tp = PB.parse(it.bytes);
            String nm = PB.asString(PB.find(tp, 8));
            if (nm != null) byName.put(nm, it);
        }
    }

    /** @param onnxFile 模板 .onnx；dataFile 可为 null（权重已内联时） */
    public static OnnxTpl load(File onnxFile, File dataFile) throws IOException {
        byte[] mb = Files.readAllBytes(onnxFile.toPath());
        byte[] db = (dataFile != null && dataFile.exists()) ? Files.readAllBytes(dataFile.toPath()) : new byte[0];
        return new OnnxTpl(PB.parse(mb), db);
    }

    public Set<String> initializerNames() { return byName.keySet(); }
    public boolean has(String name) { return byName.containsKey(name); }

    private List<PB.Field> tp(String name) throws IOException {
        if (saved) throw new IOException(
            "save() 之后不能再读：内部 TensorProto 已被改写为新 offset，"
          + "但 oldData 仍是旧文件内容。需要读取请重新 load()。");
        PB.Field it = byName.get(name);
        if (it == null) throw new IOException("模板里没有 initializer: " + name);
        return PB.parse(it.bytes);
    }

    public long[] dims(String name) throws IOException {
        long[] d = dimOverrides.get(name);
        return d != null ? d : PB.getDims(tp(name));
    }

    /** 读出 fp32 数值 */
    public float[] getFloats(String name) throws IOException {
        float[] ov = overrides.get(name);
        if (ov != null) return ov;
        List<PB.Field> t = tp(name);
        PB.Field dt = PB.find(t, 2);
        int dtype = dt == null ? 1 : (int) dt.varint;
        if (dtype != 1) throw new IOException(name + " 不是 float32 (dtype=" + dtype + ")");
        long[] dims = PB.getDims(t);
        long n = 1;
        for (long v : dims) n *= v;
        if (n == 0) return new float[0];

        byte[] raw = null;
        PB.Field rd = PB.find(t, 9);
        if (rd != null && rd.bytes != null && rd.bytes.length >= n * 4) {
            raw = rd.bytes;
        } else {
            long[] loc = externalRange(t);
            if (loc == null) throw new IOException(name + " 既无 raw_data 也无 external_data");
            long off = loc[0]; int len = (int) loc[1];
            if (off + len > oldData.length)
                throw new IOException(name + " 的 external 范围越界 (off=" + off + " len=" + len
                        + " dataFileSize=" + oldData.length + ")");
            raw = Arrays.copyOfRange(oldData, (int) off, (int) off + len);
        }
        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[(int) n];
        fb.get(out, 0, Math.min(out.length, fb.capacity()));
        return out;
    }

    /** 读 external_data 的 (offset, length)；data_location 必须为 1(EXTERNAL) 才算 */
    private long[] externalRange(List<PB.Field> t) throws IOException {
        PB.Field dl = PB.find(t, 14);
        if (dl == null || dl.varint != 1) return null;
        long off = -1, len = -1;
        for (PB.Field f : PB.findAll(t, 13)) {
            List<PB.Field> kv = PB.parse(f.bytes);
            String k = PB.asString(PB.find(kv, 1));
            String v = PB.asString(PB.find(kv, 2));
            if ("offset".equals(k)) off = Long.parseLong(v.trim());
            else if ("length".equals(k)) len = Long.parseLong(v.trim());
        }
        return (off >= 0 && len >= 0) ? new long[]{off, len} : null;
    }


    /**
     * ★ 整图 fp16 -> fp32（原地）。
     * 模板 tpl/dec.onnx 为省内存是 fp16 内联的，但导入出来的角色 dec 必须给 fp32：
     * QNN EP / AI Hub 拿到 fp16 图会 op validation 失败，报
     * "Conv with domain:com.ms.internal.nhwc ... was not selected by that EP"。
     * 必须一起改四处（漏一处就类型不匹配）：
     *   1) initializer 的 data_type + 数据
     *   2) node 里 Cast 的 attribute "to"
     *   3) graph.input / graph.output 的 elem_type
     *   4) 清空 value_info（残留静态形状会让 ORT 优化出错误常量）
     */
    public int convertToFp32() throws IOException {
        if (saved) throw new IOException("save() 之后不能再转：请重新 load()");
        int n = 0;
        for (PB.Field it : initList) {
            List<PB.Field> t = PB.parse(it.bytes);
            PB.Field dtF = PB.find(t, 2);
            int dtype = dtF == null ? 1 : (int) dtF.varint;
            if (dtype != 10) continue;
            byte[] raw = null;
            long[] loc = externalRange(t);
            if (loc != null) raw = Arrays.copyOfRange(oldData, (int) loc[0], (int) (loc[0] + loc[1]));
            else {
                PB.Field rd = PB.find(t, 9);
                if (rd != null) raw = rd.bytes;
            }
            if (raw == null) continue;
            int cnt = raw.length / 2;
            byte[] out = new byte[cnt * 4];
            ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < cnt; i++) {
                int h = (raw[i * 2] & 0xFF) | ((raw[i * 2 + 1] & 0xFF) << 8);
                bb.putFloat(Float.float16ToFloat((short) h));
            }
            PB.removeAll(t, 13);
            PB.removeAll(t, 14);
            PB.removeAll(t, 9);
            t.add(PB.ld(9, out));
            if (dtF == null) t.add(PB.varint(2, 1)); else dtF.varint = 1;
            it.bytes = PB.write(t);
            n++;
        }
        int ncast = 0;
        for (PB.Field nodeF : PB.findAll(graph, 1)) {
            List<PB.Field> nd = PB.parse(nodeF.bytes);
            boolean touched = false;
            for (PB.Field atF : PB.findAll(nd, 5)) {
                List<PB.Field> at = PB.parse(atF.bytes);
                PB.Field nm = PB.find(at, 1);
                PB.Field iv = PB.find(at, 3);
                if (nm != null && "to".equals(PB.asString(nm)) && iv != null && iv.varint == 10) {
                    iv.varint = 1;
                    atF.bytes = PB.write(at);
                    touched = true;
                    ncast++;
                }
            }
            if (touched) nodeF.bytes = PB.write(nd);
        }
        int nio = 0;
        for (int num : new int[]{11, 12}) {
            for (PB.Field vi : PB.findAll(graph, num)) {
                List<PB.Field> v = PB.parse(vi.bytes);
                PB.Field ty = PB.find(v, 2);
                if (ty == null) continue;
                List<PB.Field> tp = PB.parse(ty.bytes);
                PB.Field tt = PB.find(tp, 1);
                if (tt == null) continue;
                List<PB.Field> tensor = PB.parse(tt.bytes);
                PB.Field et = PB.find(tensor, 1);
                if (et != null && et.varint == 10) {
                    et.varint = 1;
                    tt.bytes = PB.write(tensor);
                    ty.bytes = PB.write(tp);
                    vi.bytes = PB.write(v);
                    nio++;
                }
            }
        }
        int nvi = PB.findAll(graph, 13).size();
        PB.removeAll(graph, 13);
        System.err.println("[convertToFp32] init=" + n + " cast=" + ncast + " io=" + nio + " vi=" + nvi);
        return n;
    }

    /** 登记替换（不立即写盘） */
    public void setFloats(String name, float[] data, long[] dims) throws IOException {
        if (!byName.containsKey(name)) throw new IOException("模板里没有 " + name);
        overrides.put(name, data);
        dimOverrides.put(name, dims);
    }

    /** fp32 → fp16 的原始字节（小端） */
    public static byte[] toFp16Bytes(float[] v) {
        byte[] out = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            int h = Float.floatToFloat16(v[i]);
            out[i*2]   = (byte) (h & 0xFF);
            out[i*2+1] = (byte) ((h >> 8) & 0xFF);
        }
        return out;
    }

    /** 把 float[] 按模板 dtype 编码成 payload */
    public static byte[] encode(float[] v, int dtype) {
        if (dtype == 10) return toFp16Bytes(v);       // float16
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(v);
        return bb.array();
    }

    /**
     * 写出：新 onnx + 新 data。
     * 所有 initializer 重新排布，被替换的用新数据，其余从原 data 原样搬运。
     * @return 新 data 的字节数
     */
    public long save(File outOnnx, File outData) throws IOException {
        // 1. 重建每个 TensorProto
        long offset = 0;
        File tmpData = new File(outData.getParentFile(), outData.getName() + ".tmp");
        if (tmpData.exists()) tmpData.delete();
        try (RandomAccessFile raf = new RandomAccessFile(tmpData, "rw")) {
            for (PB.Field it : initList) {
                List<PB.Field> t = PB.parse(it.bytes);
                String nm = PB.asString(PB.find(t, 8));
                float[] ov = nm == null ? null : overrides.get(nm);
                long[] dimOv = nm == null ? null : dimOverrides.get(nm);

                byte[] payload = null;   // null = 该张量不进外部数据文件，原样保留
                PB.Field dtF = PB.find(t, 2);
                int dtype = dtF == null ? 1 : (int) dtF.varint;
                boolean isFloat = (dtype == 1 || dtype == 10);
                if (ov == null && !isFloat) {
                    it.bytes = PB.write(t);
                    continue;
                }
                // 小张量保持内联（必须放在形状推断节点能读到的地方）
                {
                    long[] dd = dimOv != null ? dimOv : PB.getDims(t);
                    long cnt = 1;
                    for (long v : dd) cnt *= v;
                    int esz = (dtype == 10) ? 2 : 4;
                    if (ov == null && cnt * esz <= INLINE_LIMIT) {
                        it.bytes = PB.write(t);
                        continue;
                    }
                }
                if (ov != null) {
                    payload = encode(ov, dtype);   // 按模板 dtype 编码（fp16/fp32）
                    if (dimOv != null) PB.setDims(t, dimOv);
                    PB.removeAll(t, 9);            // 去掉可能的内联 raw_data
                } else {
                    long[] loc = externalRange(t);
                    if (loc != null) {
                        // 外部数据：搬运到新文件
                        payload = Arrays.copyOfRange(oldData, (int) loc[0], (int) (loc[0] + loc[1]));
                    } else {
                        // 内联数据
                        PB.Field rd = PB.find(t, 9);
                        if (rd != null && rd.bytes != null && rd.bytes.length > 0) {
                            payload = rd.bytes;
                            PB.removeAll(t, 9);
                        }
                        // 否则可能是 int64_data / int32_data 等标量常量——
                        // 这类张量【完全不动】，否则 ORT 会报
                        // "data field count (0) does not match expected count from shape"
                    }
                }

                if (payload == null) {
                    it.bytes = PB.write(t);
                    continue;                      // 内联常量，保持原样
                }

                raf.seek(offset);
                raf.write(payload);

                PB.removeAll(t, 13);
                String fn = outData.getName();
                t.add(PB.ld(13, stringEntry("location", fn)));
                t.add(PB.ld(13, stringEntry("offset", String.valueOf(offset))));
                t.add(PB.ld(13, stringEntry("length", String.valueOf(payload.length))));
                // ★ 必须把 data_location 置 1(EXTERNAL)，否则 ORT 仍去 raw_data 找数据，
                //   报 "data field count (0) does not match expected count from shape"
                PB.removeAll(t, 14);
                t.add(PB.varint(14, 1));

                it.bytes = PB.write(t);
                offset += payload.length;
            }
        }

        // 2. 重写 graph / model
        PB.Field g = PB.find(model, 7);
        g.bytes = PB.write(graph);
        byte[] mb = PB.write(model);
        Files.write(outOnnx.toPath(), mb);

        // 3. 替换 .data
        if (outData.exists()) outData.delete();
        if (!tmpData.renameTo(outData)) {
            Files.copy(tmpData.toPath(), outData.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tmpData.delete();
        }
        saved = true;
        return offset;
    }

    private static byte[] stringEntry(String k, String v) {
        List<PB.Field> kv = new ArrayList<>();
        kv.add(PB.ldString(1, k));
        kv.add(PB.ldString(2, v));
        return PB.write(kv);
    }

    /** weight_norm 合并：w = g * v / ||v||，按 output_channel 归一化 */
    public static float[] fuseWeightNorm(float[] g, float[] v, long[] vDims) {
        if (vDims.length < 2) return v;
        long outCh = vDims[0];
        long per = v.length / outCh;      // 每个输出通道的元素数
        float[] w = new float[v.length];
        for (long o = 0; o < outCh; o++) {
            double sq = 0;
            int base = (int) (o * per);
            for (int i = 0; i < per; i++) { double x = v[base + i]; sq += x * x; }
            double norm = Math.sqrt(sq);
            double gv = (g.length > o) ? g[(int) o] : 1.0;
            double scale = norm > 1e-12 ? gv / norm : 0.0;
            for (int i = 0; i < per; i++) w[base + i] = (float) (v[base + i] * scale);
        }
        return w;
    }

    /** 读 data_type：1=float32, 7=int64, 6=int32, 9=bool ... */
    public int dataType(String name) throws IOException {
        List<PB.Field> t = tp(name);
        PB.Field dt = PB.find(t, 2);
        return dt == null ? 1 : (int) dt.varint;
    }

    /** 读原始字节（诊断用） */
    public byte[] getRaw(String name) throws IOException {
        List<PB.Field> t = tp(name);
        long[] dims = PB.getDims(t);
        long n = 1; for (long v : dims) n *= v;
        PB.Field rd = PB.find(t, 9);
        if (rd != null && rd.bytes != null) return rd.bytes;
        long[] loc = externalRange(t);
        if (loc == null) return null;
        return Arrays.copyOfRange(oldData, (int) loc[0], (int) (loc[0] + loc[1]));
    }

}
