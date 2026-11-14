package com.svc.voice;

/** 轻量系统信息读取：系统属性 / nativeLibraryDir。全部失败安全。 */
final class SysProps {

    private static String NATIVE_DIR;
    private static boolean ADSP_DONE;

    private SysProps() {}

    /** 反射读 android.os.SystemProperties（隐藏 API，读失败返回 null） */
    static String get(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            if (v instanceof String) {
                String s = ((String) v).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    static void setNativeLibDir(String dir) { NATIVE_DIR = dir; }

    static String nativeLibDir() { return NATIVE_DIR; }

    /**
     * QNN/HTP 必需：ADSP_LIBRARY_PATH 要包含解压后的 .so 目录，
     * FastRPC 才能用 fopen 打开 libQnnHtpV{arch}Skel.so。
     * 单独成方法便于在本轮改动中明确控制调用点（Tune 里原有实现保留不动）。
     */
    static synchronized boolean ensureAdspPath(String libDir) {
        if (ADSP_DONE || libDir == null) return ADSP_DONE;
        try {
            String sys = "/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp/;"
                    + "/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
            String cur = System.getenv("ADSP_LIBRARY_PATH");
            String val;
            if (cur == null || cur.trim().isEmpty()) val = libDir + ";" + sys;
            else if (cur.contains(libDir)) val = cur;
            else val = libDir + ";" + cur + ";" + sys;
            android.system.Os.setenv("ADSP_LIBRARY_PATH", val, true);
            ADSP_DONE = true;
        } catch (Throwable ignore) {}
        return ADSP_DONE;
    }
}
