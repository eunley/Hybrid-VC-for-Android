package com.svc.voice;

import java.io.File;
import java.util.Locale;

/**
 * 设备 SoC 识别 + HTP 架构映射。
 *
 * 设计原则（重要）：
 *  1. 只从「可读的系统真实来源」取芯片型号，绝不靠商品名/机型名猜：
 *       a. Build.SOC_MODEL（API 31+；字段缺失时用反射，编译目标仍是 SDK 34 所以直接可用）
 *       b. /sys/devices/soc0/{machine,chip_id,chip_family} 等 sysfs
 *       c. ro.board.platform / ro.soc.model 等系统属性（兜底，只做校验，不主推）
 *  2. 型号 → HTP 架构 只查「官方表」（QAIRT SDK 设备表 / Qualcomm AI Hub 芯片表），
 *     表里没有 → 返回未知。未知绝不强行当某个架构用。
 *  3. 「本安装包内实际有哪些 skel」由 {@link #skelAvailable(int)} 判断，
 *     缺库时上层必须明确报错并回退 CPU，不允许用别的架构顶替。
 *
 * 数据来源（打包进 assets/htp-soc-table.json，见 htp-implementation.md）：
 *  - QAIRT 2.42 SDK overview 设备表（QNN SOC Model / Hexagon Arch）
 *  - qualcomm/ai-hub-models devices_and_chipsets.yaml（htp_version）
 *  - pytorch/executorch qc_schema.py _soc_info_table（HTP arch / VTCM）
 */
public final class SocInfo {

    /** HTP 架构未知 */
    public static final int ARCH_UNKNOWN = 0;

    public final String socModel;   // 规范化型号，例如 SM8850；未知为 null
    public final String source;     // 型号取自哪个来源
    public final int arch;          // 68/69/73/75/79/81/85；未知为 0
    public final int vtcmMiB;       // 官方表给出的 VTCM，未知为 0
    public final String detail;     // 人类可读说明

    SocInfo(String socModel, String source, int arch, int vtcmMiB, String detail) {
        this.socModel = socModel;
        this.source = source;
        this.arch = arch;
        this.vtcmMiB = vtcmMiB;
        this.detail = detail;
    }

    public boolean known() { return arch != ARCH_UNKNOWN; }

    /** v81 这样的短名，未知返回 null */
    public static String archName(int arch) {
        return arch == ARCH_UNKNOWN ? null : "v" + arch;
    }

    @Override public String toString() {
        return "SoC=" + (socModel == null ? "未知" : socModel)
                + "(" + source + ")"
                + " 架构=" + (known() ? archName(arch) : "未知")
                + (vtcmMiB > 0 ? " VTCM=" + vtcmMiB +"MB" : "");
    }

    // ───────────────────────── 读取 ─────────────────────────

    /** 读设备 SoC 型号：Build.SOC_MODEL 优先，其次 sysfs。返回 null 表示确实读不到。 */
    static String readSocModel(String[] outSource) {
        String v = null;
        // 1) Build.SOC_MODEL（API 31+）。字段是 public static String；
        //    targetSdk 34 编译可直接引用，但为了在 <31 上不炸仍然走反射。
        try {
            Object o = Class.forName("android.os.Build").getField("SOC_MODEL").get(null);
            if (o instanceof String) v = ((String) o).trim();
        } catch (Throwable ignore) { /* API < 31 或字段被移除 */ }
        if (valid(v)) { if (outSource != null) outSource[0] = "Build.SOC_MODEL"; return v; }

        // 2) sysfs：chip_id 在多数高通设备上是 SMxxxx，machine 是 codename（CANOE 之类）
        for (String p : new String[]{"/sys/devices/soc0/chip_id",
                                     "/sys/devices/soc0/machine",
                                     "/sys/devices/soc0/family",
                                     "/sys/devices/soc0/soc_id"}) {
            String s = readSys(p);
            if (valid(s)) { if (outSource != null) outSource[0] = p; return s; }
        }

        // 3) 系统属性兜底
        String b = SysProps.get("ro.soc.model");
        if (valid(b)) { if (outSource != null) outSource[0] = "ro.soc.model"; return b; }
        return null;
    }

    private static String readSys(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) return null;
            byte[] b = new byte[128];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return null;
                String s = new String(b, 0, n, "UTF-8").trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Throwable t) { return null; }
    }

    /** 芯片型号是否"看起来像"可用于查表的型号（过滤 CANOE/canoe/MTP 这类 codename） */
    private static boolean valid(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty()) return false;
        String u = s.toUpperCase(Locale.ROOT);
        if (u.equals("UNKNOWN") || u.startsWith("UNKNOWN")) return false;
        // 纯数字（sysfs soc_id=660）不是型号，交给表里按 soc_id 处理；这里不接受
        if (u.matches("\\d+")) return false;
        return true;
    }

    // ───────────────────────── 映射 ─────────────────────────

    /**
     * 依据型号查官方表。socModel 为 null/未知时返回未知，绝不猜。
     * @param table 由 HtpTable 提供的已解析映射
     */
    static SocInfo map(String socModel, String source, HtpTable table) {
        if (socModel == null) return new SocInfo(null, source == null ? "未读到" : source, ARCH_UNKNOWN, 0,
                "系统未提供可读的 SoC 型号");
        HtpTable.Row r = table.find(socModel);
        if (r == null) return new SocInfo(norm(socModel), source, ARCH_UNKNOWN, 0,
                "官方表未收录该型号，按未知处理（不推测）");
        if (r.noHtp || r.arch == ARCH_UNKNOWN)
            return new SocInfo(r.soc, source + "→官方表", ARCH_UNKNOWN, 0,
                    "官方表标注该芯片无 HTP（N/A），而不是“未知”");
        return new SocInfo(r.soc, source + "→官方表", r.arch, r.vtcmMiB, r.note);
    }

    /** 规范化：SM8850-AD → SM8850；sm8850 → SM8850；SDM845 → SDM845 */
    static String norm(String s) {
        String u = s.trim().toUpperCase(Locale.ROOT);
        // 去掉分辨率后缀 -AC / -AD / -AB / -AA
        u = u.replaceFirst("-(AC|AD|AB|AA)$", "");
        return u;
    }

    private static SocInfo CACHE;
    private static boolean CACHE_DONE;

    /** 进程内只解析一次（用户要求：打开时查一次，结果当常量常驻内存） */
    public static synchronized SocInfo detect() {
        if (CACHE_DONE) return CACHE;
        String[] src = new String[1];
        String m = readSocModel(src);
        HtpTable table = HtpTable.get();
        CACHE = map(m, src[0] == null ? "未读到" : src[0], table);
        // 表为空（还没 attach Context）时不要把它固化成"未知"，留给下次重新识别
        CACHE_DONE = !(table.isEmpty() && m != null);
        return CACHE;
    }

    /**
     * 本安装包内是否同时有该架构的 Skel/Stub（决定能否真的上 NPU）。
     * 通过 VmRSS/dalvik 无法判断，所以直接看 nativeLibraryDir 里是否同时存在 libQnnHtpV{n}Skel.so / Stub.so。
     */
    public static boolean skelAvailable(int arch) {
        if (arch == ARCH_UNKNOWN) return false;
        String dir = SysProps.nativeLibDir();
        if (dir == null) return false;
        return new File(dir, "libQnnHtpV" + arch + "Skel.so").isFile()
                && new File(dir, "libQnnHtpV" + arch + "Stub.so").isFile();
    }

    /** 供日志/诊断：列出当前可用的 skel 架构 */
    public static java.util.List<Integer> availableArches() {
        java.util.List<Integer> r = new java.util.ArrayList<>();
        for (int a : new int[]{68, 69, 73, 75, 79, 81, 85}) if (skelAvailable(a)) r.add(a);
        return r;
    }
}
