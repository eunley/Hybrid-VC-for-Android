package com.svc.voice;

import java.io.*;
import java.util.*;

/**
 * 极简 protobuf 读写：不依赖 schema，按 (字段号, wire type) 原样解析与回写。
 * 用于修改 ONNX 模型里的 TensorProto 而无需引入 protobuf-java。
 *
 * wire type: 0=varint 1=fixed64 2=length-delimited 5=fixed32
 */
public final class PB {

    public static final class Field {
        public int num;
        public int wt;
        public long varint;     // wt=0
        public byte[] bytes;    // wt=2
        public long fixed;      // wt=1 / 5 原始 8 或 4 字节（低位存放）

        Field(int n, int w) { num = n; wt = w; }
        public String toString() { return "field" + num + "/wt" + wt; }
    }

    // ---------- 读 ----------
    public static List<Field> parse(byte[] b) throws IOException { return parse(b, 0, b.length); }

    public static List<Field> parse(byte[] b, int off, int len) throws IOException {
        List<Field> out = new ArrayList<>();
        int p = off, end = off + len;
        while (p < end) {
            long tag = 0; int shift = 0;
            while (true) {
                int c = b[p++] & 0xFF;
                tag |= ((long)(c & 0x7F)) << shift;
                if ((c & 0x80) == 0) break;
                shift += 7;
            }
            int num = (int)(tag >>> 3);
            int wt = (int)(tag & 7);
            Field f = new Field(num, wt);
            switch (wt) {
                case 0: {
                    long v = 0; shift = 0;
                    while (true) {
                        int c = b[p++] & 0xFF;
                        v |= ((long)(c & 0x7F)) << shift;
                        if ((c & 0x80) == 0) break;
                        shift += 7;
                    }
                    f.varint = v;
                    break;
                }
                case 1: {
                    long v = 0;
                    for (int i = 0; i < 8; i++) v |= ((long)(b[p+i] & 0xFF)) << (8*i);
                    p += 8; f.fixed = v;
                    break;
                }
                case 5: {
                    long v = 0;
                    for (int i = 0; i < 4; i++) v |= ((long)(b[p+i] & 0xFF)) << (8*i);
                    p += 4; f.fixed = v;
                    break;
                }
                case 2: {
                    long n = 0; shift = 0;
                    while (true) {
                        int c = b[p++] & 0xFF;
                        n |= ((long)(c & 0x7F)) << shift;
                        if ((c & 0x80) == 0) break;
                        shift += 7;
                    }
                    f.bytes = Arrays.copyOfRange(b, p, p + (int) n);
                    p += (int) n;
                    break;
                }
                default:
                    throw new IOException("未知 wire type " + wt + " field " + num + " @" + p);
            }
            out.add(f);
        }
        return out;
    }

    // ---------- 写 ----------
    public static byte[] write(List<Field> fs) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
        try { writeTo(bo, fs); } catch (IOException e) { throw new RuntimeException(e); }
        return bo.toByteArray();
    }

    public static void writeTo(OutputStream out, List<Field> fs) throws IOException {
        for (Field f : fs) {
            writeVarint(out, ((long) f.num << 3) | f.wt);
            switch (f.wt) {
                case 0: writeVarint(out, f.varint); break;
                case 1: for (int i = 0; i < 8; i++) out.write((int)((f.fixed >>> (8*i)) & 0xFF)); break;
                case 5: for (int i = 0; i < 4; i++) out.write((int)((f.fixed >>> (8*i)) & 0xFF)); break;
                case 2: writeVarint(out, f.bytes.length); out.write(f.bytes); break;
                default: throw new IOException("坏 wire type " + f.wt);
            }
        }
    }

    private static void writeVarint(OutputStream out, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            out.write((int)((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    // ---------- 便捷构造 ----------
    public static Field varint(int num, long v) { Field f = new Field(num, 0); f.varint = v; return f; }
    public static Field ld(int num, byte[] b) { Field f = new Field(num, 2); f.bytes = b; return f; }
    public static Field ld(int num, List<Field> sub) { return ld(num, write(sub)); }
    public static Field ldString(int num, String s) {
        try { return ld(num, s.getBytes("UTF-8")); } catch (UnsupportedEncodingException e) { throw new RuntimeException(e); }
    }

    // ---------- 查找 ----------
    public static Field find(List<Field> fs, int num) {
        for (Field f : fs) if (f.num == num) return f;
        return null;
    }
    public static List<Field> findAll(List<Field> fs, int num) {
        List<Field> r = new ArrayList<>();
        for (Field f : fs) if (f.num == num) r.add(f);
        return r;
    }
    public static String asString(Field f) {
        if (f == null || f.bytes == null) return null;
        try { return new String(f.bytes, "UTF-8"); } catch (UnsupportedEncodingException e) { return null; }
    }

    // ---------- packed 整数（用于 TensorProto.dims）----------
    public static long[] packedLong(Field f) {
        if (f == null) return new long[0];
        // packed 形式：bytes 里连续 varint；也兼容非 packed（每个一个 field）
        List<Long> out = new ArrayList<>();
        byte[] b = f.bytes;
        int p = 0;
        while (p < b.length) {
            long v = 0; int shift = 0;
            while (true) {
                int c = b[p++] & 0xFF;
                v |= ((long)(c & 0x7F)) << shift;
                if ((c & 0x80) == 0) break;
                shift += 7;
            }
            out.add(v);
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    public static byte[] packLong(long[] vs) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try { for (long v : vs) writeVarint(bo, v); } catch (IOException e) { throw new RuntimeException(e); }
        return bo.toByteArray();
    }

    /**
     * 读 TensorProto 的 dims（field 1）。
     * ONNX 用 proto2，repeated int64 默认【非 packed】——每个 dim 一个独立的 field1/wt0。
     * 但也兼容 packed 形式。
     */
    public static long[] getDims(List<Field> tp) {
        List<Field> ds = findAll(tp, 1);
        if (ds.isEmpty()) return new long[0];
        if (ds.size() == 1 && ds.get(0).wt == 2) return packedLong(ds.get(0));
        long[] a = new long[ds.size()];
        for (int i = 0; i < a.length; i++) a[i] = ds.get(i).varint;
        return a;
    }

    /** 替换 TensorProto 的 dims（先删所有 field1，再在第一个原位置插入新的非 packed 序列） */
    public static void setDims(List<Field> tp, long[] dims) {
        int insertAt = -1;
        for (int i = 0; i < tp.size(); i++) {
            if (tp.get(i).num == 1) { if (insertAt < 0) insertAt = i; }
        }
        for (int i = tp.size() - 1; i >= 0; i--) if (tp.get(i).num == 1) tp.remove(i);
        if (insertAt < 0) insertAt = 0;
        for (int i = dims.length - 1; i >= 0; i--) tp.add(insertAt, varint(1, dims[i]));
    }

    /** 去掉某个字段的全部实例 */
    public static void removeAll(List<Field> fs, int num) {
        for (int i = fs.size() - 1; i >= 0; i--) if (fs.get(i).num == num) fs.remove(i);
    }

    /** 设置（替换）单值字段 */
    public static void setOne(List<Field> fs, Field nf) {
        removeAll(fs, nf.num);
        fs.add(nf);
    }

}
