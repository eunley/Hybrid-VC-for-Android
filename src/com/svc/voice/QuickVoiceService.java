package com.svc.voice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 快捷录音：独立的 microphone 前台服务，只负责「麦克风 -> m4a」。
 *
 * 约束（与悬浮菜单/JobService 的契约）：
 *  - 只在悬浮菜单可见时由用户明确点击才会启动，服务自身不主动触发；
 *  - 录音结束（ACTION_STOP）把文件显式交给 JobService 做文件变声，
 *    成功结果由 JobService.quick* 静态字段 + 包内广播回传，本服务不做推理、
 *    也不播放；
 *  - ACTION_ABORT（例如浮窗服务被关闭）停止录音并删文件，不自动转；
 *  - 与实时变声 / 文件转换互斥：Busy 被占或 RealtimeService 在跑时拒绝启动；
 *  - 任何异常都要在 onDestroy 兜底清理 MediaRecorder，绝不留下占着麦克风的实例。
 */
public final class QuickVoiceService extends Service {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(I18n.wrap(base)); }


    public static final String ACTION_START = "com.svc.voice.QUICK_REC_START";
    /** 结束录音并送 JobService 转换 */
    public static final String ACTION_STOP = "com.svc.voice.QUICK_REC_STOP";
    /** 结束录音并丢弃（关浮窗时用） */
    public static final String ACTION_ABORT = "com.svc.voice.QUICK_REC_ABORT";

    private static final String CHANNEL = "quick_voice";
    private static final int NOTIFICATION = 4813;

    /** 正在录音（UI 据此切换按钮文字与互斥判断） */
    public static volatile boolean recording = false;
    /** 一句话状态，给悬浮菜单展示 */
    public static volatile String status = "";
    /** 最近一次提交给 JobService 的 token，0=还没提交过。UI 用它匹配回传结果。 */
    public static volatile long lastToken = 0L;
    /** 本次录音起点（uptimeMillis），0=未在录 */
    public static volatile long startedAtMs = 0L;

    private MediaRecorder recorder;
    private File target;
    private long startedAt;

    /** 允许启动的静态检查。返回 null 表示已发起启动，否则返回给用户看的原因。 */
    public static String startRecording(Context c) {
        if (recording) return null;
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return I18n.text(c,R.string.ov_need_microphone);
        if (RealtimeService.running) return I18n.text(c,R.string.ov_realtime_busy);
        if (JobService.running) return I18n.text(c,R.string.ov_file_busy);
        if (Busy.holder() != null) return I18n.text(c,R.string.ov_task_busy);
        if (!MainActivity.modelReady) return I18n.text(c,R.string.ov_need_model);
        if (new Prefs(c).roleDir().isEmpty()) return I18n.text(c,R.string.ov_need_role);
        Intent i = new Intent(c, QuickVoiceService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException e) {
            return I18n.text(c,R.string.ov_record_start_unavailable);
        }
        return null;
    }

    /** 结束录音并自动送转换（只在 recording 为真时有意义）。 */
    public static void stopAndConvert(Context c) {
        if (!recording) return;
        lastToken=Math.max(System.currentTimeMillis(),lastToken+1);
        JobService.beginSubmission(lastToken);
        Intent i = new Intent(c, QuickVoiceService.class).setAction(ACTION_STOP);
        try {
            c.startService(i);
        } catch (RuntimeException e) {
            abort(c);
        }
    }

    /** 结束录音并丢弃，不转。关浮窗 / 出错时用。 */
    public static void abort(Context c) {
        Intent i = new Intent(c, QuickVoiceService.class).setAction(ACTION_ABORT);
        try {
            c.startService(i);
        } catch (RuntimeException e) {
            try { c.stopService(new Intent(c, QuickVoiceService.class)); } catch (RuntimeException ignored) {}
        }
    }

    private void log(String s) {
        status = s;
        AppLog.i(new Prefs(this).baseDir(), "[快捷录音] " + s);
        notifyText(s);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle(I18n.text(QuickVoiceService.this,R.string.ov_quick_title)).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
                .setContentIntent(open).build();
    }

    private void notifyText(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION, notification(text));
        } catch (Throwable ignore) {}
    }

    /** 申请 microphone 前台身份。权限缺失会抛 SecurityException，由调用方兜底。 */
    private boolean enterForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, I18n.text(QuickVoiceService.this,R.string.ov_quick_title),
                    NotificationManager.IMPORTANCE_LOW));
            // 取消旧的常驻通知（非前台状态残留）
            nm.cancel(NOTIFICATION);
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification(I18n.text(QuickVoiceService.this,R.string.ov_preparing)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION, notification(I18n.text(QuickVoiceService.this,R.string.ov_preparing)));
            }
            return true;
        } catch (Throwable e) {
            log(I18n.text(QuickVoiceService.this,R.string.ov_microphone_foreground_failed) + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        Tune.init(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_ABORT.equals(action)) {
            finishRecording(false);
            log(I18n.text(QuickVoiceService.this,R.string.ov_cancelled));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            File f = finishRecording(true);
            stopSelf();
            if (f == null) {
                JobService.rejectSubmission(lastToken,I18n.text(QuickVoiceService.this,R.string.ov_invalid_recording));
                log(I18n.text(QuickVoiceService.this,R.string.ov_invalid_recording));
            } else {
                submit(f);
            }
            return START_NOT_STICKY;
        }
        if (recording) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = I18n.text(QuickVoiceService.this,R.string.ov_microphone_missing);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startRecorder();
            log(I18n.text(QuickVoiceService.this,R.string.ov_recording));
        } catch (Throwable e) {
            finishRecording(false);
            log(I18n.text(QuickVoiceService.this,R.string.ov_record_start_failed) + (e.getMessage() == null ? e.toString() : e.getMessage()));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startRecorder() throws Exception {
        File dir = new File(new Prefs(this).baseDir(), "quick");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException(I18n.text(QuickVoiceService.this,R.string.ov_directory_failed));
        File out = new File(dir, "quick_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a");
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(96000);
            r.setOutputFile(out.getAbsolutePath());
            r.prepare();
            r.start();
        } catch (Throwable e) {
            try { r.release(); } catch (Throwable ignored) {}
            throw e instanceof Exception ? (Exception) e : new java.io.IOException(String.valueOf(e));
        }
        recorder = r;
        target = out;
        startedAt = SystemClock.uptimeMillis();
        startedAtMs = startedAt;
        recording = true;
    }

    /**
     * 停止并释放录音器；keep=false 或录音不可用时删除文件。
     * 返回可用的 m4a 文件，否则 null。幂等。
     */
    private File finishRecording(boolean keep) {
        MediaRecorder r = recorder;
        File f = target;
        recorder = null;
        target = null;
        recording = false;
        startedAtMs = 0L;
        boolean valid = r != null && f != null;
        if (r != null) {
            try { r.stop(); } catch (Throwable e) { valid = false; }
            try { r.release(); } catch (Throwable ignored) {}
        }
        if (f != null && (!valid || f.length() <= 0)) {
            try { f.delete(); } catch (Throwable ignored) {}
            valid = false;
        }
        if (f != null && !keep) {
            try { f.delete(); } catch (Throwable ignored) {}
            valid = false;
        }
        return valid ? f : null;
    }

    /** 显式把录音交给 JobService（文件变声），结果走 JobService.quick* 回传。 */
    private void submit(File f) {
        long token = lastToken;
        lastToken = token;
        Intent i = new Intent(this, JobService.class);
        i.putExtra("uri", Uri.fromFile(f).toString());
        i.putExtra("name", f.getName());
        i.putExtra("quick", true);
        i.putExtra("quickToken", token);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            log(I18n.text(QuickVoiceService.this,R.string.ov_submitted) + f.getName());
        } catch (RuntimeException e) {
            JobService.rejectSubmission(token,I18n.text(QuickVoiceService.this,R.string.ov_conversion_service_failed));
            status = I18n.text(QuickVoiceService.this,R.string.ov_conversion_service_failed);
            AppLog.i(new Prefs(this).baseDir(), "[快捷录音] 无法启动转换服务：" + e);
            log(I18n.text(QuickVoiceService.this,R.string.ov_conversion_service_failed));
        }
    }

    @Override public void onDestroy() {
        // 兜底：进程/服务被系统或用户杀掉时不能占着麦克风，也不自动转
        if (recording || recorder != null) {
            finishRecording(false);
            status = I18n.text(QuickVoiceService.this,R.string.ov_interrupted);
        }
        try { stopForeground(true); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
