package com.svc.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

/** 实时模式：麦克风 -> 变声 -> 外放 */
public class RealtimeService extends Service {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(I18n.wrap(base)); }


    public enum UiState { IDLE, LOADING, RUNNING, ERROR }
    public static volatile UiState uiState = UiState.IDLE;
    public static volatile boolean running = false;
    public static volatile String status = "";
    private Thread worker, micThread, outputThread;
    private volatile AudioRecord activeRecord;
    private static volatile RealtimeService activeService;
    private Engine engine;
    private volatile HeadphoneMonitor monitor;
    public static void stopMonitor(){RealtimeService a=activeService;if(a!=null&&a.monitor!=null)a.monitor.disable();}
    private android.media.audiofx.AcousticEchoCanceler aec;
    private android.media.audiofx.NoiseSuppressor ns;

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        Tune.init(this);
        Tune.boostProcess();   // 让 Engine 能读到「设置」页的线程数 / 核心
        String ch = "svc";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.notif_rt_title)).setContentText(getString(R.string.notif_rt_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
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

    public static volatile boolean stopReq = false;

    /** 【结束任务】 */
    public static void requestStop() { stopReq = true; RealtimeService svc = activeService; if (svc != null) svc.unblockAudio(); OutputEnvelope.clear(OutputEnvelope.LIVE); }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if(!MainActivity.modelReady || "model-load".equals(Busy.holder())) {stopSelf();return START_NOT_STICKY;}
        if (running) return START_STICKY;
        uiState = UiState.LOADING;
        running = true;
        stopReq = false;
        final Prefs p = new Prefs(this);
        worker = new Thread(() -> {
            if (!Busy.acquire("rt")) {
                rtLog(getString(R.string.log_rt_busy, busyLabel()));
                uiState = UiState.IDLE;
                stopReq = true;
            running = false;
            LiveWindowState.stopped();
            unblockAudio();
            joinAudio(micThread); joinAudio(outputThread);
            WaveProbe.played(-1);
            activeRecord = null;
            stopMonitor();
            OutputEnvelope.clear(OutputEnvelope.LIVE);
                status = getString(R.string.status_rt_stopped);
                stopSelf();
                return;
            }
            try {
                loop(p);
            } catch (Throwable e) {
                uiState = UiState.ERROR;
                status = getString(R.string.status_rt_error, String.valueOf(e));
                rtLog("实时异常: " + android.util.Log.getStackTraceString(e));
            } finally {
                Busy.release("rt");
            }
        }, "svc-realtime");
        worker.start();
        // 被系统杀掉后让系统把我们拉回来，否则 helper 活着但没人管
        return START_STICKY;
    }

    private int aliveTick = 0;

    private void loop(Prefs p) {
        final boolean denoise=p.liveDenoise(); final boolean volumeLimit=p.liveVolumeLimit(); final int frozenPitch=p.livePitchSemitones();
        final long envelopeToken=OutputEnvelope.begin(OutputEnvelope.LIVE);
        AudioRecord rec = null;
        monitor=new HeadphoneMonitor(this);
        AudioTrack track = null;
        try {
            String roleDir = p.roleDir();
            Engine.device=p.device();
            engine = Engine.acquire(p.modelsDir(), s -> { status = s; rtLog(s); });
            engine.loadRole(roleDir, p.rtFrames());
            engine.beginBackendReport();

            // 虚拟麦克风：把变声结果注入系统 MIC，微信/QQ 才能收到
            boolean inj = p.inject();
            if (inj) {
                inj = p.bypass() ? Vmic.start(this) : Vmic.startRealtime(this);
                if(!inj)throw new IllegalStateException("目标采集启动失败："+Vmic.startupError());
                if (!inj) rtLog(getString(R.string.log_rt_vmic_fail));
            }

            // ★ 直通测试：采集+推子+注入全在 helper 里，App 完全不碰音频。
            //   App 只负责把推子数值写进 gain.txt。
            if (p.bypass()) {
                rtLog(getString(R.string.log_rt_passthrough, p.pushGain()));
                if (!inj) { rtLog(getString(R.string.log_rt_need_inject)); }
                // helper 被系统杀掉后 policy 会泄漏，所有 App 的麦克风变静音。
                // 这里盯着它，挂了立刻重起，最坏断 1 秒。
                while (running && !stopReq) {
                    try { Thread.sleep(200); } catch (InterruptedException ignore) { break; }
                    if (stopReq || !running) break;
                    if (++aliveTick < 10) continue;   // 每 2 秒查一次
                    aliveTick = 0;
                    if (!Vmic.isHelperAlive()) {
                        rtLog(getString(R.string.log_rt_helper_restart));
                        Vmic.start(RealtimeService.this);
                    }
                }
                return;
            }

            // ★ 采集必须用 48000Hz。
            //   UNPROCESSED 要求设备原生采样率（这台机器是 48000），
            //   用 16000 请求它不会报错，只会**静默返回全 0 数据**：
            //   自测录到 RMS=0.0007（而 MIC 源是 0.185），外面再吵录出来也是一条直线。
            //   所以这里按 48000 采集，再内部降到 16k 给模型。MicBoost 同样是 48k 采集。
            final int SR_CAP = 48000;
            if (!inj) {
            int minBuf = AudioRecord.getMinBufferSize(SR_CAP,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // Capture source must remain outside injection presets 1/7.
            // Initialization success alone does not prove a physical microphone route.
            int[] trySrc = new int[] { MediaRecorder.AudioSource.VOICE_RECOGNITION };
            int src = trySrc[0];
            rec = null;
            for (int i = 0; i < trySrc.length; i++) {
                src = trySrc[i];
                AudioRecord r;
                try {
                    r = new AudioRecord(src, SR_CAP,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minBuf, SR_CAP / 2));
                } catch (Throwable t) {
                    rtLog("采集源 " + src + " 构造失败: " + t);
                    continue;
                }
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                    break;
                }
                try { r.release(); } catch (Throwable ignore) { }
            }
            if (rec == null) throw new IllegalStateException(getString(R.string.err_rt_no_source));
            rtLog("采集源 = " + src);
            activeRecord = rec;
            rec.startRecording();

            }
            // 回声消除：避免"麦克风拾取喇叭 → 再变声 → 再放大"的无限循环
            aec = null;
            ns = null;
            // ★ AEC 一律不开：它把近场人声削掉 30dB，变声直接失效；
            //   真正的自环要在采集源上解决，不是靠 AEC 掩盖。
            if (false && needAec && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                aec = android.media.audiofx.AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (false && needAec && android.media.audiofx.NoiseSuppressor.isAvailable()) {
                ns = android.media.audiofx.NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
            rtLog("AEC=" + (aec != null && aec.getEnabled()) + " NS=" + (ns != null && ns.getEnabled()));

            int minOut = AudioTrack.getMinBufferSize(Engine.SR_OUT,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Engine.SR_OUT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(minOut, Engine.SR_OUT * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            track.setVolume(1.0f);
            track.play();


            final int win = Pipeline.sample16Of(engine.frames());
            final int fade = Math.max(0,Math.min(p.rtXfade(),engine.frames()/2));
            final int hop = Pipeline.sample16Of(Math.max(1,engine.frames()-fade));
            LiveWindowState.windowMs = win * 1000.0 / Engine.SR_IN;
            LiveWindowState.bufferMs = hop * 1000.0 / Engine.SR_IN;
            LiveWindowState.pendingBlocks = 0;
            LiveWindowState.processedBlocks = 0;
            LiveWindowState.reset(win, hop);
            LiveWindowState.running = true;
            WaveProbe.reset();
            WaveProbe.virtualMic(inj);
            final java.util.concurrent.LinkedBlockingQueue<float[]> inputs = new java.util.concurrent.LinkedBlockingQueue<>();
            final java.util.concurrent.LinkedBlockingQueue<WaveBlock> outputs = new java.util.concurrent.LinkedBlockingQueue<>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            final AudioRecord capture = rec;
            final AudioTrack playback = track;
            final boolean inject = inj;
            // 辅助条只在这两个条件都成立时刷新；关闭时坐标仍连续，只是不落图形
            // 每个块重新问一次：开关可能在中途被打开，届时坐标从当前绝对位置继续画
            final long[] playBase = {-1};
            micThread = new Thread(() -> {
                try {
                    short[] raw = new short[Math.max(3, hop * 3)];
                    float[] block = new float[hop];
                    int used = 0;
                    byte[] bytes=new byte[960];
                    Pcm48To16 converter=new Pcm48To16();
                    // 绝对 16k 采样坐标：一个分片一个窗口，块起点=已交付的 16k 样本数
                    long inPos=0;
                    while (running && !stopReq) {
                        LiveWindowState.publish();
                        int n;
                        if(inject)n=Vmic.readCapture(bytes);
                        else {int k=capture.read(raw,0,Math.min(raw.length,480));if(k<0)throw new IllegalStateException("AudioRecord.read="+k);n=k*2;for(int i=0;i<k;i++){bytes[2*i]=(byte)raw[i];bytes[2*i+1]=(byte)(raw[i]>>8);}}
                        float[] converted=converter.accept(bytes,n);
                        WaveProbe.inputStart(inPos);
                        if(WaveProbe.running()) WaveProbe.inputBlock(converted,converted.length);
                        else WaveProbe.skipInput(converted.length);
                        inPos+=converted.length;
                        for(float v:converted){block[used++]=v;LiveWindowState.inputQuiet(1);
                            if(used==hop){
                                // 坐标先落位再入队：辅助条看到的时间轴与推理输入严格一致
                                inputs.add(block);LiveWindowState.pendingBlocks=inputs.size();block=new float[hop];used=0;}}

                    }
                } catch (Throwable t) { if (!stopReq) failure.compareAndSet(null, t); }
            }, "svc-live-capture");
            outputThread = new Thread(() -> {
                try {
                    while (running && !stopReq) {
                        if(!inject) WaveProbe.played(WaveStore.unsigned32(playback.getPlaybackHeadPosition()));
                        WaveBlock wb = outputs.poll(33, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (wb == null) continue;
                        short[] chunk = wb.pcm;
                        if (inject) {
                            // 写入仅作诊断；播放头由独立进度通道回报，不代表接收 App 已读取
                            Vmic.feed44k(chunk, 0, chunk.length);

                        } else {
                            int off = 0;
                            // AudioTrack 的 head 从 0 起、只按本 track 的消费量前进，
                            // 而分片的 start 是整条绝对时间轴。用「已开始消费的绝对样本」作基准，
                            // 每次把无符号 head 的增量加上去，回绕时就自然进位。
                            if (playBase[0] < 0) playBase[0] = wb.start;
                            int lastHead = playback.getPlaybackHeadPosition();
                            while (off < chunk.length && running && !stopReq) {
                                int n = playback.write(chunk, off, chunk.length - off, AudioTrack.WRITE_NON_BLOCKING);
                                if (n < 0) throw new IllegalStateException("AudioTrack.write=" + n);
                                if (n == 0) Thread.sleep(5); else off += n;
                                int raw = playback.getPlaybackHeadPosition();
                                // 32 位无符号：int 读法在累计 2^31 样本后变负，必须按位掩码取增量
                                long delta = WaveStore.unsignedDelta(WaveStore.unsigned32(raw), WaveStore.unsigned32(lastHead));
                                lastHead = raw;
                                WaveProbe.played(WaveStore.unsigned32(raw));
                            }
                        }
                        if (!stopReq) OutputEnvelope.submit(OutputEnvelope.LIVE, envelopeToken, chunk, 0, chunk.length, Engine.SR_OUT);
                        LiveWindowState.outputPendingBlocks = outputs.size();
                    }
                } catch (Throwable t) { if (!stopReq) failure.compareAndSet(null, t); }
            }, "svc-live-output");
            micThread.start(); outputThread.start();
            engine.resetStats();engine.resetRandom();
            final long[] blocks={0};
            ChunkPipeline core=new ChunkPipeline(engine.frames(),fade,
                segment->engine.convert(segment,frozenPitch),
                (audio,start)->{
                    short[] pcm=new short[audio.length];
                    for(int i=0;i<pcm.length;i++)pcm[i]=(short)(Math.max(-1f,Math.min(1f,audio[i]))*32767f);
                    // start 是 44.1k 绝对样本；转成 short 不改变长度，与写入 AudioTrack/helper 的区间一致
                    WaveProbe.outputCommitted(pcm,start);
                    outputs.add(new WaveBlock(pcm,start));
                    if(inject)monitor.offer(pcm,p.monitorEnabled());
                },
                new ChunkPipeline.Observer(){
                    public void begin(long start,int real){LiveWindowState.begin(start+real);}
                    public void complete(long start,int real,long ns){
                        engine.completedChunks++;LiveWindowState.lastComputeMs=ns/1000000;
                        LiveWindowState.processedBlocks=++blocks[0];
                        LiveWindowState.complete(start+real,LiveWindowState.lastComputeMs);
                    }
                },denoise,volumeLimit);
            try {
            long lastWarning=0;
            rtLog("公共文件分片核心 T="+engine.frames()+" full16="+win+" step16="+hop+" xfade="+fade+"；末窗回移仅EOF可确定；在线固定增益1，文件最终全段峰归一0.95");
            while(running&&!stopReq){
                if(failure.get()!=null)throw new IllegalStateException("实时音频线程失败",failure.get());
                float[] next=inputs.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);if(next==null)continue;
                core.accept(next);
                LiveWindowState.pendingBlocks=inputs.size();LiveWindowState.outputPendingBlocks=outputs.size();
                String info="分片 "+LiveWindowState.windowMs+"ms / 步长 "+LiveWindowState.bufferMs+"ms / 输入积压ms "+(inputs.size()*hop/16.0)+" / 输出积压块 "+outputs.size()+" / 已处理 "+blocks[0]+" / 推理 "+LiveWindowState.lastComputeMs+"ms / 区间44k [0,"+core.emitted()+")";
                rtTick(info);long now=android.os.SystemClock.elapsedRealtime();
                if((inputs.size()>2||outputs.size()>2)&&now-lastWarning>=5000){lastWarning=now;new android.os.Handler(android.os.Looper.getMainLooper()).post(()->android.widget.Toast.makeText(this,"实时处理积压，保留全部队列；"+info,android.widget.Toast.LENGTH_SHORT).show());}
            }

            } finally {core.close();}
        } catch (Throwable e) {
            uiState = UiState.ERROR;
            status = getString(R.string.status_rt_error, String.valueOf(e));
            rtLog("实时出错: " + android.util.Log.getStackTraceString(e));
        } finally {
            stopReq = true;
            running = false;
            LiveWindowState.stopped();
            unblockAudio();
            joinAudio(micThread); joinAudio(outputThread);
            WaveProbe.played(-1);
            activeRecord = null;
            if(monitor!=null)monitor.close();
            OutputEnvelope.clear(OutputEnvelope.LIVE);
            if (uiState != UiState.ERROR) uiState = UiState.IDLE;
            try { if (aec != null) aec.release(); } catch (Throwable ignore) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignore) {}
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignore) {}
            try { if (track != null) { track.setVolume(0f); track.pause(); track.flush(); track.stop(); track.release(); } } catch (Throwable ignore) {}
            // ★ 不关闭：Engine 常驻
            try { Vmic.stop(this); } catch (Throwable ignore) {}
            stopSelf();
        }
    }

    /** 外放时会自激，需要 AEC；建议戴耳机并关掉 */
    private static final boolean needAec = false;

    private static long sample44(long sample16) { return Math.round(sample16*(double)Engine.SR_OUT/Engine.SR_IN); }

    /** 转换结果 + 它在 44.1k 绝对时间轴上的起点（ChunkPipeline sink 的 start）。 */
    private static final class WaveBlock {
        final short[] pcm;
        final long start;
        WaveBlock(short[] pcm,long start){this.pcm=pcm;this.start=start;}
    }

    private int logCount = 0;
    private long sDumpLeft = Engine.SR_OUT * 4;
    private java.io.FileOutputStream sDumpOs;

    private void rtLog(String s) {
        AppLog.i(new Prefs(this).baseDir(), s);
    }

    /** 每片都回显，不必只在状态栏 */
    private void rtTick(String s) {
        uiState = UiState.RUNNING;
        status = s;
        if ((++logCount % 4) == 1) AppLog.i(new Prefs(this).baseDir(), s);
        else {
            AppLog.Sink k = AppLog.sink;
            if (k != null) try { k.log(s); } catch (Throwable ignore) {}
        }
    }

    @Override
    public void onDestroy() {
        requestStop();
        if (uiState != UiState.ERROR) uiState = UiState.IDLE;
        activeService = null;
        super.onDestroy();
    }

    private void unblockAudio() {
        Vmic.stopRealtime();
        AudioRecord r = activeRecord;
        if (r != null) try { r.stop(); } catch (Throwable ignored) { }
        if (micThread != null) micThread.interrupt();
        if (outputThread != null) outputThread.interrupt();
    }

    private void joinAudio(Thread t) {
        if (t == null || t == Thread.currentThread()) return;
        boolean interrupted = false;
        while (t.isAlive()) {
            try { t.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
