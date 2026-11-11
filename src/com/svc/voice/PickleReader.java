package com.svc.voice;

import java.io.*;
import java.util.*;

/**
 * 极简 Pickle 解析器，只处理 PyTorch .pth 用到的 opcode 子集。
 * 目标：从 data.pkl 还原 {张量名 -> (storage key, offset, shape, stride)}。
 */
public final class PickleReader {

    public static final class GlobalRef {
        public final String module, name;
        GlobalRef(String m, String n) { module = m; name = n; }
        public String toString() { return module + " " + name; }
    }

    /** torch 的 storage 引用，由 BINPERSID 产生 */
    public static final class StorageRef {
        public final String dtype;
        public final String key;
        public final String location;
        public final long numel;
        StorageRef(String d, String k, String loc, long n) {
            dtype = d; key = k; location = loc; numel = n;
        }
        public String toString() { return dtype + "#" + key + "(" + numel + ")"; }
    }

    /** _rebuild_tensor_v2 的结果 */
    public static final class TensorRef {
        public final StorageRef storage;
        public final long offset;
        public final long[] shape;
        public final long[] stride;
        public TensorRef(StorageRef s, long off, long[] sh, long[] st) {
            storage = s; offset = off; shape = sh; stride = st;
        }
        public long numel() {
            long n = 1;
            for (long v : shape) n *= v;
            return n;
        }
        public boolean isContiguous() {
            long acc = 1;
            for (int i = shape.length - 1; i >= 0; i--) {
                if (shape[i] != 1 && stride[i] != acc) return false;
                acc *= shape[i];
            }
            return true;
        }
        public String toString() {
            return Arrays.toString(shape) + "/" + Arrays.toString(stride) + "@" + offset;
        }
    }

    public static final class OrderedDict extends LinkedHashMap<Object, Object> {}

    private final byte[] buf;
    private int pos;
    private final ArrayList<Object> stack = new ArrayList<>();
    private final ArrayList<Integer> marks = new ArrayList<>();
    private final HashMap<Integer, Object> memo = new HashMap<>();

    private PickleReader(byte[] b) { buf = b; }

    public static Object load(byte[] data) throws IOException {
        return new PickleReader(data).run();
    }

    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, TensorRef> loadTensors(byte[] data) throws IOException {
        Object root = load(data);
        if (!(root instanceof Map)) throw new IOException("顶层不是 dict");
        Map<Object, Object> top = (Map<Object, Object>) root;
        Object model = top.get("model");
        if (!(model instanceof Map)) model = top;
        LinkedHashMap<String, TensorRef> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) model).entrySet()) {
            if (e.getValue() instanceof TensorRef) out.put(String.valueOf(e.getKey()), (TensorRef) e.getValue());
        }
        return out;
    }

    private int u1() { return buf[pos++] & 0xFF; }
    private int u2() { return (buf[pos++] & 0xFF) | ((buf[pos++] & 0xFF) << 8); }
    private int i4() {
        int v = (buf[pos] & 0xFF) | ((buf[pos+1] & 0xFF) << 8)
              | ((buf[pos+2] & 0xFF) << 16) | ((buf[pos+3] & 0xFF) << 24);
        pos += 4; return v;
    }
    private void push(Object o) { stack.add(o); }
    private Object pop() {
        if (stack.isEmpty()) throw new IllegalStateException("stack underflow");
        return stack.remove(stack.size() - 1);
    }
    private List<Object> popToMark() {
        int m = marks.remove(marks.size() - 1);
        List<Object> r = new ArrayList<>(stack.subList(m, stack.size()));
        while (stack.size() > m) stack.remove(stack.size() - 1);
        return r;
    }
    private Object top() { return stack.get(stack.size() - 1); }

    @SuppressWarnings("unchecked")
    private Object run() throws IOException {
        while (pos < buf.length) {
            int op = u1();
            switch (op) {
                case 0x80: { int v = u1(); if (v > 5) throw new IOException("protocol " + v + " 太新"); break; }
                case '.': return pop();
                case '(': marks.add(stack.size()); break;
                case '}': push(new LinkedHashMap<Object,Object>()); break;
                case ')': push(new ArrayList<Object>()); break;
                case ']': push(new ArrayList<Object>()); break;
                case 't': push(popToMark()); break;
                case 0x85: { Object a = pop(); List<Object> l = new ArrayList<>(1); l.add(a); push(l); break; }
                case 0x86: { Object b = pop(), a = pop(); List<Object> l = new ArrayList<>(2); l.add(a); l.add(b); push(l); break; }
                case 0x87: { Object c = pop(), b = pop(), a = pop(); List<Object> l = new ArrayList<>(3); l.add(a); l.add(b); l.add(c); push(l); break; }
                case 'X': { int n = i4(); push(new String(buf, pos, n, "UTF-8")); pos += n; break; }
                case 0x8c: { int n = u1(); push(new String(buf, pos, n, "UTF-8")); pos += n; break; }
                case 'T': { int n = i4(); push(new String(buf, pos, n, "ISO-8859-1")); pos += n; break; }
                case 'U': { int n = u1(); push(new String(buf, pos, n, "ISO-8859-1")); pos += n; break; }
                case 'V': { int s = pos; while (buf[pos] != '\n') pos++; push(new String(buf, s, pos - s, "UTF-8")); pos++; break; }
                case 'J': push((long) i4()); break;
                case 'K': push((long) u1()); break;
                case 'M': push((long) u2()); break;
                case 0x8a: {
                    int n = u1(); long v = 0;
                    for (int i = 0; i < n && i < 8; i++) v |= ((long)(buf[pos+i] & 0xFF)) << (8*i);
                    if (n > 0 && n < 8 && (buf[pos+n-1] & 0x80) != 0) v |= -1L << (8*n);
                    pos += n; push(v); break;
                }
                case 0x8b: {
                    int n = i4(); long v = 0;
                    for (int i = 0; i < n && i < 8; i++) v |= ((long)(buf[pos+i] & 0xFF)) << (8*i);
                    pos += n; push(v); break;
                }
                case 'I': {
                    int s = pos; while (buf[pos] != '\n') pos++;
                    String t = new String(buf, s, pos - s, "UTF-8").trim(); pos++;
                    if ("00".equals(t)) push(Boolean.FALSE);
                    else if ("01".equals(t)) push(Boolean.TRUE);
                    else push(Long.valueOf(t));
                    break;
                }
                case 'G': {
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits = (bits << 8) | (buf[pos++] & 0xFF);
                    push(Double.longBitsToDouble(bits)); break;
                }
                case 'F': {
                    int s = pos; while (buf[pos] != '\n') pos++;
                    push(Double.valueOf(new String(buf, s, pos - s, "UTF-8").trim())); pos++;
                    break;
                }
                case 'N': push(null); break;
                case 0x88: push(Boolean.TRUE); break;
                case 0x89: push(Boolean.FALSE); break;
                case 'c': {
                    int s = pos; while (buf[pos] != '\n') pos++;
                    String mod = new String(buf, s, pos - s, "UTF-8"); pos++;
                    s = pos; while (buf[pos] != '\n') pos++;
                    String nm = new String(buf, s, pos - s, "UTF-8"); pos++;
                    push(new GlobalRef(mod, nm)); break;
                }
                case 0x93: { Object nm = pop(), mod = pop(); push(new GlobalRef(String.valueOf(mod), String.valueOf(nm))); break; }
                case 'R': { Object args = pop(); Object fn = pop(); push(reduce(fn, args)); break; }
                case 'b': {
                    Object state = pop();
                    Object obj = stack.isEmpty() ? null : top();
                    if (obj instanceof Map && state instanceof Map) ((Map<Object,Object>) obj).putAll((Map<Object,Object>) state);
                    else if (obj instanceof List && state instanceof List) for (Object o : (List<?>) state) ((List<Object>) obj).add(o);
                    break;
                }
                case 'q': memo.put(u1(), top()); break;
                case 'r': memo.put(i4(), top()); break;
                case 'h': push(memo.get(u1())); break;
                case 'j': push(memo.get(i4())); break;
                case 0x94: memo.put(memo.size(), top()); break;
                case 's': { Object v = pop(), k = pop(); ((Map<Object,Object>) top()).put(k, v); break; }
                case 'u': {
                    List<Object> items = popToMark();
                    Map<Object,Object> m = (Map<Object,Object>) top();
                    for (int i = 0; i + 1 < items.size(); i += 2) m.put(items.get(i), items.get(i+1));
                    break;
                }
                case 'a': { Object v = pop(); ((List<Object>) top()).add(v); break; }
                case 'e': { List<Object> items = popToMark(); ((List<Object>) top()).addAll(items); break; }
                case 'Q': { Object pid = pop(); push(persistentLoad(pid)); break; }
                case 'P': {
                    int s = pos; while (buf[pos] != '\n') pos++;
                    String t = new String(buf, s, pos - s, "UTF-8"); pos++;
                    push(persistentLoad(t)); break;
                }
                default:
                    throw new IOException(String.format("未支持 opcode 0x%02x @%d", op, pos-1));
            }
        }
        throw new IOException("pickle 没有 STOP");
    }

    @SuppressWarnings("unchecked")
    private Object reduce(Object fn, Object argsObj) throws IOException {
        if (!(fn instanceof GlobalRef)) return null;
        GlobalRef g = (GlobalRef) fn;
        List<Object> args = (argsObj instanceof List) ? (List<Object>) argsObj : Collections.emptyList();
        if ("collections".equals(g.module) && "OrderedDict".equals(g.name)) return new OrderedDict();
        if ("torch._utils".equals(g.module) && "_rebuild_tensor_v2".equals(g.name)) {
            if (args.size() < 4 || !(args.get(0) instanceof StorageRef))
                throw new IOException("_rebuild_tensor_v2 参数异常");
            return new TensorRef((StorageRef) args.get(0), asLong(args.get(1)),
                                 asLongArr(args.get(2)), asLongArr(args.get(3)));
        }
        if ("torch._utils".equals(g.module) && "_rebuild_parameter".equals(g.name)) {
            return args.isEmpty() ? null : args.get(0);
        }
        if ("builtins".equals(g.module) && "set".equals(g.name)) return new ArrayList<Object>();
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object persistentLoad(Object pid) throws IOException {
        if (pid instanceof List) {
            List<Object> l = (List<Object>) pid;
            if (l.size() >= 3 && "storage".equals(l.get(0))) {
                String dtype = (l.get(1) instanceof GlobalRef) ? ((GlobalRef) l.get(1)).name : "FloatStorage";
                return new StorageRef(dtype, String.valueOf(l.get(2)),
                        l.size() >= 4 ? String.valueOf(l.get(3)) : "",
                        l.size() >= 5 ? asLong(l.get(4)) : 0);
            }
        }
        throw new IOException("无法处理的 persistent_id");
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0;
    }
    private static long[] asLongArr(Object o) {
        if (!(o instanceof List)) return new long[0];
        List<?> l = (List<?>) o;
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = asLong(l.get(i));
        return a;
    }
}
