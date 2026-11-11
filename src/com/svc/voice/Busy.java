package com.svc.voice;

/** 全局互斥：同一时刻只允许一套模型在内存里（文件变声 / 实时 / 基准测试） */
public final class Busy {

    private static final java.util.concurrent.atomic.AtomicReference<String> OWNER =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    private Busy() {}

    /** 抢占；成功返回 true，失败说明别人正占着 */
    public static boolean acquire(String who) {
        return OWNER.compareAndSet(null, who);
    }

    public static void release(String who) {
        if (who != null) OWNER.compareAndSet(who, null);
    }

    /** 当前占用者，没人用为 null */
    public static String holder() { return OWNER.get(); }

    /** 谁在跑，给提示用 */
    public static String holderName() {
        String h = OWNER.get();
        if (h == null) return "";
        if (h.startsWith("file")) return "音频变声";
        if (h.startsWith("rt")) return "实时变声";
        if (h.startsWith("bench")) return "基准测试";
        return h;
    }

    /** 前台服务用：抢不到就抛异常 */
    public static void acquireOrThrow(String who) throws IllegalStateException {
        if (!acquire(who)) throw new IllegalStateException("正在" + holderName() + "，请先停止");
    }
}