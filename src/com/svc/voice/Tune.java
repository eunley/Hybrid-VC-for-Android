package com.svc.voice;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 推理线程数 / CPU 亲和性（「设置」页两项的系统层实现）。
 *
 * 亲和性走反射 libcore：
 *   Libcore.os.sched_setaffinity(int pid, StructCpuSet)
 * 新线程继承父线程亲和性，所以只要在 createSession 之前给当前线程设好，
 * ORT 的 intra-op 线程池就会落在选中的核心上。
 *
 * 任何一步失败都只记录原因并跳过（返回 -1 / false），绝不抛给调用方。
 */
public final class Tune {

    public static final int THREADS_MIN = 1;
    public static final int THREADS_MAX = Math.max(1, Runtime.getRuntime().availableProcessors());
    public static final int THREADS_DEF = 6;
    /** 默认 0~7 八核全选 */
    public static final int MASK_DEF = 0xFF;

    private static volatile Context appCtx;
    private static volatile Object osObj;
    /** 最近一次失败的简述，供 Engine 写进日志 */
    public static volatile String lastNote = "";

    private Tune() {}

    /** App 启动/服务启动时调一次，让 Engine 能读到用户设置 */
    public static void init(Context c) {
        if (c == null) return;
        Context a = c.getApplicationContext();
        appCtx = (a != null) ? a : c;
        setupAdspPath(appCtx);
    }

    /**
     * ★ QNN/HTP 必需：ADSP_LIBRARY_PATH 必须包含 nativeLibraryDir。
     * FastRPC 的加载器要用 fopen() 打开真实的 .so 文件（libQnnHtpV81Skel.so），
     * 它读不了 APK 压缩包内部。所以：
     *   · extractNativeLibs=true  让系统把 .so 解压到 nativeLibraryDir
     *   · 这里把该目录加进 ADSP_LIBRARY_PATH，DSP 侧才能找到 skel
     */
    private static void setupAdspPath(Context ctx) {
        try {
            String libDir = ctx.getApplicationInfo().nativeLibraryDir;
            String sys = "/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp/;"
                    + "/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
            String cur = System.getenv("ADSP_LIBRARY_PATH");
            String val;
            if (cur == null || cur.trim().isEmpty()) {
                val = libDir + ";" + sys;
            } else if (cur.contains(libDir)) {
                val = cur;                       // 已设过，别重复追加（ORT 会警告）
            } else {
                val = libDir + ";" + cur + ";" + sys;
            }
            android.system.Os.setenv("ADSP_LIBRARY_PATH", val, true);
            lastNote = "ADSP_LIBRARY_PATH=" + val;
        } catch (Throwable t) {
            lastNote = "ADSP_LIBRARY_PATH 设置失败：" + t;
        }
    }

    private static Prefs prefs() {
        Context c = appCtx;
        return c == null ? null : new Prefs(c);
    }

    /** 当前配置的推理线程数（没有 Context 时用默认 6） */
    public static int threads() {
        Prefs p = prefs();
        return p == null ? THREADS_DEF : p.cpuThreads();
    }

    /** 当前配置的核心位掩码（没有 Context 时用默认 0xFF） */
    public static int mask() {
        Prefs p = prefs();
        return p == null ? MASK_DEF : p.cpuMask();
    }

    /** 掩码 -> "0,1,2,3" 便于写日志 */
    public static String maskStr(int m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if ((m & (1 << i)) == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /**
     * 把当前线程的亲和性改成 mask，返回原来的掩码。
     * 返回 -1 表示没改成（调用方 unpin(-1) 什么也不做）；
     * 返回 0 表示改成了但读不到原值（unpin(0) 也不做恢复）。
     */
    /** ★ 亲和性在 Android 上受 sepolicy 限制，实测始终失败且无收益，直接停用 */
    public static final boolean PIN_ENABLED = false;

    public static long pin(int mask) {
        if (!PIN_ENABLED) return -1L;
        if (mask <= 0) return -1L;
        long orig = -1L;
        try { orig = currentMask(); } catch (Throwable ignore) {}
        if (!setAffinity(mask)) return -1L;
        return orig > 0 ? orig : 0L;
    }

    /** 恢复 pin() 返回的原掩码；失败静默 */
    public static void unpin(long orig) {
        if (orig > 0) setAffinity(orig);
    }


    // ================= 亲和性底层 =================

    private static Object os() throws Exception {
        Object o = osObj;
        if (o == null) {
            o = Class.forName("libcore.io.Libcore").getField("os").get(null);
            osObj = o;
        }
        return o;
    }

    /** 当前线程的 tid（Linux 里线程自己的 pid），taskset 用它单独改一个线程 */
    private static int myTid() {
        try { return android.os.Process.myTid(); } catch (Throwable t) { return -1; }
    }

    /** toybox 的 taskset 不认 "0x" 前缀，这里给纯十六进制 */
    private static String hex(long m) { return Long.toHexString(m); }

    /** 从 taskset 输出里抠出 "xxx affinity mask: ff" 的掩码，抠不到返回 -1 */
    private static long maskFromOutput(String out, String key) {
        if (out == null) return -1L;
        int k = out.indexOf(key);
        if (k < 0) return -1L;
        String h = out.substring(k + key.length()).trim();
        int sp = h.indexOf('\n');
        if (sp >= 0) h = h.substring(0, sp).trim();
        if (h.startsWith("0x")) h = h.substring(2);
        try { return Long.parseLong(h.trim(), 16); } catch (NumberFormatException e) { return -1L; }
    }

    /**
     * 跑 /system/bin/taskset -p <mask> <tid>。
     * 改自家线程的亲和性不需要 root，也不受隐藏 API 限制；
     * 反射在所有路径都失败时用它兜底（本机 Android 16 上就是这条生效）。
     */
    private static String runTaskset(String... args) {
        int tid = myTid();
        if (tid <= 0) return null;
        String[] cmd = new String[args.length + 2];
        cmd[0] = "/system/bin/taskset";
        System.arraycopy(args, 0, cmd, 1, args.length);
        cmd[cmd.length - 1] = String.valueOf(tid);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            lastNote = "taskset: " + brief(t);
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }

    private static boolean setAffinity(long m) {
        if (m <= 0) return false;
        // 1) 反射 libcore（老一些的 ROM 上可用）
        try {
            Object os = os();
            Class<?> cs = Class.forName("android.system.StructCpuSet");
            Object set = cs.getConstructor(long.class).newInstance(m);
            os.getClass().getMethod("sched_setaffinity", int.class, cs).invoke(os, 0, set);
            return true;
        } catch (Throwable t) {
            lastNote = brief(t);
        }
        // 2) 兜底：taskset（只改当前线程，新线程继承；新线程会继承本线程的亲和性）
        String out = runTaskset("-p", hex(m));
        if (maskFromOutput(out, "new affinity mask:") == m) { lastNote = ""; return true; }
        if (out != null) lastNote = "taskset: " + out.trim();
        return false;
    }

    private static String brief(Throwable t) {
        Throwable c = t;
        if (t instanceof InvocationTargetException && t.getCause() != null) c = t.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : (": " + msg));
    }

    /** 读当前线程的亲和性掩码，读不到返回 -1 */
    private static long currentMask() {
        // 1) 反射 sched_getaffinity
        try {
            Object os = os();
            Method m = os.getClass().getMethod("sched_getaffinity", int.class);
            long v = cpuSetToMask(m.invoke(os, 0));
            if (v > 0) return v;
        } catch (Throwable ignore) {}
        // 2) taskset -p 打印 "pid N's current affinity mask: ff"
        long v2 = maskFromOutput(runTaskset("-p"), "current affinity mask:");
        if (v2 > 0) return v2;
        // 3) 兜底：/proc/self/status（只读，无副作用）
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/status"));
            String ln;
            while ((ln = r.readLine()) != null) {
                if (!ln.startsWith("Cpus_allowed_list:")) continue;
                long v = parseCpuList(ln.substring(ln.indexOf(':') + 1).trim());
                if (v > 0) return v;
            }
        } catch (Throwable ignore) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
        return -1L;
    }

    /** StructCpuSet -> long 掩码（getCpus() 拿 long[] 或 List<Long>；字段兜底） */
    private static long cpuSetToMask(Object set) {
        if (set == null) return -1L;
        Object v = null;
        try { v = set.getClass().getMethod("getCpus").invoke(set); } catch (Throwable ignore) {}
        if (v == null) {
            try {
                Field f = set.getClass().getDeclaredField("cpus");
                f.setAccessible(true);
                v = f.get(set);
            } catch (Throwable ignore) {}
        }
        if (v instanceof long[]) {
            long[] a = (long[]) v;
            return a.length == 0 ? -1L : a[0];
        }
        if (v instanceof List) {
            long m = 0;
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size() && i < 64; i++) {
                Object o = l.get(i);
                if (o instanceof Number && ((Number) o).longValue() != 0) m |= (1L << i);
            }
            return m == 0 ? -1L : m;
        }
        return -1L;
    }

    // ================= 优先级提升 =================
    // 背景：后台运行时容易被 LMK 杀、被调度器降权、被热控降频，
    // 实测同一段音频 RTF 能在 0.7~1.95 之间大幅漂移，主要是这个原因。

    /** 把当前线程提到接近音频实时的优先级（推理线程开头调一次） */
    public static void boostThread() {
        try { android.os.Process.setThreadPriority(-16); } catch (Throwable ignore) {}
    }

    private static volatile boolean procBoosted = false;
    public static volatile String boostNote = "";

    /**
     * 进程级防杀：oom_score_adj = -1000（最不可能被 LMK 选中）。
     * 没 root 就算了，纯后台线程跑，不阻塞调用方。
     */
    public static void boostProcess() {
        if (procBoosted) return;
        procBoosted = true;
        final int pid = android.os.Process.myPid();
        new Thread(() -> {
            String r1 = runSu("echo -1000 > /proc/" + pid + "/oom_score_adj");
            String r2 = runSu("cat /proc/" + pid + "/oom_score_adj");
            boostNote = (r2 != null && r2.trim().equals("-1000"))
                    ? "已防杀(oom=-1000)" : ("防杀未生效" + (r1 == null ? "" : " " + r1.trim()));
        }, "svc-boost").start();
    }

    /** 跑一条 su 命令，返回输出；失败返回 null（绝不抛） */
    public static String runSu(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            long deadline = System.currentTimeMillis() + 4000;
            while ((n = p.getInputStream().read(buf)) > 0 && System.currentTimeMillis() < deadline) {
                bo.write(buf, 0, n);
            }
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }

    /** "0-7" / "0,2-3" -> 掩码 */
    private static long parseCpuList(String s) {
        long m = 0;
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).trim());
                    int b = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int i = a; i <= b && i < 64; i++) m |= (1L << i);
                } else {
                    int a = Integer.parseInt(part);
                    if (a >= 0 && a < 64) m |= (1L << a);
                }
            } catch (NumberFormatException ignore) {}
        }
        return m;
    }
}
