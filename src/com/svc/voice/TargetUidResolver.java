package com.svc.voice;

/** Pure target selection shared by echo and realtime; never falls back to root. */
final class TargetUidResolver {
    interface Packages {
        int defaultUid() throws Exception;
        boolean exists(int uid);
    }
    static int resolve(Integer explicit, Packages packages) {
        final int uid;
        if (explicit != null) {
            uid = explicit;
        } else {
            try { uid = packages.defaultUid(); }
            catch (Exception e) {
                throw new IllegalStateException("未安装默认目标应用微信，或目标应用不可见，无法解析目标 UID", e);
            }
        }
        if (uid <= 0) throw new IllegalStateException("目标 UID 无效：" + uid + "，不能使用 root 或缺省 UID 启动采集");
        if (!packages.exists(uid)) throw new IllegalStateException("目标 UID 对应的应用不存在或不可见：" + uid);
        return uid;
    }
    private TargetUidResolver() { }
}
