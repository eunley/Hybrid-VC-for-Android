package com.svc.voice;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * 把社区 so-vits-svc 4.x 角色（G_*.pth + config.json）转成本 App 用的 ONNX。
 *
 * 做法：不改图结构，只把模板里的权重换成角色权重。
 *   - 模板 initializer "net.xxx"  ← pth "xxx"
 *   - 模板 "d.xxx.weight"        ← pth "dec.xxx.weight_g/weight_v" 现场合并
 *   - 模板 "_src_*"（相对位置表） ← pth "enc_p.enc_.attn_layers.N.emb_rel_k/v"
 *   - 形状一律以 pth 为准 → gin_channels / n_speakers 差异自动适配
 */
public final class RoleImporter {

    private static final long ENTRY_LIMIT = 4L << 30, TOTAL_LIMIT = 8L << 30;
    private static final int CONFIG_LIMIT = 1 << 20, PICKLE_LIMIT = 64 << 20;
    // Budget includes retained overrides and transient storage/fusion arrays, not the template itself.
    private static final long ARRAY_BUDGET = Math.min(512L << 20,
            Runtime.getRuntime().maxMemory() / 3);

    private static void checkMemory(long bytes) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long available = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (bytes < 0 || bytes > ARRAY_BUDGET || bytes > available - (32L << 20))
            throw new IOException("角色权重超过安全内存预算: " + bytes);
    }

    public interface Log { void log(String s); }

    public static final class Result {
        public File encOnnx, encData, decOnnx, decData;
        public String roleName;
        public int tensorCount;
        public int missing;
        public long costMs;
    }

    /** 相对位置表映射（已验证 maxdiff=0） */
    private static final String[][] RELPOS = {
        {"_src_transpose_7",  "0", "emb_rel_k"},
        {"_src_transpose_17", "1", "emb_rel_k"},
        {"_src_transpose_27", "2", "emb_rel_k"},
        {"_src_transpose_37", "3", "emb_rel_k"},
        {"_src_transpose_47", "4", "emb_rel_k"},
        {"_src_transpose_57", "5", "emb_rel_k"},
        {"_src_unsqueeze_3",  "0", "emb_rel_v"},
        {"_src_unsqueeze_5",  "1", "emb_rel_v"},
        {"_src_unsqueeze_7",  "2", "emb_rel_v"},
        {"_src_unsqueeze_9",  "3", "emb_rel_v"},
        {"_src_unsqueeze_11", "4", "emb_rel_v"},
        {"_src_unsqueeze_13", "5", "emb_rel_v"},
    };

    /**
     * @param src      角色来源：可以是 .pth / .pt 文件、包含它们的目录、或 zip（社区常见的打包）
     * @param outDir   输出目录（会创建，里面放 enc.onnx / enc.onnx.data / dec.onnx / dec.onnx.data）
     * @param tplDir   模板目录（enc.onnx + enc.onnx.data + dec.onnx + dec.onnx.data）
     */
    public static Result importRole(File src, File outDir, File tplDir, Log log) throws IOException {
        long t0 = System.currentTimeMillis();
        Result r = new Result();

        File tmpUnzip = null;
        try {
        // ---------- 1. 定位 pth 与 config ----------
        File pth = null, cfg = null;
        if (src.isDirectory()) {
            for (File f : src.listFiles()) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".pth") || n.endsWith(".pt")) {
                    // 选生成器主模型：
                    //  · .pth 优先于 .pt（.pt 常是 slim/量化/导出中间产物）
                    //  · 同后缀取体积最大的（slim 版通常小很多）
                    if (pth == null || betterModel(f, pth)) pth = f;
                } else if (n.equals("config.json")) cfg = f;
            }
        } else {
            String n = src.getName().toLowerCase();
            if (n.endsWith(".pth") || n.endsWith(".pt")) {
                pth = src;
                File c2 = new File(src.getParentFile(), "config.json");
                if (c2.exists()) cfg = c2;
            } else {
                // 当作 zip 解压
                File parent = outDir.getAbsoluteFile().getParentFile();
                Files.createDirectories(parent.toPath());
                tmpUnzip = Files.createTempDirectory(parent.toPath(), ".unzip_").toFile();
                unzip(src, tmpUnzip, log);
                File[] found = findFiles(tmpUnzip);
                pth = found[0];
                cfg = found[1];
            }
        }
        if (pth == null) throw new IOException("没找到 .pth/.pt 模型文件");
        log.log("模型 " + pth.getName() + "  " + (pth.length() / 1048576) + " MB");

        // ---------- 2. 读 config ----------
        int gin = 256, ssl = 768, nspk = 200;
        if (cfg != null && cfg.exists()) {
            String js = new String(readLimited(new FileInputStream(cfg), CONFIG_LIMIT), Charset.forName("UTF-8"));
            gin  = intOf(js, "gin_channels", gin);
            ssl  = intOf(js, "ssl_dim", ssl);
            nspk = intOf(js, "n_speakers", nspk);
            int sr = intOf(js, "sampling_rate", 44100);
            int hop = intOf(js, "hop_length", 512);
            log.log("config: ssl_dim=" + ssl + " gin_channels=" + gin
                    + " n_speakers=" + nspk + " sr=" + sr + " hop=" + hop);
            if (sr != 44100 || hop != 512) {
                log.log("⚠ 警告：采样率/跳长与模板不同（" + sr + "/" + hop
                        + "），可能不是 so-vits-svc 4.x 44k 模型，结果可能不正确");
            }
            if (ssl != 768) {
                log.log("⚠ 警告：ssl_dim=" + ssl + "（模板为 768）。当前版本仅支持 768，"
                        + "请用 768 维 hubert 训练的角色");
            }
        } else {
            log.log("⚠ 没有 config.json，按默认值 ssl=768 gin=256 处理");
        }

        // ---------- 3. 读 pth ----------
        LinkedHashMap<String, PickleReader.TensorRef> tensors = readPth(pth, log);
        log.log("pth 张量 " + tensors.size() + " 个");

        // ---------- 4. 逐模板填充 ----------
        r.roleName = baseName(src);
        Files.createDirectories(outDir.toPath());

        r.encOnnx = new File(outDir, "enc.onnx");
        r.encData = new File(outDir, "enc.onnx.data");
        r.decOnnx = new File(outDir, "dec.onnx");
        r.decData = new File(outDir, "dec.onnx.data");

        int[] miss = new int[2];
        r.tensorCount = fillTemplate(
                new File(tplDir, "enc.onnx"), new File(tplDir, "enc.onnx.data"),
                r.encOnnx, r.encData, tensors, pth, true, log, miss);
        int decCnt = fillTemplate(
                new File(tplDir, "dec.onnx"), new File(tplDir, "dec.onnx.data"),
                r.decOnnx, r.decData, tensors, pth, false, log, miss);
        r.tensorCount += decCnt;
        r.missing = miss[0];


        r.costMs = System.currentTimeMillis() - t0;
        log.log("完成 " + r.tensorCount + " 个张量, 缺失 " + r.missing
                + ", 耗时 " + r.costMs + " ms");
        return r;
        } finally { if (tmpUnzip != null) deleteTree(tmpUnzip); }
    }

    /** @return 成功填充的数量 */
    private static int fillTemplate(File tplOnnx, File tplData, File outOnnx, File outData,
                                    LinkedHashMap<String, PickleReader.TensorRef> tensors,
                                    File pth, boolean isEnc, Log log, int[] miss) throws IOException {
        OnnxTpl tpl = OnnxTpl.load(tplOnnx, tplData);
        // ★ dec 一律输出 fp32：fp16 图交给 QNN EP / AI Hub 会 op validation 失败
        //   （报 Conv ... com.ms.internal.nhwc was not selected by that EP）
        if (!isEnc) tpl.convertToFp32();
        int ok = 0, skip = 0, notFound = 0;
        try (ZipFile z = new ZipFile(pth)) {
            String prefix = findDataPrefix(z, pth);
            Cache cache = new Cache(z, prefix);

            for (String name : new ArrayList<>(tpl.initializerNames())) {
                // 1) 位置表
                String relKey = relTarget(name);
                if (relKey != null) {
                    PickleReader.TensorRef tr = tensors.get(relKey);
                    if (tr == null) { notFound++; continue; }
                    float[] v = cache.load(tr);
                    tpl.setFloats(name, v, pickDims(tpl.dims(name), tr.shape, v.length));
                    ok++;
                    continue;
                }
                // 2) 常量（val_* / 形状计算）保持模板原值
                if (isShapeConstant(name)) { skip++; continue; }

                // 3) 普通权重
                String pthKey = toPthKey(name, isEnc);
                if (pthKey == null) { skip++; continue; }

                PickleReader.TensorRef tr = tensors.get(pthKey);
                if (tr != null) {
                    float[] v = cache.load(tr);
                    // ★ 形状以模板为准：模板可能把权重存成扁平一维（如 noise_convs.weight
                    //   在 pth 是 (256,1,128)、在 onnx 是 (32768,)）。只有元素数变了才改 dims。
                    tpl.setFloats(name, v, pickDims(tpl.dims(name), tr.shape, v.length));
                    ok++;
                    continue;
                }
                // 4) weight_norm 合并：模板 "d.X.weight" ← pth "dec.X.weight_g/weight_v"
                if (pthKey.endsWith(".weight")) {
                    String stem = pthKey.substring(0, pthKey.length() - ".weight".length());
                    PickleReader.TensorRef tg = tensors.get(stem + ".weight_g");
                    PickleReader.TensorRef tv = tensors.get(stem + ".weight_v");
                    if (tg != null && tv != null) {
                        float[] g = cache.load(tg);
                        float[] v = cache.load(tv);
                        if (tv.shape.length >= 2 && tv.shape[0] == 0)
                            throw new IOException("weight_norm 输出通道为零");
                        cache.reserveFusion(v.length);
                        float[] w = OnnxTpl.fuseWeightNorm(g, v, tv.shape);
                        tpl.setFloats(name, w, pickDims(tpl.dims(name), tv.shape, w.length));
                        ok++;
                        continue;
                    }
                }
                notFound++;
            }
            tpl.save(outOnnx, outData);
        }
        miss[0] += notFound;
        log.log((isEnc ? "enc" : "dec") + ": 填充 " + ok + " 个, 常量跳过 " + skip
                + ", 缺失 " + notFound
                + " → " + outData.getName() + " " + (outData.length() / 1048576) + " MB");
        return ok;
    }

    /** 模板名 → pth 名 */
    private static String toPthKey(String tplName, boolean isEnc) {
        if (isEnc) {
            if (tplName.startsWith("net.")) return tplName.substring(4);
            return null;
        } else {
            if (tplName.startsWith("d.")) return "dec." + tplName.substring(2);
            if (tplName.startsWith("net.")) return tplName.substring(4);
            return null;
        }
    }

    private static String relTarget(String tplName) {
        for (String[] m : RELPOS) {
            if (m[0].equals(tplName)) {
                return "enc_p.enc_.attn_layers." + m[1] + "." + m[2];
            }
        }
        return null;
    }

    /** 模板里参与形状计算的常量，保持原样（它们的值由动态图自己算） */
    private static boolean isShapeConstant(String n) {
        return n.startsWith("val_") || n.startsWith("_") && !n.startsWith("_src_");
    }

    // ---------- pth 读取 ----------
    private static LinkedHashMap<String, PickleReader.TensorRef> readPth(File pth, Log log) throws IOException {
        try (ZipFile z = new ZipFile(pth)) {
            ZipEntry pkl = null;
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (en.getName().endsWith("data.pkl")) { pkl = en; break; }
            }
            if (pkl == null) throw new IOException("pth 里没有 data.pkl（不是 PyTorch 保存的权重？）");
            if (pkl.getSize() > PICKLE_LIMIT) throw new IOException("data.pkl 超过 64MiB");
            byte[] data = readLimited(z.getInputStream(pkl), PICKLE_LIMIT);
            return PickleReader.loadTensors(data);
        }
    }

    private static String findDataPrefix(ZipFile z, File pth) {
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            String n = e.nextElement().getName();
            if (n.endsWith("data.pkl")) return n.substring(0, n.length() - "data.pkl".length());
        }
        return "";
    }

    /** 按 storage key 读原始 fp32 */
    private static final class Cache {
        final ZipFile z;
        final String prefix;
        long retained;
        Cache(ZipFile z, String prefix) { this.z = z; this.prefix = prefix; }

        void reserveFusion(int count) throws IOException {
            checkMemory(retained + 4L * count);
            retained += 4L * count;
        }

        float[] load(PickleReader.TensorRef tr) throws IOException {
            if (tr.storage == null || !"FloatStorage".equals(tr.storage.dtype))
                throw new IOException("仅支持 FloatStorage");
            if (tr.shape == null || tr.stride == null || tr.shape.length > 64
                    || tr.shape.length != tr.stride.length || tr.offset < 0)
                throw new IOException("非法 tensor 元数据");
            long n = 1, max = tr.offset;
            try {
                for (int d = 0; d < tr.shape.length; d++) {
                    if (tr.shape[d] < 0 || tr.shape[d] > Integer.MAX_VALUE - 8 || tr.stride[d] < 0) throw new IOException("非法 tensor 维度/stride");
                    n = Math.multiplyExact(n, tr.shape[d]);
                    max = Math.addExact(max, Math.multiplyExact(Math.max(0, tr.shape[d] - 1), tr.stride[d]));
                }
            } catch (ArithmeticException e) { throw new IOException("tensor 整数溢出", e); }
            long storage = tr.storage.numel;
            if (storage < 0 || n > Integer.MAX_VALUE - 8 || storage > Integer.MAX_VALUE - 8
                    || (n > 0 && max >= storage)) throw new IOException("tensor/storage 越界");
            ZipEntry en = z.getEntry(prefix + "data/" + tr.storage.key);
            if (en == null || en.getSize() != storage * 4L) throw new IOException("FloatStorage 长度不匹配");
            boolean contiguous = tr.isContiguous();
            long working = contiguous ? n : storage + n;
            checkMemory(retained + working * 4L);
            float[] out = new float[(int) n];
            // No memo: the template retains returned arrays already. Shared storage is reread.
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(z.getInputStream(en), 65536))) {
                if (contiguous) {
                    long skip = tr.offset * 4L;
                    while (skip > 0) {
                        long k = in.skip(skip);
                        if (k == 0) { if (in.read() < 0) throw new EOFException(); k = 1; }
                        skip -= k;
                    }
                    for (int i = 0; i < out.length; i++)
                        out[i] = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
                } else {
                    float[] all = new float[(int) storage];
                    for (int i = 0; i < all.length; i++)
                        all[i] = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
                    long[] idx = new long[tr.shape.length];
                    for (int i = 0; i < out.length; i++) {
                        long src = tr.offset;
                        for (int d = 0; d < idx.length; d++) src += idx[d] * tr.stride[d];
                        out[i] = all[(int) src];
                        for (int d = idx.length - 1; d >= 0; d--) {
                            if (++idx[d] < tr.shape[d]) break;
                            idx[d] = 0;
                        }
                    }
                }
            }
            retained += n * 4L;
            return out;
        }
    }

    // ---------- 杂项 ----------
    private static int intOf(String js, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(js);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0 && !f.isDirectory()) n = n.substring(0, dot);
        return n;
    }

    private static boolean exceedsRatio(long size, long compressed) {
        long base = Math.max(1L, compressed);
        return base <= Long.MAX_VALUE / 200 && size > base * 200;
    }

    private static void unzip(File zip, File dir, Log log) throws IOException {
        Path root = dir.getCanonicalFile().toPath();
        HashSet<Path> seen = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (ZipFile z = new ZipFile(zip)) {
            byte[] buf = new byte[65536];
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                String name = en.getName();
                if (++entries > 4096 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                        || name.startsWith("/") || name.matches("^[A-Za-z]:.*"))
                    throw new IOException("ZIP 条目数或路径非法");
                Path relative = Paths.get(name);
                for (Path part : relative) if (part.toString().equals(".."))
                    throw new IOException("ZIP 路径穿越");
                Path out = root.resolve(relative).normalize();
                if (!out.startsWith(root) || out.equals(root) || root.relativize(out).getNameCount() > 64
                        || !seen.add(out)) throw new IOException("ZIP 路径越界、过深或重复: " + name);
                long size = en.getSize(), compressed = en.getCompressedSize();
                if (size < 0 || compressed < 0 || size > ENTRY_LIMIT
                        || size > TOTAL_LIMIT - total || exceedsRatio(size, compressed))
                    throw new IOException("ZIP 大小或压缩比超限: " + name);
                if (en.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                if (dir.getUsableSpace() < size + (64L << 20)) throw new IOException("解压磁盘空间不足");
                long count = 0;
                CRC32 crc = new CRC32();
                try (InputStream in = z.getInputStream(en);
                     OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        count += n;
                        total += n;
                        if (count > size || count > ENTRY_LIMIT || total > TOTAL_LIMIT
                                || exceedsRatio(count, compressed))
                            throw new IOException("ZIP 实际解压量超限");
                        if (dir.getUsableSpace() < n + (64L << 20)) throw new IOException("解压磁盘空间不足");
                        os.write(buf, 0, n);
                        crc.update(buf, 0, n);
                    }
                }
                if (count != size || crc.getValue() != en.getCrc()) throw new IOException("ZIP 长度/CRC 不匹配");
            }
        }
        log.log("已解压 zip");
    }

    /** 返回 [pth, config] */
    private static File[] findFiles(File dir) {
        File pth = null, cfg = null;
        ArrayList<File> stack = new ArrayList<>();
        stack.add(dir);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 4097) {
            File d = stack.remove(stack.size() - 1);
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isDirectory()) { stack.add(f); continue; }
                String n = f.getName().toLowerCase();
                if ((n.endsWith(".pth") || n.endsWith(".pt"))) {
                    if (pth == null || betterModel(f, pth)) pth = f;
                } else if (n.equals("config.json")) {
                    cfg = f;
                }
            }
        }
        return new File[]{pth, cfg};
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    static byte[] readAll(InputStream in) throws IOException {
        return readLimited(in, PICKLE_LIMIT);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) {
                if (n > limit - bo.size()) throw new IOException("文件超过读取上限 " + limit);
                bo.write(b, 0, n);
            }
            return bo.toByteArray();
        }
    }

    /**
     * 决定写入时的 dims：
     *  - 元素数一致 → 沿用模板 dims（保持模板的存储布局，可能是扁平的）
     *  - 元素数不同 → 用源形状（gin_channels / n_speakers 变了的情况）
     */
    private static long[] pickDims(long[] tplDims, long[] srcShape, int count) {
        long n = 1;
        for (long v : tplDims) {
            if (v < 0 || (v != 0 && n > Long.MAX_VALUE / v)) return srcShape;
            n *= v;
        }
        if (n == count) return tplDims;
        return srcShape;
    }


    /** a 是否比 b 更适合当主模型 */
    private static boolean betterModel(File a, File b) {
        boolean apth = a.getName().toLowerCase().endsWith(".pth");
        boolean bpth = b.getName().toLowerCase().endsWith(".pth");
        if (apth != bpth) return apth;          // .pth 优先
        return a.length() > b.length();         // 否则取大的
    }

}
