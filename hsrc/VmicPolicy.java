import android.os.Looper;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;

/**
 * 虚拟麦克风注入器（照 MicBoost setupAudioPolicy 实现）
 *
 * app_process /system/bin VmicPolicy <mode> [args]
 *   selftest <wav>            注册 preset 7，注入 440Hz，并录音验证
 *   feed <fifo> <presets>     持续从 FIFO 读 PCM16/48000/mono 写入所有注入 track
 *                             presets 形如 "1,7"
 */
public class VmicPolicy {

    static Class<?> cls(String n) throws Exception { return Class.forName(n); }

    static Method findMethod(Class<?> k, String name, Class<?>[] pts) {
        for (Class<?> cur = k; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(name, pts);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignore) { }
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (pts != null && m.getParameterCount() != pts.length) continue;
                try { m.setAccessible(true); return m; } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    static Object call(Object o, String name, Class<?>[] pts, Object... args) throws Exception {
        Method m = findMethod(o.getClass(), name, pts);
        if (m == null) throw new NoSuchMethodException(name + " on " + o.getClass().getName());
        return m.invoke(o, args);
    }

    static int asInt(Object o) { return ((Number) o).intValue(); }

    static final List<Object> TRACKS = new ArrayList<Object>();
    static Object POLICY;
    static Object MANAGER;

    static Object buildMix(Class<?> mixbCls, Class<?> ruleCls, Object rule,
                           Object fmt, Class<?> afCls) throws Exception {
        Constructor<?> c = mixbCls.getDeclaredConstructor(ruleCls);
        c.setAccessible(true);
        Object b = c.newInstance(rule);
        call(b, "setFormat", new Class<?>[] { afCls }, fmt);
        call(b, "setRouteFlags", new Class<?>[] { int.class }, 2); // MIX_ROUTE_FLAG_LOOP_BACK
        return call(b, "build", new Class<?>[] { });
    }

    /** 注册 AudioPolicy，为每个 preset 建一个注入 AudioTrack。
     *  excludeUid >= 0 时，用 excludeMixRule(RULE_MATCH_UID, uid) 把该 uid 排除在注入之外，
     *  这样"自己 App 的采集"不会被自己的 policy 拦截（MicBoost 就是这么做的）。
     */
    static boolean setup(int[] presets) throws Exception { return setup(presets, -1); }

    static boolean setup(int[] presets, int excludeUid) throws Exception { return setup(presets, excludeUid, -1); }

    /**
     * @param includeUid >=0 时用白名单（MicBoost 的 AppScope.selectedOnly=true）：
     *        只对这一个 uid 生效，录音机/相机等其他 App 完全不受影响。
     */
    static boolean setup(int[] presets, int excludeUid, long includeUid) throws Exception {
        try { Looper.class.getMethod("prepare").invoke(null); } catch (Throwable ignore) { }

        Class<?> atCls = cls("android.app.ActivityThread");
        Object thread = atCls.getMethod("systemMain").invoke(null);
        Object sysCtx = call(thread, "getSystemContext", new Class<?>[] { });
        System.out.println("[vmic] systemContext ok");

        Class<?> ctxCls = cls("android.content.Context");
        Class<?> pbCls = cls("android.media.audiopolicy.AudioPolicy$Builder");
        Constructor<?> pbCtor = pbCls.getConstructor(ctxCls);
        Object pb = pbCtor.newInstance(sysCtx);

        Class<?> mbCls = cls("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> abCls = cls("android.media.AudioAttributes$Builder");
        Class<?> aaCls = cls("android.media.AudioAttributes");
        Class<?> afCls = cls("android.media.AudioFormat$Builder");
        Class<?> afT = cls("android.media.AudioFormat");
        Class<?> mixCls = cls("android.media.audiopolicy.AudioMix");
        Class<?> mixbCls = cls("android.media.audiopolicy.AudioMix$Builder");
        Class<?> ruleCls = cls("android.media.audiopolicy.AudioMixingRule");

        // 统一格式：48000 / PCM16 / mono
        Object afb = afCls.getConstructor().newInstance();
        call(afb, "setEncoding", new Class<?>[] { int.class }, 2);
        call(afb, "setSampleRate", new Class<?>[] { int.class }, 48000);
        call(afb, "setChannelMask", new Class<?>[] { int.class }, 16);
        Object fmt = call(afb, "build", new Class<?>[] { });

        List<Object> mixes = new ArrayList<Object>();
        for (int preset : presets) {
            Object mb = mbCls.getConstructor().newInstance();
            call(mb, "setTargetMixRole", new Class<?>[] { int.class }, 1); // MIX_ROLE_INJECTOR
            Object ab = abCls.getConstructor().newInstance();
            call(ab, "setCapturePreset", new Class<?>[] { int.class }, preset);
            Object attrs = call(ab, "build", new Class<?>[] { });
            call(mb, "addMixRule", new Class<?>[] { int.class, aaCls }, 2, attrs); // RULE_MATCH_ATTRIBUTES
            // RULE_MATCH_UID=4；把 root 采集进程(uid 0)排除在注入之外，
            // 否则自己的采集也会被重定向到 submix，形成自激。
            if (excludeUid >= 0) {
                Integer ev = Integer.valueOf(excludeUid);
                boolean done = false;
                try {
                    call(mb, "excludeMixRule", new Class<?>[] { int.class, Integer.class }, 4, ev);
                    done = true;
                } catch (Throwable ignore) { }
                if (!done) {
                    try { call(mb, "excludeMixRule", new Class<?>[] { int.class, Object.class }, 4, (Object) ev); }
                    catch (Throwable failure) { throw new IllegalStateException("UID exclusion failed uid=" + excludeUid, failure); }
                }
            }
            Object rule = call(mb, "build", new Class<?>[] { });
            Object mix = buildMix(mixbCls, ruleCls, rule, fmt, afT);
            call(pb, "addMix", new Class<?>[] { mixCls }, mix);
            mixes.add(mix);
            System.out.println("[vmic] mix preset=" + preset + " ready");
        }

        Object policy = call(pb, "build", new Class<?>[] { });
        POLICY = policy;
        Object am = call(sysCtx, "getSystemService", new Class<?>[] { cls("java.lang.String") }, "audio");
        MANAGER = am;
        Object rc = call(am, "registerAudioPolicy", new Class<?>[] { cls("android.media.audiopolicy.AudioPolicy") }, policy);
        System.out.println("[vmic] registerAudioPolicy -> " + rc);
        if (asInt(rc) != 0) return false;

        for (Object mix : mixes) {
            Object track = call(policy, "createAudioTrackSource", new Class<?>[] { mixCls }, mix);
            if (track == null) { System.out.println("[vmic] track null"); continue; }
            Object st = call(track, "getState", new Class<?>[] { });
            System.out.println("[vmic] track state=" + st);
            if (asInt(st) == 1) {
                call(track, "play", new Class<?>[] { });
                TRACKS.add(track);
            }
        }
        System.out.println("[vmic] active tracks = " + TRACKS.size());
        System.out.println("[vmic] writing tracks = " + TRACKS.size());
        return !TRACKS.isEmpty();
    }

    static volatile boolean STOP = false;
    /** 自动滑动已废弃（用户要求只保留回声） */
    static volatile long lastSignalMs = 0;
    /** 供子进程采集线程读取的推子值 */
    static final double[] gainRef = new double[] { 1.0 };
    static volatile Process CAP_PROC = null;
    /** App 进程 pid，由启动命令传入；-1 = 不监控 */
    static volatile int APP_PID = -1;
    // ---- 山洞回声 ----
    static final short[] ECHO = new short[48000 * 2];
    static int echoPos = 0;
    static volatile boolean echoOn = true;
    static final int ECHO_DELAY = 14400;   // 300ms
    static volatile double ECHO_WET = 0.50;
    static volatile double ECHO_FB  = 0.30;
    /** 滑块数值文件：0~100，越大回声越重 */
    static volatile int ECHO_LEVEL = 50;

    static void audioPriority() {
        try { android.os.Process.setThreadPriority(-19); } catch (Throwable ignore) { }
    }

    /**
     * 退出清理，顺序照抄 MicBoost.cleanup()：
     *   停采集 -> pause+release 所有注入 track -> unregisterAudioPolicy
     * 不清理的话 policy 会一直劫持系统录音，录音机录到的全是静音。
     */
    static void closeRealtimeSocket(android.net.LocalSocket socket) {
        if (socket == null) return;
        try { socket.shutdownInput(); } catch (Throwable ignore) { }
        try { socket.shutdownOutput(); } catch (Throwable ignore) { }
        try { socket.close(); } catch (Throwable ignore) { }
    }

    static synchronized void cleanup() {
        STOP = true;
        android.net.LocalSocket progress = RT_PROGRESS_SOCKET; RT_PROGRESS_SOCKET = null;
        closeRealtimeSocket(progress);
        Thread progressThread = RT_PROGRESS_THREAD; RT_PROGRESS_THREAD = null;
        if (progressThread != null) progressThread.interrupt();
        android.net.LocalSocket capture = RT_SOCKET; RT_SOCKET = null;
        closeRealtimeSocket(capture);
        if (CAP_PROC != null) {
            Process child = CAP_PROC; CAP_PROC = null;
            try { child.getOutputStream().close(); child.destroy(); } catch (Throwable ignore) { }
            try { child.getInputStream().close(); } catch (Throwable ignore) { }
            try { child.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        for (Object t : TRACKS) {
            try { call(t, "pause", new Class<?>[0]); } catch (Throwable ignore) { }
            try { call(t, "release", new Class<?>[0]); } catch (Throwable ignore) { }
        }
        TRACKS.clear();
        W_TRACKS.clear();
        W_METHODS.clear();
        W_PROGRESS = new TrackProgress[0];
        if (POLICY != null && MANAGER != null) {
            try {
                Class<?> amCls = cls("android.media.AudioManager");
                Class<?> polCls = cls("android.media.audiopolicy.AudioPolicy");
                amCls.getMethod("unregisterAudioPolicy", polCls).invoke(MANAGER, POLICY);
                System.out.println("[vmic] unregisterAudioPolicy ok");
            } catch (Throwable t) {
                System.out.println("[vmic] unregister 失败: " + t);
            }
        }
        POLICY = null;
    }

    /** 收到 SIGTERM / Runtime.exit 时自动清理。App 侧必须用 kill(SIGTERM)，不能用 kill -9 */
    static void installCleanupHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override public void run() {
                    try { cleanup(); } catch (Throwable ignore) { }
                    System.out.println("[vmic] 清理完成，退出");
                }
            }));
        } catch (Throwable ignore) { }
    }



    /** 写入线程：队列空时 <350ms 重复上一块，>350ms 淡出填静音，绝不让 AudioTrack 欠载。 */
    static Thread newWriterThread(final ConcurrentLinkedQueue<byte[]> queue) {
        return new Thread(new Runnable() {
            @Override public void run() {
                audioPriority();
                final int N = 960;
                byte[] buf = new byte[N];
                long holds = 0, silences = 0, hookMs = 0;
                boolean got = false;
                while (!STOP) {
                    byte[] data = queue.poll();
                    long now = System.currentTimeMillis();
                    if (data != null) {
                        int len = Math.min(data.length, N);
                        System.arraycopy(data, 0, buf, 0, len);
                        if (len < N) java.util.Arrays.fill(buf, len, N, (byte) 0);
                        hookMs = 0;
                        got = true;
                    } else if (got) {
                        if (hookMs == 0) hookMs = now;
                        if (now - hookMs > 350) {
                            fadeOut(buf);
                            java.util.Arrays.fill(buf, (byte) 0);
                            silences++;
                        } else {
                            holds++;
                        }
                    } else {
                        try { Thread.sleep(5); } catch (InterruptedException ignore) { break; }
                        continue;
                    }
                    writeAllTracks(buf, 0, N);
                }
                System.out.println("[vmic] 写入结束 重复=" + holds + " 静音=" + silences
                        + " 写=" + (WRITE_OK / 96000) + "s 部分=" + WRITE_PARTIAL
                        + " 异常=" + WRITE_ERR);
            }
        });
    }

    static long WRITE_OK = 0;
    static long WRITE_PARTIAL = 0;
    static long WRITE_ERR = 0;
    static long WRITE_ZERO = 0;
    static String LAST_ERR = "-";
    static final List<Object> W_TRACKS = new ArrayList<Object>();
    static final List<Method> W_METHODS = new ArrayList<Method>();

    // A separate authenticated socket has exactly one writer. Keep this wire layout in sync
    // with com.svc.voice.VmicProgressWire; the helper build deliberately stays single-source.
    static final class ProgressWire {
        static final int MAGIC = 0x53565031, VERSION = 1, RATE = 48000, MAX_TRACKS = 8;
        static void hello(java.io.DataOutput out, int tracks) throws java.io.IOException {
            if (tracks < 1 || tracks > MAX_TRACKS) throw new java.io.IOException("progress tracks");
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(RATE); out.writeInt(tracks);
        }
        static void frame(java.io.DataOutput out, long seq, long[] written, long[] played,
                          int[] flags) throws java.io.IOException {
            int count = written.length;
            out.writeInt(1); out.writeInt(12 + 20 * count);
            out.writeLong(seq); out.writeInt(count);
            for (int i = 0; i < count; i++) {
                out.writeLong(written[i]); out.writeLong(played[i]); out.writeInt(flags[i]);
            }
        }
    }

    /** Pure counter: extend uint32 wraps, reject resets, never substitute writes for head motion. */
    static final class PlaybackCounter {
        long raw, total, played;
        boolean invalid;
        long sample(int value, long writtenFrames) {
            if (invalid) return played;
            long now = value & 0xffffffffL;
            long delta = (now - raw) & 0xffffffffL;
            if (delta > 0x7fffffffL || writtenFrames < played || Long.MAX_VALUE-total < delta) {
                invalid = true; return played;
            }
            raw = now; total += delta;
            // A temporarily head-ahead-of-write snapshot must not turn a later write into play.
            if (delta > 0) played = Math.max(played, Math.min(total, writtenFrames));
            return played;
        }
    }

    static final class TrackProgress {
        final Object track;
        final Method head;
        final PlaybackCounter counter = new PlaybackCounter();
        volatile long acceptedBytes;
        TrackProgress(Object track) {
            this.track = track;
            head = findMethod(track.getClass(), "getPlaybackHeadPosition", new Class<?>[0]);
        }
    }
    static volatile TrackProgress[] W_PROGRESS = new TrackProgress[0];
    static volatile android.net.LocalSocket RT_SOCKET, RT_PROGRESS_SOCKET;
    static volatile Thread RT_PROGRESS_THREAD;

    static void startProgress(final String name, final int appUid, final int appPid) {
        if (name == null) return; // Old callers have no progress channel; App displays unknown.
        final TrackProgress[] states = W_PROGRESS;
        Thread thread = new Thread(() -> {
            android.net.LocalSocket socket = new android.net.LocalSocket();
            RT_PROGRESS_SOCKET = socket;
            try {
                if (STOP) return;
                socket.connect(new android.net.LocalSocketAddress(name));
                android.net.Credentials peer = socket.getPeerCredentials();
                if (peer.getUid() != appUid || peer.getPid() != appPid)
                    throw new SecurityException("wrong progress app peer");
                socket.setSoTimeout(1500);
                java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
                ProgressWire.hello(out, states.length);
                long[] written = new long[states.length], played = new long[states.length];
                int[] flags = new int[states.length];
                long seq = 0;
                while (!STOP && !Thread.currentThread().isInterrupted()) {
                    for (int i = 0; i < states.length; i++) {
                        TrackProgress state = states[i];
                        flags[i] = 0;
                        try {
                            if (state.head == null) throw new NoSuchMethodException("playback head");
                            int raw = ((Number)state.head.invoke(state.track)).intValue();
                            // Read acceptedBytes AFTER head, bounding partial/blocking writes.
                            state.counter.sample(raw, state.acceptedBytes / 2);
                            if (!state.counter.invalid && state.counter.played > 0) flags[i] = 1;
                        } catch (Throwable unavailable) { /* this track is unknown, not estimated */ }
                        written[i] = state.acceptedBytes / 2;
                        played[i] = state.counter.played;
                    }
                    ProgressWire.frame(out, ++seq, written, played, flags); out.flush();
                    Thread.sleep(50);
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) { if (!STOP) System.err.println("[rt] progress unavailable: " + failure); }
            finally {
                closeRealtimeSocket(socket);
                if (RT_PROGRESS_SOCKET == socket) RT_PROGRESS_SOCKET = null;
            }
        }, "track-progress");
        thread.setDaemon(true); RT_PROGRESS_THREAD = thread; thread.start();
    }

    /** PCM is a byte stream: retain an odd byte across arbitrary socket read boundaries. */
    static final class PcmReader {
        final java.io.InputStream in;
        final byte[] bytes = new byte[3840];
        int pending;
        PcmReader(java.io.InputStream in) { this.in = in; }
        int read() throws java.io.IOException {
            for (;;) {
                int n = in.read(bytes, pending, bytes.length - pending);
                if (n < 0) {
                    if (pending != 0) throw new java.io.EOFException("truncated model PCM16");
                    return -1;
                }
                int total = pending + n;
                int aligned = total & ~1;
                pending = total - aligned;
                if (aligned > 0) return aligned;
            }
        }
        void consumed(int n) { if (pending != 0) bytes[0] = bytes[n]; }
    }

    /** 取所有 track 的 4 参数 write(byte[],int,int,int)，MicBoost 同样遍历全部写 */
    static void prepareWriter() {
        W_TRACKS.clear();
        W_METHODS.clear();
        for (Object t : TRACKS) {
            Method wm = null;
            for (Method m : t.getClass().getMethods()) {
                if (m.getName().equals("write")) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 4 && pt[0] == byte[].class) {
                        try { m.setAccessible(true); } catch (Throwable ignore) { }
                        wm = m;
                        break;
                    }
                }
            }
            if (wm != null) { W_TRACKS.add(t); W_METHODS.add(wm); }
        }
        TrackProgress[] states = new TrackProgress[W_TRACKS.size()];
        for (int i = 0; i < states.length; i++) states[i] = new TrackProgress(W_TRACKS.get(i));
        W_PROGRESS = states;
        System.out.println("[vmic] writer tracks=" + W_TRACKS.size());
    }

    /**
     * 写入所有 track，每步处理部分写入，直到写完或停止，不按重试次数丢弃。
     * 原来只写 track[0]：注册了 {1,7} 两条 mix，track[1] 永远没数据，
     * AudioFlinger 就一直重放它的旧缓冲 —— 这也是"重复"的来源之一。
     */
    static void writeAllTracks(byte[] b, int off, int len) {
        final TrackProgress[] progress = W_PROGRESS;
        for (int k = 0; k < W_TRACKS.size(); k++) {
            Object t = W_TRACKS.get(k);
            Method wm = W_METHODS.get(k);
            int done = 0;
            while (done < len && !STOP) {
                try {
                    Object r = wm.invoke(t, b, off + done, len - done, 0);  // WRITE_BLOCKING; retain remainder on short/zero writes
                    int n = ((Number) r).intValue();
                    if (n < 0) { WRITE_ERR++; LAST_ERR = "write=" + n; throw new IllegalStateException(LAST_ERR); }
                    // 返回 0 = track 暂时满，等 1ms 重试同一位置。
                    // 绝不能 break —— 那会丢掉这块剩下的音频，直接变成断音、杂音。
                    if (n == 0) { WRITE_ZERO++; try { Thread.sleep(1); } catch (InterruptedException e) { return; } continue; }
                    if (n > len - done) throw new IllegalStateException("oversized track write=" + n);
                    if (n < len - done) WRITE_PARTIAL++;
                    if (k < progress.length) progress[k].acceptedBytes += n;
                    done += n;
                } catch (Throwable e) {
                    WRITE_ERR++;
                    LAST_ERR = String.valueOf(e.getCause() != null ? e.getCause() : e);
                    throw new IllegalStateException(LAST_ERR, e);
                }
            }
            WRITE_OK += done;
        }
    }

    /** 最后 min(samples,2880) 个样本线性淡出，MicBoost 同款 */
    static void fadeOut(byte[] b) {
        int samples = b.length / 2;
        int n = Math.min(samples, 2880);
        for (int i = samples - n; i < samples; i++) {
            double g = (samples - i) / (double) n;
            int v = (short) (((b[2 * i + 1] & 0xff) << 8) | (b[2 * i] & 0xff));
            v = (int) Math.round(v * g);
            b[2 * i] = (byte) (v & 0xff);
            b[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
    }

    /**
     * 完整复刻 MicBoost 的 live：
     *   1. 先 openCapture + 起采集线程
     *   2. waitFirstSignal 确认真的采到东西
     *   3. 才 setupAudioPolicy（否则 policy 会把自己采集源劫持成全 0）
     *   4. 起写入线程
     */
    /** 把本进程设成不可被低内存杀手干掉。进程一死 policy 就泄漏在 audioserver 里，
     *  所有 App 的麦克风都会被导向没人写数据的空管道（微信直接报录音错误）。 */
    static void protectFromKill() {
        try {
            java.io.File f = new java.io.File("/proc/self/oom_score_adj");
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write("-1000\n".getBytes("UTF-8"));
            o.close();
        } catch (Throwable t) {
            System.out.println("[vmic] oom_adj 设置失败: " + t);
        }
        audioPriority();
    }


    // ================= 双进程：root 注册 policy，微信 uid 只采集 =================
    // 为什么必须两个进程：
    //   采集进程如果是 root，系统判定"麦克风被别的应用占用"，微信一录就失败；
    //   采集进程如果是微信 uid，又没有音频路由权限，createAudioTrackSource 返回 null。
    //   MicBoost 就是这么拆的：policy 归 root（日志里的 policy_keep=1），
    //   采集进程 HELPER_START uid=10422，采集源 9 避开自己注册的 preset 1/7。

    /** 只采集，原始 PCM 走 stdout（日志必须走 stderr，否则污染数据流） */
    static void cap(int src) throws Exception {
        System.err.println("[cap] start src=" + src + " uid=" + android.os.Process.myUid());
        audioPriority();
        // 父进程（root 注入进程）一死，stdin 管道就 EOF。
        // 靠这个退出，不留孤儿占着麦克风（否则 App 删了状态栏绿点还亮）。
        final java.io.InputStream pstdin = new java.io.FileInputStream(java.io.FileDescriptor.in);
        Thread dead = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] b = new byte[64];
                    while (pstdin.read(b) >= 0) { }
                } catch (Throwable ignore) { }
                System.err.println("[cap] 父进程已退出，采集结束");
                System.exit(0);
            }
        }, "cap-parent-watch");
        dead.setDaemon(true);
        dead.start();
        Class<?> arCls = cls("android.media.AudioRecord");
        Object rec = null;
        Method readM = null;
        int used = -1;
        if (src != 6) throw new IllegalArgumentException("capture requires source 6; injection uses 1/7");
        int[] cands = { 6 };
        for (int c : cands) {
            try {
                Object mb = arCls.getMethod("getMinBufferSize", int.class, int.class, int.class)
                        .invoke(null, 48000, 0x10, 0x2);
                int minBuf = ((Integer) mb).intValue();
                int bsz = Math.max(minBuf, 960 * 4);
                if (bsz <= 0) bsz = 960 * 8;
                Object r = arCls.getConstructor(int.class, int.class, int.class, int.class, int.class)
                        .newInstance(c, 48000, 0x10, 0x2, bsz);
                int st = ((Integer) arCls.getMethod("getState").invoke(r)).intValue();
                if (st != 1) { arCls.getMethod("release").invoke(r); System.err.println("[cap] src " + c + " state=" + st); continue; }
                readM = arCls.getMethod("read", byte[].class, int.class, int.class, int.class);
                arCls.getMethod("startRecording").invoke(r);
                rec = r; used = c;
                System.err.println("[cap] capture src=" + c + " bufSize=" + bsz);
                break;
            } catch (Throwable t) {
                System.err.println("[cap] src " + c + " 打不开: " + t);
            }
        }
        if (rec == null) { throw new java.io.IOException("all capture sources failed"); }

        java.io.OutputStream out = new java.io.FileOutputStream(java.io.FileDescriptor.out);
        byte[] buf = new byte[960];
        long blocks = 0;
        final Object ownedRecord = rec;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { call(ownedRecord,"stop",new Class<?>[0]); } catch(Throwable ignored) {}
            try { call(ownedRecord,"release",new Class<?>[0]); } catch(Throwable ignored) {}
        }));
        while (true) {
            int n = ((Integer) readM.invoke(rec, buf, 0, 960, 1)).intValue();
            if (n < 0) throw new java.io.IOException("AudioRecord.read="+n);
            if (n == 0) { try { Thread.sleep(5); } catch (InterruptedException ignore) { } continue; }
            if ("1".equals(System.getenv("SVC_REALTIME"))) {
                Object cfg=arCls.getMethod("getActiveRecordingConfiguration").invoke(rec);
                if(cfg==null)throw new java.io.IOException("recording configuration unavailable");
                if(Boolean.TRUE.equals(cfg.getClass().getMethod("isClientSilenced").invoke(cfg)))
                    throw new java.io.IOException("system silenced capture");
            }
            try { out.write(buf, 0, n); out.flush(); } catch (Throwable t) { break; }
            if (++blocks % 6000 == 0) System.err.println("[cap] " + (blocks / 100) + "s src=" + used);
        }
        System.err.println("[cap] end src=" + used);
    }

    /** 用目标 App 的 uid 起采集子进程，读它的 stdout */
    static Process startCapChild(long uid, int src) throws Exception {
        String cp = System.getenv("CLASSPATH");
        if (cp == null || cp.length() == 0) cp = System.getProperty("java.class.path");
        String cmd = "CLASSPATH=" + cp + " /system/bin/app_process /system/bin VmicPolicy cap " + src;
        System.err.println("[vmic] 起采集子进程 uid=" + uid + " src=" + src);
        ProcessBuilder pb = new ProcessBuilder("/system/bin/su", String.valueOf(uid), "-c", cmd);
        if (RT_CAPTURE) pb.environment().put("SVC_REALTIME","1");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);   // 子进程日志直接进同一个文件
        return pb.start();
    }

    /** 从子进程 stdout 读 PCM，处理后入队 */
    static Thread newCaptureFromStream(final Process proc, final ConcurrentLinkedQueue<byte[]> queue) {
        return new Thread(new Runnable() {
            @Override public void run() {
                audioPriority();
                byte[] buf = new byte[960];
                long reads = 0;
                try {
                    java.io.InputStream in = proc.getInputStream();
                    while (true) {
                        int n = 0;
                        try { n = in.read(buf, 0, 960); } catch (Throwable t) { break; }
                        if (n <= 0) break;
                        reads++;
                        for (int i = 0; i < n; i++) {
                            if (buf[i] != 0) { lastSignalMs = System.currentTimeMillis(); break; }
                        }
                        byte[] out = new byte[n];
                        // 不缩放，原样拷贝；效果只在下面的回声里做
                        System.arraycopy(buf, 0, out, 0, n);
                        // ---- 山洞回声（唯一效果）----
                        if (echoOn) {
                            int L = ECHO.length;
                            double lv = ECHO_LEVEL / 100.0;       // 0~1
                            double wetK = 0.15 + lv * 0.75;        // 15%~90%
                            double fbK  = 0.05 + lv * 0.70;        // 5%~75%
                            for (int i = 0; i + 1 < n; i += 2) {
                                int dry = (short) (((out[i + 1] & 0xff) << 8) | (out[i] & 0xff));
                                int rd = echoPos - ECHO_DELAY;
                                if (rd < 0) rd += L;
                                int wetS = ECHO[rd];
                                int mix = dry + (int) (wetS * wetK);
                                if (mix > 32767) mix = 32767; else if (mix < -32768) mix = -32768;
                                int fb = dry + (int) (wetS * fbK);
                                if (fb > 32767) fb = 32767; else if (fb < -32768) fb = -32768;
                                ECHO[echoPos] = (short) fb;
                                echoPos++; if (echoPos >= L) echoPos = 0;
                                out[i] = (byte) (mix & 0xff);
                                out[i + 1] = (byte) ((mix >> 8) & 0xff);
                            }
                        }
                        while (queue.size() > 8) queue.poll();
                        queue.offer(out);
                    }
                    System.err.println("[vmic] 采集子进程输出结束 reads=" + reads);
                } catch (Throwable t) {
                    System.err.println("[vmic] 读子进程失败: " + t);
                }
            }
        });
    }

    static void mic(String gainFile, int src, long targetUid) throws Exception {
        protectFromKill();
        java.io.File gf = new java.io.File(gainFile);
        final double[] gain = new double[] { 1.0 };
        try {
            java.io.RandomAccessFile f = new java.io.RandomAccessFile(gf, "r");
            gain[0] = Double.parseDouble(f.readLine().trim()) / 100.0;
            gainRef[0] = gain[0];
            f.close();
        } catch (Throwable ignore) { }

        installCleanupHook();

        // ★ 和 App 生命周期绑定。
        //   从后台划掉 App 只会杀进程，不会走 onDestroy，helper 用 nohup+setsid 又完全脱离了
        //   App 进程树，结果就是"后台删了，麦克风还占着，绿点一直亮"。
        //   所以反过来盯 App 的 pid：它没了就自己走 cleanup 退出。
        final int appPid = APP_PID;
        if (appPid > 1) {
            Thread guard = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                        if (!new java.io.File("/proc/" + appPid).exists()) {
                            System.err.println("[vmic] App 进程 " + appPid + " 已消失，自行退出");
                            try { cleanup(); } catch (Throwable ignore) { }
                            try {
                                Runtime.getRuntime().exec(new String[] { "/system/bin/sh", "-c",
                                    "true" }).waitFor();
                            } catch (Throwable ignore) { }
                            System.exit(0);
                        }
                    }
                }
            }, "app-watch");
            guard.setDaemon(true);
            guard.start();
        }

        ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<byte[]>();

        // 1. 先起采集
        //    targetUid > 0 时用「目标 App 的 uid」起子进程采集：
        //    这样系统认为录音的是微信本人，不会报"被别的应用占用"。
        //    policy 仍由当前 root 进程注册（微信 uid 没权限建注入通道）。
        long capUid = targetUid > 0 ? targetUid : android.os.Process.myUid();
        CAP_PROC = startCapChild(capUid, src);
        Thread cap = newCaptureFromStream(CAP_PROC, queue);
        cap.start();

        // 2. 等第一次真实信号，最多 2 秒
        long t0 = System.currentTimeMillis();
        boolean ok = false;
        while (System.currentTimeMillis() - t0 < 2000) {
            if (System.currentTimeMillis() - lastSignalMs < 200) { ok = true; break; }
            Thread.sleep(50);
        }
        System.out.println("[vmic] CAP_PRIME ok=" + ok
                + " silent=" + (System.currentTimeMillis() - lastSignalMs) + "ms");

        // 3. 确认有信号后才注册 policy
        if (ok) {
            // ★ 只注册 preset=7（VOICE_COMMUNICATION），照 MicBoost：传 7 时 presets={7}。
            //   注册 preset=1(MIC) 会把所有用 MIC 的 App（录音机/相机/微信语音消息）
            //   的采集通通劫持到我们的 submix —— 那就是"录音机录到静音、微信报录音错误"。
            // ★ 同时用白名单，只对目标 App 的 uid 生效。
            // ★ 照 MicBoost：微信语音消息走 MIC(1)、语音通话走 VOICE_COMMUNICATION(7)，
            //   两个都要注册，否则微信根本收不到注入。
            //   代价是同用 MIC 的 App 也会被拦；所以采集端用 6(VOICE_RECOGNITION)
            //   避开这两条规则，否则自己采到自己。
            if (setup(new int[] { 1, 7 }, 0, -1)) {
                System.out.println("[vmic] POLICY_OK");
                prepareWriter();
            } else {
                System.out.println("[vmic] POLICY_FAIL");
            }
        } else {
            System.out.println("[vmic] POLICY_DEFERRED waiting_for_capture=1");
        }

        // 4. 写入线程
        Thread wr = newWriterThread(queue);
        wr.start();

        // 每秒刷一次推子 + 打印状态
        long lastGainMs = 0, lastLog = 0;
        while (!STOP) {
            Thread.sleep(500);
            long now = System.currentTimeMillis();
            if (now - lastGainMs > 1000) {
                lastGainMs = now;
                try {
                    java.io.RandomAccessFile f = new java.io.RandomAccessFile(gf, "r");
                    double g = Double.parseDouble(f.readLine().trim()) / 100.0;
                    f.close();
                    if (g != gain[0]) { gain[0] = g; gainRef[0] = g; System.out.println("[vmic] gain -> " + g); }
                } catch (Throwable ignore) { }
                // sweep 文件在 = 自动滑动
                try {
                    java.io.RandomAccessFile ef = new java.io.RandomAccessFile(
                            new java.io.File(gf.getParentFile(), "echo.txt"), "r");
                    int lv = Integer.parseInt(ef.readLine().trim());
                    ef.close();
                    if (lv < 0) lv = 0; if (lv > 100) lv = 100;
                    if (lv != ECHO_LEVEL) { ECHO_LEVEL = lv; System.out.println("[vmic] 回声强度 -> " + lv); }
                } catch (Throwable ignore) { }
                boolean e = new java.io.File(gf.getParentFile(), "echo").exists();
                if (e != echoOn) {
                    echoOn = e;
                    echoPos = 0;
                    java.util.Arrays.fill(ECHO, (short) 0);
                    System.out.println("[vmic] 回声 " + (e ? "开" : "关"));
                }
            }
            long sec = WRITE_OK / 96000;
            if (sec != lastLog) {
                lastLog = sec;
                System.out.println("[vmic] out " + sec + "s 写=" + (WRITE_OK / 96000)
                        + "s 部分=" + WRITE_PARTIAL + " 零=" + WRITE_ZERO
                        + " 异常=" + WRITE_ERR + " 队列=" + queue.size()
                        + (WRITE_ERR > 0 ? " err=" + LAST_ERR : ""));
            }
        }
    }

    static void tone(int ms) throws Exception {
        int total = 48000 * ms / 1000;
        byte[] buf = new byte[1920];
        double ph = 0;
        int done = 0;
        while (done < total) {
            int n = Math.min(960, total - done);
            for (int i = 0; i < n; i++) {
                short v = (short) (Math.sin(ph) * 14000);
                ph += 2 * Math.PI * 440.0 / 48000.0;
                buf[2 * i] = (byte) (v & 0xff);
                buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
            }
            writeAllTracks(buf, 0, n * 2);
            done += n;
            if (done % 24000 == 0) Thread.sleep(2);
        }
    }

    static void record(String path, int ms) throws Exception { record(path, ms, 7); }

    static void record(String path, int ms, int src) throws Exception {
        Class<?> arc = cls("android.media.AudioRecord");
        Constructor<?> c = arc.getConstructor(int.class, int.class, int.class, int.class, int.class);
        Object ar = c.newInstance(src, 48000, 16, 2, 1920 * 16);
        call(ar, "startRecording", new Class<?>[] { });
        int total = 48000 * ms / 1000;
        int dataBytes = total * 2;
        FileOutputStream fo = new FileOutputStream(path);
        fo.write(wavHeader(dataBytes, 48000, 1, 16));
        byte[] buf = new byte[1920];
        int got = 0;
        while (got < dataBytes) {
            Object n = call(ar, "read", new Class<?>[] { byte[].class, int.class, int.class }, buf, 0, Math.min(1920, dataBytes - got));
            int k = asInt(n);
            if (k <= 0) break;
            fo.write(buf, 0, k);
            got += k;
        }
        fo.close();
        call(ar, "stop", new Class<?>[] { });
        call(ar, "release", new Class<?>[] { });
        System.out.println("[vmic] recorded " + got);
    }

    static byte[] wavHeader(int dataLen, int sr, int ch, int bits) {
        int byteRate = sr * ch * bits / 8;
        int blockAlign = ch * bits / 8;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); putI(h, 4, 36 + dataLen); put(h, 8, "WAVE");
        put(h, 12, "fmt "); putI(h, 16, 16); putS(h, 20, (short) 1);
        putS(h, 22, (short) ch); putI(h, 24, sr); putI(h, 28, byteRate);
        putS(h, 32, (short) blockAlign); putS(h, 34, (short) bits);
        put(h, 36, "data"); putI(h, 40, dataLen);
        return h;
    }

    static void put(byte[] h, int o, String s) {
        for (int i = 0; i < s.length(); i++) h[o + i] = (byte) s.charAt(i);
    }
    static void putI(byte[] h, int o, int v) {
        h[o] = (byte) v; h[o + 1] = (byte) (v >> 8); h[o + 2] = (byte) (v >> 16); h[o + 3] = (byte) (v >> 24);
    }
    static void putS(byte[] h, int o, short v) {
        h[o] = (byte) v; h[o + 1] = (byte) (v >> 8);
    }

    static boolean RT_CAPTURE=false;
    static void realtime(String socket, long uid, int appUid, int appPid, String progressName) throws Exception {
        APP_PID=appPid; STOP=false; RT_CAPTURE=true;
        installCleanupHook();
        protectFromKill();
        android.net.LocalSocket ipc=new android.net.LocalSocket();
        RT_SOCKET=ipc;
        try {
            ipc.connect(new android.net.LocalSocketAddress(socket));
            android.net.Credentials peer=ipc.getPeerCredentials();
            if(peer.getUid()!=appUid || peer.getPid()!=appPid) throw new SecurityException("wrong app peer");
            java.io.DataOutputStream status=new java.io.DataOutputStream(ipc.getOutputStream());
            status.writeInt(0x53564337); status.writeInt((int)uid); status.flush();
            System.err.println("[rt] stage=cap-start uid="+uid);
            CAP_PROC=startCapChild(uid,6);
            final Process child=CAP_PROC;
            Thread exitWatch=new Thread(()->{try{System.err.println("[rt] cap exit="+child.waitFor());}catch(InterruptedException e){Thread.currentThread().interrupt();}},"cap-exit");exitWatch.setDaemon(true);exitWatch.start();
            final java.io.InputStream raw=CAP_PROC.getInputStream();
            final java.util.concurrent.LinkedBlockingQueue<byte[]> q=new java.util.concurrent.LinkedBlockingQueue<>();
            Thread pump=new Thread(() -> {
                try {byte[] b=new byte[960]; int n; while(!STOP && (n=raw.read(b))>0) q.add(java.util.Arrays.copyOf(b,n));}
                catch(Throwable t){System.err.println(t);}
                finally {q.add(new byte[0]);}
            },"raw-pcm"); pump.setDaemon(true);pump.start();
            java.io.DataOutputStream out=new java.io.DataOutputStream(ipc.getOutputStream());
            try {
                System.err.println("[rt] stage=prime");
                byte[] first=q.poll(6,java.util.concurrent.TimeUnit.SECONDS);
                if(first==null)throw new java.io.IOException("capture PCM timeout (6s)");
                if(first.length==0)throw new java.io.EOFException("cap startup EOF exit="+(CAP_PROC.waitFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)?CAP_PROC.exitValue():"still alive"));
                // A complete PCM16 frame is sufficient; amplitude is diagnostic only.
                java.util.ArrayList<byte[]> prime=new java.util.ArrayList<>(); prime.add(first);
                int bytes=first.length;
                while(bytes<2){byte[] next=q.poll(1,java.util.concurrent.TimeUnit.SECONDS);
                    if(next==null||next.length==0)throw new java.io.EOFException("incomplete PCM16 frame");
                    prime.add(next);bytes+=next.length;}
                if(!CAP_PROC.isAlive())throw new java.io.IOException("cap exited="+CAP_PROC.exitValue());
                boolean signal=false;for(byte[] block:prime)for(byte v:block)if(v!=0)signal=true;
                System.err.println("[rt] valid PCM bytes="+bytes+" nonzero="+signal);
                System.err.println("[rt] stage=policy excludeUid=0 presets=1,7");
                if(!setup(new int[]{1,7},0,-1))throw new java.io.IOException("policy registration/tracks failed");
                prepareWriter();
                if(W_TRACKS.size()!=TRACKS.size()||W_TRACKS.isEmpty())throw new java.io.IOException("prepareWriter missing write methods");
                if(!CAP_PROC.isAlive()||q.peek()!=null&&q.peek().length==0)throw new java.io.IOException("cap exited during policy setup");
                out.writeInt(1);out.writeUTF("STARTED");out.flush();
                System.err.println("[rt] stage=STARTED");
                startProgress(progressName, appUid, appPid);
                for(byte[] block:prime){out.write(block);out.flush();}
            } catch(Throwable failure) {
                try{out.writeInt(-1);out.writeUTF(failure.toString());out.flush();}catch(Throwable ignored){}
                throw failure;
            }
            Thread writer=new Thread(() -> {
                try {PcmReader in=new PcmReader(ipc.getInputStream());int n;
                    while(!STOP&&(n=in.read())>0){writeAllTracks(in.bytes,0,n);in.consumed(n);}
                } catch(Throwable t){System.err.println(t);}
                finally {STOP=true;closeRealtimeSocket(ipc);cleanup();}
            },"model-inject");writer.setDaemon(true);writer.start();
            while(!STOP){byte[] b=q.poll(3,java.util.concurrent.TimeUnit.SECONDS);
                if(b==null||b.length==0)throw new java.io.EOFException("capture timeout/EOF");
                out.write(b);out.flush();
            }
        } finally {closeRealtimeSocket(ipc);cleanup();}
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "selftest";
        if (!mode.equals("cap")) System.out.println("=== VmicPolicy " + mode + " start ===");
        if (mode.equals("cap")) {
            // cap <采集源>：只采集，PCM 走 stdout。由 root 用 su <目标uid> 拉起。
            int csrc = args.length > 1 ? Integer.parseInt(args[1]) : 6;
            try { cap(csrc); } catch (Throwable t) { t.printStackTrace(System.err); System.exit(1); }
            return;
        }
        if(mode.equals("rt")){
            try{realtime(args[1],Long.parseLong(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),args.length>5?args[5]:null);}
            catch(Throwable t){t.printStackTrace();cleanup();System.exit(1);} finally{cleanup();} return;
        }
        if (mode.equals("mic")) {
            // mic <增益文件> <采集源> <排除uid>
            String gf = args.length > 1 ? args[1] : "/data/local/tmp/svcvmic/gain.txt";
            int src = args.length > 2 ? Integer.parseInt(args[2]) : 6;
            long tgt = args.length > 3 ? Long.parseLong(args[3]) : -1;
            int exUid = args.length > 3 ? Integer.parseInt(args[3]) : -1;
            // mic <gain> <src> <targetUid> <appPid>
            APP_PID = args.length > 4 ? Integer.parseInt(args[4]) : -1;
            try {
                mic(gf, src, tgt);   // 内部自己掌握「先采集→确认信号→再注册 policy」的顺序
            } catch (Throwable t) {
                System.out.println("[vmic] mic 失败: " + t);
                t.printStackTrace();
            }
            return;
        }
        try {
            if ("selftest".equals(mode)) {
                String out = args.length > 1 ? args[1] : "/data/local/tmp/vmic_test/rec.wav";
                if (!setup(new int[] { 7 })) { System.out.println("[vmic] FAIL"); return; }
                tone(500);
                final String fout = out;
                Thread rec = new Thread(new Runnable() {
                    public void run() {
                        try { record(fout, 3000); } catch (Throwable t) { t.printStackTrace(); }
                    }
                });
                rec.start();
                Thread.sleep(400);
                tone(2000);
                rec.join(6000);
                System.out.println("[vmic] selftest done");
            } else if ("verify".equals(mode)) {
                // verify <wav> <preset> <recSource>
                String out = args.length > 1 ? args[1] : "/data/local/tmp/vmic_test/v.wav";
                int preset = args.length > 2 ? Integer.parseInt(args[2]) : 1;
                final int rsrc = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                if (!setup(new int[] { preset })) { System.out.println("[vmic] FAIL"); return; }
                tone(400);
                final String fout = out;
                Thread rec = new Thread(new Runnable() {
                    public void run() {
                        try { record(fout, 3000, rsrc); } catch (Throwable t) { t.printStackTrace(); }
                    }
                });
                rec.start();
                Thread.sleep(400);
                tone(2000);
                rec.join(6000);
                System.out.println("[vmic] verify done preset=" + preset + " recSrc=" + rsrc);
            } else if ("feed".equals(mode)) {
                final String fifoPath = args.length > 1 ? args[1] : "/data/local/tmp/vmic_test/cap.fifo";
                String fifo = fifoPath;
                String ps = args.length > 2 ? args[2] : "1,7";
                int excludeUid = args.length > 3 ? Integer.parseInt(args[3].trim()) : -1;
                APP_PID = args.length > 4 ? Integer.parseInt(args[4]) : -1;
                installCleanupHook();
                Thread parentGuard = new Thread(() -> {
                    while (!STOP) {
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                        if (APP_PID > 1 && !new java.io.File("/proc/" + APP_PID).exists()) System.exit(0);
                    }
                }, "feed-parent-watch");
                parentGuard.setDaemon(true); parentGuard.start();
                String[] parts = ps.split(",");
                int[] presets = new int[parts.length];
                for (int i = 0; i < parts.length; i++) presets[i] = Integer.parseInt(parts[i].trim());
                if (!setup(presets, excludeUid)) { System.out.println("[vmic] FAIL register"); return; }
                System.out.println("[vmic] feeding " + fifo);

                // ---- 极简转发 ----
                // App 端串行处理：凑够一块 → 编码 → 解码 → 整块写 FIFO → 再凑下一块。
                // 没处理完的时候 FIFO 是空的，这里自然阻塞在 read 上，声卡就在播放静音，
                // 也就是"中间间隔空着"。完全不丢、不攒、不重排。
                // 阻塞写本身以声卡速度前进，这就是唯一需要的时钟。
                // 调试：把真正写进 track 的数据落盘，用于分析波形
                java.io.FileOutputStream dbg = null;
                try {
                    dbg = new java.io.FileOutputStream("/data/local/tmp/svcvmic/feed.pcm");
                    System.out.println("[vmic] dump -> /data/local/tmp/svcvmic/feed.pcm");
                } catch (Throwable t) { }

                prepareWriter();

                byte[] buf = new byte[3840];
                long fed = 0, lastLog = 0;
                while (!STOP) {
                    FileInputStream in = null;
                    try {
                        in = new FileInputStream(fifoPath);
                        System.out.println("[vmic] fifo opened");
                        int k;
                        while (!STOP && (k = in.read(buf)) > 0) {
                            if (dbg != null && fed < 96000 * 20) {
                                try { dbg.write(buf, 0, k); } catch (Throwable ignore) { }
                            }
                            writeAllTracks(buf, 0, k);
                            fed += k;
                            long sec = fed / 96000;
                            if (sec != lastLog) {
                                lastLog = sec;
                                System.out.println("[vmic] out " + sec + "s"
                                        + " 写=" + (WRITE_OK / 96000) + "s"
                                        + " 部分=" + WRITE_PARTIAL
                                        + " 零=" + WRITE_ZERO
                                        + " 异常=" + WRITE_ERR
                                        + (WRITE_ERR > 0 ? " err=" + LAST_ERR : ""));
                            }
                        }
                        System.out.println("[vmic] fifo eof, reopen");
                    } catch (Throwable t) {
                        t.printStackTrace(); cleanup(); return;
                    } finally {
                        if (in != null) try { in.close(); } catch (Throwable ignore) { }
                    }
                }
            } else {
                System.out.println("unknown mode " + mode);
            }
        } catch (Throwable t) {
            System.out.println("[vmic] EXCEPTION " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("=== VmicPolicy exit ===");
    }
}