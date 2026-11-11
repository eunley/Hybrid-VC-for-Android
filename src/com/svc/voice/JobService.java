package com.svc.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.app.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 文件/批量变声：前台服务跑，串行处理，模型全程复用 */
public class JobService extends Service {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(I18n.wrap(base)); }


    public static volatile boolean running = false;
    public static volatile String status = "";
    public static volatile boolean cancelReq = false;

    /** 快捷录音请求的结果回传（包内广播 + 静态字段双通道，悬浮菜单轮询静态字段） */
    public static final String ACTION_QUICK_RESULT = "com.svc.voice.QUICK_RESULT";
    public static volatile long quickToken = 0L;      // 最近一次 quick 请求的 token（被拒绝也会写）
    public static volatile boolean quickDone = false; // 该 token 的处理已结束
    public static volatile boolean quickOk = false;   // 是否成功产出文件
    public static volatile String quickOutPath = "";  // 成功输出绝对路径，失败清空
    public static volatile String quickMessage = "";  // 失败原因 / 额外信息

    /** 单次 quick 请求的实例态 */
    private boolean quickRun = false;
    private boolean quickPublished = false;
    private volatile File lastTarget = null;

    /** 拒绝一次 quick 请求（忙/模型未就绪）：直接回传结论，避免 UI 一直等 */
    public static void beginSubmission(long token){quickDone=false;quickOk=false;quickOutPath="";quickMessage="";quickToken=token;}
    public static void rejectSubmission(long token,String message){rejectQuick(token,message);}
    private static void rejectQuick(long token, String message) {
        quickOk = false;
        quickOutPath = "";
        quickMessage = message == null ? "" : message;
        quickToken = token;
        quickDone = true;
    }

    private void publishQuickResult(boolean ok, String path, String message) {
        quickOk = ok;
        quickOutPath = ok && path != null ? path : "";
        quickMessage = message == null ? "" : message;
        quickPublished = true;
        try {
            Intent i = new Intent(ACTION_QUICK_RESULT).setPackage(getPackageName());
            i.putExtra("ok", ok).putExtra("path", quickOutPath)
             .putExtra("message", quickMessage).putExtra("token", quickToken);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private static final Object gate=new Object();
    public static volatile boolean paused=false;
    private static volatile long activeQuick=0, cancelledQuick=0;
    public static boolean quickPending(long token){return token>0 && QuickVoiceService.lastToken==token && !(quickToken==token&&quickDone);}
    public static boolean cancelQuick(long token){synchronized(gate){if(!quickPending(token))return false;cancelledQuick=token;if(activeQuick==token){cancelReq=true;paused=false;gate.notifyAll();OutputEnvelope.clear(OutputEnvelope.FILE);}return true;}}
    public static void togglePause(){synchronized(gate){if(running&&!cancelReq){paused=!paused;gate.notifyAll();}}}
    public static void checkpoint(){synchronized(gate){while(paused&&!cancelReq){try{gate.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException("__CANCELLED__");}}if(cancelReq)throw new RuntimeException("__CANCELLED__");}}
    public static void requestCancel() { synchronized(gate){cancelReq = true;paused=false;gate.notifyAll();} OutputEnvelope.clear(OutputEnvelope.FILE); }

    private double[] durations; private double completedSeconds,totalSeconds,currentSeconds; private int progressIndex,unknownDurations; private long lastProgress;
    private void progress(String phase,boolean success,boolean force){long now=android.os.SystemClock.uptimeMillis();if(!force&&now-lastProgress<100)return;lastProgress=now;double ft=durations!=null&&progressIndex<durations.length?durations[progressIndex]:0;AudioJobProgress.current=new AudioJobProgress(currentSeconds,ft,completedSeconds+currentSeconds,totalSeconds,progressIndex+1,durations==null?0:durations.length,unknownDurations,phase,success);}
    private double duration(Uri uri){android.media.MediaExtractor ex=new android.media.MediaExtractor();try{ex.setDataSource(this,uri,null);for(int i=0;i<ex.getTrackCount();i++){android.media.MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(android.media.MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")&&f.containsKey(android.media.MediaFormat.KEY_DURATION)){double d=f.getLong(android.media.MediaFormat.KEY_DURATION)/1000000.0;if(d>0)return d;}}}catch(Exception ignored){}finally{ex.release();}return 0;}
    private Thread worker;
    private NotificationManager nm;
    private Prefs prefs;
    private String notifTitle = "";

    private void notify(String text) {
        if (nm == null) return;
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "svcjob") : new Notification.Builder(this);
            b.setContentTitle(notifTitle).setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true);
            nm.notify(2, b.build());
        } catch (Throwable ignore) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Tune.init(this);
        Tune.boostProcess();
        prefs = new Prefs(this);
        nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("svcjob",
                    getString(R.string.notif_channel_task), NotificationManager.IMPORTANCE_LOW));
        }
        notifTitle = getString(R.string.notif_title_audio);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "svcjob") : new Notification.Builder(this);
        b.setContentTitle(notifTitle).setContentText(getString(R.string.notif_preparing))
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(2, b.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final boolean quick = intent != null && intent.getBooleanExtra("quick", false);
        final long qtoken = quick ? intent.getLongExtra("quickToken", 0L) : 0L;
        if (!MainActivity.modelReady || "model-load".equals(Busy.holder())) {
            if (quick) rejectQuick(qtoken, getString(R.string.job_not_ready));
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running || intent == null) {
            if (quick) rejectQuick(qtoken, getString(R.string.msg_busy, busyLabel()));
            return START_NOT_STICKY;
        }

        ArrayList<String> uris = intent.getStringArrayListExtra("uris");
        ArrayList<String> names = intent.getStringArrayListExtra("names");
        final boolean batch = intent.getBooleanExtra("batch", false) || (uris != null && uris.size() > 1);
        if (uris == null) {
            String u = intent.getStringExtra("uri");
            String n = intent.getStringExtra("name");
            if (u == null) { stopSelf(); return START_NOT_STICKY; }
            uris = new ArrayList<>();
            uris.add(u);
            names = new ArrayList<>();
            names.add(n == null ? "input.wav" : n);
        }
        if (names == null) names = new ArrayList<>();
        while (names.size() < uris.size()) names.add("input.wav");

        if(quick && cancelledQuick==qtoken){rejectQuick(qtoken,getString(R.string.job_cancelled_this));stopSelf();return START_NOT_STICKY;}
        final String reservation=batch?"batch":"file";
        if(!Busy.acquire(reservation)){
            if(quick)rejectQuick(qtoken,getString(R.string.job_active)+Busy.holderName());
            stopSelf();return START_NOT_STICKY;
        }
        activeQuick=quick?qtoken:0;paused=false;
        running = true;
        OutputEnvelope.clear(OutputEnvelope.FILE);
        cancelReq = quick && cancelledQuick==qtoken;
        quickRun = quick;
        lastTarget = null;
        if (quick) {
            // 先关旧结论，再清内容，最后写 token：读方先看 quickDone 不会读到半更新状态
            quickDone = false;
            quickPublished = false;
            quickOk = false;
            quickOutPath = "";
            quickMessage = "";
            quickToken = qtoken;
        }
        if (batch) notifTitle = getString(R.string.notif_title_batch);
        final ArrayList<String> fu = uris, fn = names;
        worker = new Thread(() -> runAll(fu, fn, batch), "svc-job");
        worker.start();
        return START_NOT_STICKY;
    }

    private String busyLabel() {
        String h = Busy.holder();
        if (h == null) return "";
        if (h.startsWith("batch")) return getString(R.string.busy_batch);
        if (h.startsWith("file")) return getString(R.string.busy_file);
        if (h.startsWith("rt")) return getString(R.string.busy_rt);
        if (h.startsWith("compile")) return getString(R.string.busy_compile);
        if (h.startsWith("model")) return getString(R.string.busy_model);
        return h;
    }

    private void log(String s) {
        status = s;
        AppLog.i(prefs == null ? new Prefs(this).baseDir() : prefs.baseDir(), s);
        notify(s.length() > 60 ? s.substring(0, 60) : s);
    }

    private void runAll(ArrayList<String> uris, ArrayList<String> names, boolean batch) {
        final String who = batch ? "batch" : "file";
        // Busy was reserved before touching shared cancellation/pause fields.
        Pipeline.filePitch.set(prefs.filePitchSemitones());
        Pipeline.fileDenoise.set(prefs.fileDenoise());
        Pipeline.fileVolumeLimit.set(prefs.fileVolumeLimit());
        log(getString(R.string.job_pitch)+prefs.filePitchSemitones()+getString(R.string.job_pitch_suffix));
        Pipeline.fileEnvelopeToken.set(OutputEnvelope.begin(OutputEnvelope.FILE));
        durations=new double[uris.size()]; completedSeconds=totalSeconds=currentSeconds=0;unknownDurations=0;progressIndex=0;progress(getString(R.string.job_duration),false,true);
        Engine eng = null;
        int ok = 0, fail = 0, done = 0;
        boolean cancelled = false;
        try {
            for(int i=0;i<uris.size();i++){checkpoint();durations[i]=duration(Uri.parse(uris.get(i)));if(durations[i]<=0)unknownDurations++;else totalSeconds+=durations[i];}
            progress(getString(R.string.job_models),false,true);
            if (prefs.roleDir().isEmpty()) { log(getString(R.string.log_no_role)); return; }
            Engine.device = prefs.device();
            eng = Engine.acquire(prefs.modelsDir(), this::log);
            eng.loadRole(prefs.roleDir(), prefs.fileFrames());
            eng.beginBackendReport();
            log(getString(R.string.log_chunk_layout, Prefs.framesToMs(eng.frames()),
                    Prefs.framesToMs(prefs.fileXfade())));
            if (batch) log(getString(R.string.log_batch_start, uris.size()));

            for (int i = 0; i < uris.size(); i++) {
                if (cancelReq) { cancelled = true; break; }
                progressIndex=i;currentSeconds=0;progress(getString(R.string.job_read),false,true);
                String nm = names.get(i) == null ? "input.wav" : names.get(i);
                if (batch) {
                    log(getString(R.string.log_batch_item, i + 1, uris.size(), nm));
                    notify(getString(R.string.notif_batch_progress, i + 1, uris.size(), nm));
                }
                long t0 = System.currentTimeMillis();
                try {
                    processOne(eng, Uri.parse(uris.get(i)), nm, batch, t0);
                    checkpoint();
                    ok++;
                    if (quickRun) publishQuickResult(true,
                            lastTarget == null ? "" : lastTarget.getAbsolutePath(), "");
                    progress(getString(R.string.job_file_done),false,true);
                    completedSeconds+=durations[i];currentSeconds=0;
                } catch (Throwable e) {
                    if (cancelReq || isCancel(e)) { cancelled = true; break; }
                    fail++;currentSeconds=0;progress(getString(R.string.job_file_failed),false,true);
                    log(getString(R.string.log_batch_fail, nm, msg(e)));
                    if (quickRun) publishQuickResult(false, "", msg(e));
                }
                done++;
            }

            if (batch && !cancelled) log(getString(R.string.log_batch_done, ok, fail));
            if (cancelled) log(getString(R.string.log_batch_cancel, done, uris.size()));
        } catch (Throwable e) {
            log(getString(R.string.log_failed, msg(e)));
        } finally {
            if(ok==uris.size()&&fail==0&&durations.length>0){currentSeconds=durations[progressIndex];completedSeconds-=currentSeconds;}
            progress(cancelReq||cancelled?getString(R.string.job_cancelled):(fail==0&&ok==uris.size()?getString(R.string.job_done):getString(R.string.job_partial)),!cancelReq&&!cancelled&&fail==0&&ok==uris.size(),true);
            Busy.release(who);
            finish();
        }
    }

    /** 处理一个文件；批量时每片进度只累计到本文件 */
    private void processOne(Engine eng, Uri uri, String name, boolean batch, long t0) throws Exception {
        Pipeline.filePitch.set(prefs.filePitchSemitones());
        Pipeline.fileDenoise.set(prefs.fileDenoise());
        Pipeline.fileVolumeLimit.set(prefs.fileVolumeLimit());
        log(getString(R.string.job_pitch)+prefs.filePitchSemitones()+getString(R.string.job_pitch_suffix));
        Pipeline.fileEnvelopeToken.set(OutputEnvelope.begin(OutputEnvelope.FILE));
        File tmp = null;
        boolean copied = false;
        try {
            if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                tmp = new File(uri.getPath());
                if (!tmp.isFile()) throw new IOException(getString(R.string.log_file_missing, tmp.getAbsolutePath()));
            } else {
                tmp = new File(getCacheDir(), "in_" + System.currentTimeMillis() + "_" + safeName(name));
                copied = true;
                log(getString(R.string.log_read_file, name));
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException(getString(R.string.err_no_stream));
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buf)) > 0) {checkpoint();os.write(buf, 0, n);}
                }
            }
            log(getString(R.string.log_decode));
            float[] wav16 = AudioIO.decode(tmp.getAbsolutePath(), Engine.SR_IN);
            log(String.format(Locale.ROOT, getString(R.string.log_duration),
                    wav16.length / (double) Engine.SR_IN));

            checkpoint();
            double actual=wav16.length/(double)Engine.SR_IN;
            if(durations[progressIndex]<=0)unknownDurations--;
            totalSeconds+=actual-durations[progressIndex];durations[progressIndex]=actual;
            progress(getString(R.string.job_converting),false,true);
            Pipeline.audioProgress.set((processed,total)->{checkpoint();currentSeconds=processed/(double)Engine.SR_IN;progress(getString(R.string.job_converting),false,false);});
            float[] out = Pipeline.process(eng, wav16, prefs.fileXfade(), (d, total, m) -> {
                if (cancelReq) throw new RuntimeException("__CANCELLED__");
                log(getString(R.string.log_chunk, d, total, m));
            });
            checkpoint();
            progress(getString(R.string.job_writing),false,true);
            String base = name.replaceAll("\\.[^.]+$", "");
            File target = uniqueOut(new File(prefs.outDir()), safeName(base), eng.roleName());
            checkpoint();
            AudioIO.writeWav(target.getAbsolutePath(), out, Engine.SR_OUT);
            if(cancelReq){target.delete();checkpoint();}
            lastTarget = target;
            long dt = System.currentTimeMillis() - t0;
            double sec = out.length / (double) Engine.SR_OUT;
            log(getString(R.string.log_wrote, target.getName()));
            log(String.format(Locale.ROOT, getString(R.string.log_summary), sec, dt / 1000.0,
                    dt / 1000.0 / Math.max(sec, 0.001)));
            if (!batch) {
                int nCh = Math.max(1, eng.completedChunks);
                log("completed chunks="+eng.completedChunks);
                log(String.format(Locale.ROOT,
                        "per-chunk hubert=%.0fms rmvpe=%.0fms mel=%.0fms enc=%.0fms dec=%.0fms other=%.0fms | sum=%.0fms actual=%.0fms",
                        eng.tHubert / 1e6 / nCh, eng.tRmvpe / 1e6 / nCh, eng.tMel / 1e6 / nCh,
                        eng.tEnc / 1e6 / nCh, eng.tDec / 1e6 / nCh, eng.tOther / 1e6 / nCh,
                        (eng.tHubert + eng.tRmvpe + eng.tMel + eng.tEnc + eng.tDec + eng.tOther) / 1e6 / nCh,
                        dt / (double) nCh));
            }
        } finally {
            Pipeline.audioProgress.remove();
            Pipeline.fileDenoise.remove();
            Pipeline.fileVolumeLimit.remove();
            Pipeline.filePitch.remove();

            Long token=Pipeline.fileEnvelopeToken.get();if(token!=null)OutputEnvelope.end(OutputEnvelope.FILE,token);
            if (copied && tmp != null) tmp.delete();
        }
    }

    /** 输出不覆盖同名文件，扩展名固定保留 .wav */
    private File uniqueOut(File dir, String base, String role) {
        if (!dir.isDirectory()) dir.mkdirs();
        String b = base.isEmpty() ? "input" : base;
        File f = new File(dir, b + "_" + role + ".wav");
        int i = 2;
        while (f.exists()) {
            f = new File(dir, b + "_" + role + "_" + i + ".wav");
            i++;
            if (i > 9999) break;
        }
        return f;
    }

    private static String safeName(String s) {
        if (s == null) return "input";
        String r = s.replace('/', '_').replace('\\', '_').replace('\0', '_');
        return r.isEmpty() ? "input" : r;
    }

    private static boolean isCancel(Throwable e) {
        String m = String.valueOf(e.getMessage());
        return e instanceof RuntimeException && m.contains("__CANCELLED__");
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.toString() : m;
    }

    private void finish() {
        synchronized(gate){paused=false;gate.notifyAll();}activeQuick=0;
        cancelReq = false;
        Long token=Pipeline.fileEnvelopeToken.get();if(token!=null)OutputEnvelope.end(OutputEnvelope.FILE,token);
        Pipeline.fileEnvelopeToken.remove();
        Pipeline.fileDenoise.remove();
        Pipeline.fileVolumeLimit.remove();
        Pipeline.filePitch.remove();
        running = false;
        if (quickRun) {
            if (!quickPublished) publishQuickResult(false, "", status);
            quickDone = true;   // 最后写：读方见到 true 时结果字段已就绪
        }
        try { nm.cancel(2); } catch (Throwable ignore) {}
        try { stopForeground(true); } catch (Throwable ignore) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if(running){requestCancel();}
        Pipeline.fileEnvelopeToken.remove();
        Pipeline.fileDenoise.remove();
        Pipeline.fileVolumeLimit.remove();
        Pipeline.filePitch.remove();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}