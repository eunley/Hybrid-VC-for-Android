package com.svc.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;

/** 设置与目录 */
public final class Prefs {
    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("svc", Context.MODE_PRIVATE);
        if(sp.getInt("rt_chunk_core_version",0)<1)sp.edit().putInt("rt_frames",26).putInt("rt_xfade",2).putInt("rt_chunk_core_version",1).apply();
    }

    /** 优先 /sdcard/SVCVoice，无权限则退到应用私有目录 */
    public File baseDir() {
        File ext = new File(Environment.getExternalStorageDirectory(), "SVCVoice");
        if ((android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
                && (ext.isDirectory() || ext.mkdirs()) && ext.canWrite()) return ext;
        File f = new File(ctx.getFilesDir(), "SVCVoice");
        f.mkdirs();
        return f;
    }

    public String httpToken(){return sp.getString("http_token", "");}
    public void setHttpToken(String token){sp.edit().putString("http_token",token).commit();}
    public boolean httpEnabled(){return sp.getBoolean("http_enabled",false);}
    public int httpPort(){return sp.getInt("http_port",16180);}
    public boolean httpLocalOnly(){return sp.getBoolean("http_local_only",true);}
    public void setHttp(boolean enabled,int port,boolean local){
        if(port<1||port>65535)throw new IllegalArgumentException("端口范围 1..65535");
        sp.edit().putBoolean("http_enabled",enabled).putInt("http_port",port).putBoolean("http_local_only",local).commit();
    }

    public boolean debugMode(){return sp.getBoolean("debug_mode",false);}
    public void setDebugMode(boolean value){sp.edit().putBoolean("debug_mode",value).apply();}

    public String device() {return sp.getString("device", "cpu");}
    public void setDevice(String value) {sp.edit().putString("device",value).apply();}

    public String modelsDir() {
        return new File(baseDir(), "models").getAbsolutePath();
    }

    public String rolesDir() {
        File f = new File(baseDir(), "roles");
        f.mkdirs();
        return f.getAbsolutePath();
    }

    public String outPath() {
        String c = sp.getString("out_path", "");
        return c == null ? "" : c;
    }

    public void setOutPath(String p) { sp.edit().putString("out_path", p).apply(); }

    /** 推理线程数 1~8，默认 6（Engine 建 SessionOptions 时用） */
    public int cpuThreads() {
        int v = sp.getInt("cpu_threads", Tune.THREADS_DEF);
        return Math.max(Tune.THREADS_MIN, Math.min(Tune.THREADS_MAX, v));
    }

    public void setCpuThreads(int v) {
        sp.edit().putInt("cpu_threads",
                Math.max(Tune.THREADS_MIN, Math.min(Tune.THREADS_MAX, v))).apply();
    }

    /** 使用核心位掩码：bit i = 核心 i，默认 0xFF（0~7 全选），至少 1 位 */
    public int cpuMask() {
        int m = sp.getInt("cpu_mask", Tune.MASK_DEF) & Tune.MASK_DEF;
        return m == 0 ? Tune.MASK_DEF : m;
    }

    public void setCpuMask(int m) {
        m &= Tune.MASK_DEF;
        if (m == 0) m = Tune.MASK_DEF;
        sp.edit().putInt("cpu_mask", m).apply();
    }

    public String outDir() {
        String c = outPath();
        File f = (c.startsWith("/")) ? new File(c) : new File(baseDir(), "out");
        if (!f.exists()) f.mkdirs();
        return f.getAbsolutePath();
    }

    public String roleDir() {
        String r = sp.getString("role", "");
        if (r == null || r.isEmpty() || !new File(r).isDirectory()) {
            File d = new File(rolesDir());
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs)
                if (new File(f, "dec.onnx").exists()) { r = f.getAbsolutePath(); break; }
        }
        return r == null ? "" : r;
    }

    public void setRoleDir(String dir) { sp.edit().putString("role", dir).apply(); }

    /** 实时模式：目标块长（帧），实际会吸附到角色已有的 enc_* */
    public boolean fileVolumeLimit(){return sp.getBoolean("file_volume_limit",false);}
    public boolean liveVolumeLimit(){return sp.getBoolean("live_volume_limit",false);}
    public void setVolumeLimit(boolean live,boolean enabled){sp.edit().putBoolean(live?"live_volume_limit":"file_volume_limit",enabled).apply();}
    public boolean fileDenoise(){return sp.getBoolean("file_denoise",false);}
    public boolean liveDenoise(){return sp.getBoolean("live_denoise",false);}
    public void setDenoise(boolean live,boolean enabled){sp.edit().putBoolean(live?"live_denoise":"file_denoise",enabled).apply();}
    public int livePitchSemitones(){return Math.max(-24,Math.min(24,sp.getInt("live_pitch_semitones",0)));}
    public void setMonitorEnabled(boolean enabled){sp.edit().putBoolean("live_monitor",enabled).apply();}
    public boolean monitorEnabled(){return sp.getBoolean("live_monitor",false);}
    public int filePitchSemitones() { return Math.max(-24,Math.min(24,sp.getInt("file_pitch_semitones",0))); }
    public int rtBufferMs() { return Math.max(10,Math.min(1500,framesToMs(rtXfade()))); }
    public int rtFrames() { return clampF(sp.getInt("rt_frames", 26)); }
    public void setRtFrames(int f) { sp.edit().putInt("rt_frames", clampF(f)).apply(); }
    public void setFileFrames(int f) { sp.edit().putInt("file_frames", clampF(f)).apply(); }
    public void setRtXfade(int f) { sp.edit().putInt("rt_xfade", clampF(f)).apply(); }
    public void setFileXfade(int f) { sp.edit().putInt("file_xfade", clampF(f)).apply(); }
    /** 实时模式：分片缓冲（帧） */
    public int rtXfade() { return clampF(sp.getInt("rt_xfade", 2)); }
    /** 文件模式：目标块长（帧） */
    public int fileFrames() { return clampF(sp.getInt("file_frames", 256)); }
    /** 文件模式：分片缓冲（帧） */
    public int fileXfade() { return clampF(sp.getInt("file_xfade", 16)); }

    public void set(String k, int v) { sp.edit().putInt(k, v).apply(); }

    /** 回声强度 0~100 */
    public int echoLevel() { return sp.getInt("echo_level", 50); }
    public void setEchoLevel(int v) { sp.edit().putInt("echo_level", v).apply(); }

    /** 自动滑动：增益在 0~200% 之间自动往返，方便录一段听变化 */
    public boolean autoSweep() { return sp.getBoolean("auto_sweep", false); }
    public void setAutoSweep(boolean b) { sp.edit().putBoolean("auto_sweep", b).apply(); }

    /** 显式 UID 优先；未配置时沿用默认微信包名解析。 */
    public int resolveTargetUid() {
        Integer explicit = null;
        try {
            if (sp.contains("target_uid")) explicit = sp.getInt("target_uid", -1);
        } catch (ClassCastException e) {
            throw new IllegalStateException("目标 UID 配置格式错误", e);
        }
        return TargetUidResolver.resolve(explicit, new TargetUidResolver.Packages() {
            public int defaultUid() throws Exception {
                return ctx.getPackageManager().getApplicationInfo("com.tencent.mm", 0).uid;
            }
            public boolean exists(int uid) {
                String[] names = ctx.getPackageManager().getPackagesForUid(uid);
                return names != null && names.length > 0;
            }
        });
    }
    public int targetUid() { return resolveTargetUid(); }
    public void setTargetUid(int uid) { sp.edit().putInt("target_uid", uid).apply(); }

    /** 直通测试：麦克风 -> 推子 -> 注入，完全不经过模型 */
    public boolean bypass() { return sp.getBoolean("bypass", true); }
    public void setBypass(boolean b) { sp.edit().putBoolean("bypass", b).apply(); }

    /** 音量推子（滑动变阻器）：0~200 表示 0%~200% */
    public int pushGain() {
        int v = sp.getInt("push_gain", 100);
        return Math.max(0, Math.min(200, v));
    }
    public void setPushGain(int v) {
        v = Math.max(0, Math.min(200, v));
        sp.edit().putInt("push_gain", v).apply();
    }

    /** 实时：是否把处理结果注入系统麦克风（root），让微信/QQ 收到变声 */
    public boolean inject() { return sp.getBoolean("inject", true); }
    public void setInject(boolean b) { sp.edit().putBoolean("inject", b).apply(); }

    /** 钳到 100ms~30s 对应的帧数，挡住历史坏值 */
    private static int clampF(int f) {
        int lo = msToFrames(50), hi = msToFrames(30000);
        return Math.max(lo, Math.min(hi, f));
    }

    public static int msToFrames(int ms) {
        return Math.max(1, (int) Math.round(ms / 1000.0 * Engine.SR_OUT / Engine.HOP));
    }

    public static int framesToMs(int f) {
        return (int) Math.round(f * Engine.HOP * 1000.0 / Engine.SR_OUT);
    }
}