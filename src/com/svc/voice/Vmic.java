package com.svc.voice;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * root 虚拟麦克风：用 app_process 起 helper 注册 AudioPolicy，
 * 再把处理后的 PCM 写进 createAudioTrackSource 得到的 AudioTrack，
 * 从而让所有 App（微信/QQ/游戏）录到的都是变声后的声音。
 *
 * 参考 MicBoost 的 setupAudioPolicy：
 *   AudioPolicy.Builder.addMix(AudioMix(rule=RULE_MATCH_ATTRIBUTES(2),
 *       role=MIX_ROLE_INJECTOR(1)).setRouteFlags(LOOP_BACK=2))
 *   AudioManager.registerAudioPolicy(policy) == 0
 *   policy.createAudioTrackSource(mix)
 */
public final class Vmic {

    private static final String TAG = "SVC";
    private static final String DIR = "/data/local/tmp/svcvmic";
    /** 44.1k -> 48k 线性重采样步长 */
    private static final double STEP = 44100.0 / 48000.0;

    private static OutputStream sOut;
    private static volatile java.io.FileDescriptor feedFd;
    private static volatile boolean helperStarted;
    private static double sPos;
    private static short sLast;
    private static final byte[] sBuf = new byte[3840];
    private static int sBufN;

    private Vmic() { }

    public static synchronized boolean isOn() { return helperStarted; }

    public static String fifo(Context c) {
        return c.getFilesDir().getAbsolutePath() + "/vmic.fifo";
    }

    /** 释放 helper、su 启动、连上 FIFO。失败返回 false（不影响耳机自听） */
    private static volatile android.net.LocalSocket captureSocket;
    private static android.net.LocalServerSocket captureServer;
    private static volatile ProgressSession progressSession;

    private static void closeServer(android.net.LocalServerSocket server) {
        if (server == null) return;
        // Android's close can wait behind a blocked accept. Shutdown wakes accept first.
        try { android.system.Os.shutdown(server.getFileDescriptor(), android.system.OsConstants.SHUT_RDWR); }
        catch (Throwable ignored) { }
        try { server.close(); } catch (Throwable ignored) { }
    }
    private static void closeSocket(android.net.LocalSocket peer) {
        if (peer == null) return;
        try { peer.shutdownInput(); } catch (Throwable ignored) { }
        try { peer.shutdownOutput(); } catch (Throwable ignored) { }
        try { peer.close(); } catch (Throwable ignored) { }
    }

    /** Per-start owner: closing it unblocks both accept and read, including failed startup. */
    private static final class ProgressSession {
        final android.net.LocalServerSocket server;
        final java.util.concurrent.FutureTask<android.net.LocalSocket> accept;
        final Thread acceptThread;
        volatile android.net.LocalSocket socket;
        volatile Thread readerThread;
        private boolean closed;
        ProgressSession(String name) throws java.io.IOException {
            server = new android.net.LocalServerSocket(name);
            accept = new java.util.concurrent.FutureTask<>(() -> {
                android.net.LocalSocket peer = server.accept();
                synchronized (this) {
                    if (closed) { peer.close(); throw new java.io.IOException("progress stopped"); }
                    socket = peer;
                }
                return peer;
            });
            acceptThread = new Thread(accept, "progress-accept");
            acceptThread.setDaemon(true); acceptThread.start();
        }
        void read(Context c, final int helperPid) {
            Thread reader = new Thread(() -> {
                try {
                    android.net.LocalSocket peer = accept.get(2, java.util.concurrent.TimeUnit.SECONDS);
                    android.net.Credentials cred = peer.getPeerCredentials();
                    if (cred.getUid() != 0 || cred.getPid() != helperPid)
                        throw new SecurityException("wrong progress helper peer");
                    peer.setSoTimeout(1500);
                    VmicProgressWire.Reader wire = new VmicProgressWire.Reader(
                            new java.io.DataInputStream(peer.getInputStream()));
                    for (;;) {
                        VmicProgressWire.Frame frame = wire.read();
                        synchronized (this) {
                            if (closed || progressSession != this) return;
                            WaveProbe.vmicPlayed(frame.effectiveHead48());
                        }
                    }
                } catch (Throwable unavailable) {
                    synchronized (this) {
                        if (!closed && progressSession == this)
                            diagnostic(c, "system AudioTrack progress unavailable: " + unavailable);
                    }
                } finally { close(); }
            }, "progress-reader");
            reader.setDaemon(true); readerThread = reader; reader.start();
        }
        synchronized void close() {
            if (closed) return;
            closed = true;
            if (progressSession == this) WaveProbe.vmicPlayed(-1);
            closeServer(server);
            closeSocket(socket);
            accept.cancel(true);
            acceptThread.interrupt();
            Thread reader = readerThread;
            if (reader != null && reader != Thread.currentThread()) reader.interrupt();
        }
    }
    private static Process ownedRoot;
    private static String rtName;
    private static boolean realtimeOwned;
    private static volatile String startupError="";
    public static String startupError(){return startupError;}
    private static final StringBuilder helperTail=new StringBuilder();
    private static void diagnostic(Context c,String text){ synchronized(helperTail){
        helperTail.append(text).append('\n');if(helperTail.length()>6000)helperTail.delete(0,helperTail.length()-6000);
        File logfile=new File(c.getFilesDir(),"vmic.log");
        if(logfile.length()>1048576)logfile.renameTo(new File(c.getFilesDir(),"vmic.log.1"));
        try(java.io.FileWriter w=new java.io.FileWriter(logfile,true)){
            w.write(System.currentTimeMillis()+" "+text+"\n");
        }catch(Throwable ignored){} log(c,text);
    }}
    public static synchronized boolean startRealtime(Context c) {
        if(helperStarted)return realtimeOwned;
        realtimeOwned=true;startupError="";synchronized(helperTail){helperTail.setLength(0);}
        try {
            int uid=new Prefs(c).resolveTargetUid();
            File dex=new File(c.getFilesDir(),"policy.dex");
            try(InputStream in=c.getAssets().open("policy.dex"); OutputStream out=new FileOutputStream(dex)) {
                byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
            }
            rtName="svc-raw-"+java.util.UUID.randomUUID().toString();
            captureServer=new android.net.LocalServerSocket(rtName);
            try { progressSession=new ProgressSession(rtName+"-progress"); }
            catch (java.io.IOException unavailable) {
                // Progress is optional: keep PCM capture alive and render play ? instead.
                diagnostic(c,"progress listener unavailable: "+unavailable);
            }
            final android.net.LocalServerSocket server=captureServer;
            final java.util.concurrent.FutureTask<android.net.LocalSocket> accept=new java.util.concurrent.FutureTask<>(()->server.accept());
            Thread t=new Thread(accept,"capture-accept");t.setDaemon(true);t.start();
            String dir=DIR+"/"+rtName;
            String cmd="mkdir -p '"+dir+"' && chmod 755 '"+dir+"' && cp '"+dex+"' '"+dir+"/policy.dex' && chmod 644 '"+dir+"/policy.dex' && CLASSPATH='"+dir+"/policy.dex' exec /system/bin/app_process /system/bin VmicPolicy rt "+rtName+" "+uid+" "+android.os.Process.myUid()+" "+android.os.Process.myPid()+(progressSession==null?"":" "+rtName+"-progress");
            diagnostic(c,"stage=execSu targetUid="+uid+" appUid="+android.os.Process.myUid()+" appPid="+android.os.Process.myPid());
            ownedRoot=execSu(cmd);
            if(ownedRoot==null)throw new java.io.IOException("su unavailable");
            final Process root=ownedRoot;
            Thread drain=new Thread(()->{try{BufferedReader r=new BufferedReader(new InputStreamReader(root.getInputStream()));String line;while((line=r.readLine())!=null)diagnostic(c,"helper: "+line); diagnostic(c,"helper exit="+root.waitFor());}catch(Throwable err){diagnostic(c,"drain failed: "+err);}},"capture-log");drain.setDaemon(true);drain.start();
            captureSocket=accept.get(8,java.util.concurrent.TimeUnit.SECONDS);
            android.net.Credentials helperPeer=captureSocket.getPeerCredentials();
            if(helperPeer.getUid()!=0)throw new SecurityException("helper is not root");
            captureSocket.setSoTimeout(15000);
            java.io.DataInputStream input=new java.io.DataInputStream(captureSocket.getInputStream());
            if(input.readInt()!=0x53564337 || input.readInt()!=uid)throw new java.io.IOException("bad capture handshake");
            captureSocket.setSoTimeout(15000);
            int state=input.readInt();String detail=input.readUTF();
            if(state!=1||!"STARTED".equals(detail))throw new java.io.IOException("helper startup: "+detail);
            captureSocket.setSoTimeout(3000);
            sOut=captureSocket.getOutputStream();sPos=0;sBufN=0;sLast=0;helperStarted=true;
            ProgressSession progress=progressSession;
            if(progress!=null)progress.read(c,helperPeer.getPid());
            log(c,"target UID helper source6 48000 mono PCM16 ready uid="+uid);
            return true;
        }catch(Throwable t){synchronized(helperTail){startupError=t.toString()+"\n"+helperTail.toString();}diagnostic(c,"helper capture failed: "+startupError);stopRealtime();return false;}
        finally{closeServer(captureServer); captureServer=null;}
    }
    public static int readCapture(byte[] b) throws Exception {
        android.net.LocalSocket socket=captureSocket;
        if(socket==null)throw new java.io.IOException("capture closed");
        int n=socket.getInputStream().read(b);if(n<0)throw new java.io.EOFException("capture EOF");return n;
    }
    public static void stopRealtime() {
        ProgressSession progress=progressSession;
        if(progress!=null){progress.close();if(progressSession==progress)progressSession=null;}
        if(captureSocket==null && rtName==null && captureServer==null)return;
        android.net.LocalSocket socket=captureSocket;captureSocket=null;
        closeSocket(socket);
        closeServer(captureServer);
        sOut=null;
        // socket EOF makes root release tracks/policy and close child stdin; no global kill.
        if(rtName!=null){final String dir=DIR+"/"+rtName;new Thread(()->{try{Thread.sleep(1000);}catch(Exception ignored){}sh("rm -rf '"+dir+"'");},"capture-files-cleanup").start();}
        rtName=null;
        Process process=ownedRoot;ownedRoot=null;
        if(process!=null)new Thread(()->{try{if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))process.destroy();}catch(Throwable ignored){}},"capture-reap").start();
        helperStarted=false;realtimeOwned=false;sBufN=0;sPos=0;sLast=0;
    }

    public static synchronized boolean start(Context c) {
        if (helperStarted) return true;
        startupError="";
        try {
            Prefs prefs = new Prefs(c);
            boolean bypass = prefs.bypass();
            int targetUid = bypass ? prefs.resolveTargetUid() : -1;
            if (!new File("/system/bin/app_process").exists()) return false;

            File dex = new File(c.getFilesDir(), "policy.dex");
            InputStream in = c.getAssets().open("policy.dex");
            OutputStream o = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            o.close();

            String fifo = fifo(c);
            // 注意：必须写成脚本文件再执行。
            // 若用 su -c "....VmicPolicy feed...."，sh 进程的命令行会含 "VmicPolicy feed"，
            // 下面那句 pkill -f 会把 sh 自己杀掉（表现为 log 都没建、helper 起不来）。
            File shFile = new File(c.getFilesDir(), "vmic_start.sh");
            StringBuilder sb = new StringBuilder();
            sb.append("#!/system/bin/sh\n");
            sb.append("DIR=").append(DIR).append("\n");
            sb.append("FIFO='").append(fifo).append("'\n");
            sb.append("DEX='").append(dex.getAbsolutePath()).append("'\n");
            sb.append("mkdir -p $DIR\n");
            sb.append("cp -f \"$DEX\" $DIR/policy.dex\n");
            sb.append("chmod 644 $DIR/policy.dex\n");
            sb.append("rm -f \"$FIFO\"\n");
            sb.append("mkfifo \"$FIFO\"\n");
            sb.append("chmod 666 \"$FIFO\"\n");
            // su 建出来的 FIFO 是 app_data_file:s0，缺少 App 目录的 MCS 类别，
            // 普通 App 打开会被 SELinux 拒绝，必须把父目录的 context 抄过去
            sb.append("CTX=$(stat -c %C \"$(dirname \"$FIFO\")\" 2>/dev/null)\n");
            sb.append("[ -n \"$CTX\" ] && chcon \"$CTX\" \"$FIFO\" 2>/dev/null\n");
            sb.append("[ -p \"$FIFO\" ] && echo FIFO_OK || echo FIFO_FAIL\n");
            // 必须用 SIGTERM（pkill 默认），让 helper 的 shutdown hook 跑 cleanup：
            // 停采集 -> pause/release 注入 track -> unregisterAudioPolicy。
            // 用 kill -9 会跳过清理，policy 残留在系统里一直劫持录音。
            sb.append("pkill -f 'VmicPolicy fee[d]' 2>/dev/null\n");
            sb.append("pkill -f 'VmicPolicy mi[c]' 2>/dev/null\n");
            // cap 是 su 10422 拉起的采集子进程，单独匹配，否则重启会留下孤儿占着麦克风
            sb.append("pkill -f 'VmicPolicy ca[p]' 2>/dev/null\n");
            sb.append("i=0; while [ $i -lt 20 ]; do\n");
            sb.append("  ps -A | grep -q 'VmicPolic[y]' || break\n");
            sb.append("  sleep 0.15; i=$((i+1))\n");
            sb.append("done\n");
            // Only explicit bypass starts microphone capture in the helper.
            sb.append("CLASSPATH=$DIR/policy.dex nohup /system/bin/app_process /system/bin ");
            if (bypass) sb.append("VmicPolicy mic $DIR/gain.txt 6 ").append(targetUid);
            else sb.append("VmicPolicy feed \"$FIFO\" 1,7 ").append(android.os.Process.myUid());
            sb.append(" ").append(android.os.Process.myPid())
              .append(" >> $DIR/vmic.log 2>&1 < /dev/null &\n");
            sb.append("echo STARTED\n");
            OutputStream so = new FileOutputStream(shFile);
            so.write(sb.toString().getBytes("UTF-8"));
            so.close();
            shFile.setReadable(true, false);

            String r = sh("sh '" + shFile.getAbsolutePath() + "'");
            log(c, "Vmic 启动: " + r.replace('\n', ' '));
            if (!r.contains("FIFO_OK")) {
                log(c, "Vmic: FIFO 建立失败（su 未授权？）");
                return false;
            }

            // 先把当前推子写进去，helper 启动时读
            setGain(c, new Prefs(c).pushGain());
            setEcho(new Prefs(c).echoLevel());
            Thread.sleep(1200);
            if (!bypass) {
                feedFd = android.system.Os.open(fifo, android.system.OsConstants.O_RDWR
                        | android.system.OsConstants.O_NONBLOCK, 0);
                sPos = 0; sBufN = 0; sLast = 0;
            }
            helperStarted = true;
            log(c, bypass ? "Vmic bypass 原声链" : "Vmic 模型 PCM -> FIFO -> 注入 track");
            return true;
        } catch (Throwable t) {
            startupError=t.toString();
            log(c, "Vmic 启动失败: " + startupError);
            sOut = null;
            return false;
        }
    }

    public static synchronized void stop(Context c) {
        if(realtimeOwned){stopRealtime();realtimeOwned=false;return;}
        helperStarted = false;
        java.io.FileDescriptor fd = feedFd; feedFd = null;
        if (fd != null) try { android.system.Os.close(fd); } catch (Throwable ignored) { }

        if (sOut != null) {
            try { sOut.close(); } catch (Throwable ignore) { }
            sOut = null;
            log(c, "Vmic 已停止注入");
        }
        // SIGTERM 触发 helper 的 shutdown hook 做 cleanup；等它把 policy 注销掉
        // 三种进程都要杀：
        //   feed = 老的 FIFO 转发进程
        //   mic  = root 注入进程
        //   cap  = su <目标uid> 拉起的采集进程，不杀它状态栏绿点不会消失
        sh("pkill -f '[V]micPolicy fee[d]' 2>/dev/null; "
                + "pkill -f '[V]micPolicy mi[c]' 2>/dev/null; "
                + "pkill -f '[V]micPolicy ca[p]' 2>/dev/null; "
                + "i=0; while [ $i -lt 20 ]; do "
                + "ps -A | grep -q 'VmicPolic[y]' || break; "
                + "sleep 0.15; i=$((i+1)); done; true");
    }

    /** helper 进程是否还活着（用 kill -0 探，不依赖 ps 输出格式） */
    public static boolean isHelperAlive() {
        try {
            String r = sh("pidof app_process 2>/dev/null | wc -w").trim();
            if (!r.matches("\\d+")) return false;
            if (Integer.parseInt(r) == 0) return false;
            // 确认是 VmicPolicy 而不是别家的 app_process
            String cmd = sh("for p in $(pidof app_process); do tr '\\0' ' ' < /proc/$p/cmdline; echo; done");
            return cmd.contains("VmicPolicy");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自动滑动开关：创建/删除 sweep 文件，helper 每秒查一次 */
    public static synchronized void setAutoSweep(boolean on) {
        try {
            File f = new File(DIR, "sweep");
            if (on) { f.createNewFile(); f.setReadable(true, false); }
            else { f.delete(); }
        } catch (Throwable t) {
            Log.w(TAG, "写 sweep 失败: " + t);
        }
    }

    /** 把推子（0~200，100=原音量）写给 helper，它每秒读一次 */
    /** 回声强度 0~100，helper 每秒读一次，立即生效 */
    public static synchronized void setEcho(int level) {
        try {
            File f = new File(DIR, "echo.txt");
            FileOutputStream o = new FileOutputStream(f);
            o.write(String.valueOf(level).getBytes("UTF-8"));
            o.close();
            f.setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "写 echo 失败: " + t);
        }
    }

    public static synchronized void setGain(Context c, int percent) {
        try {
            File f = new File(DIR, "gain.txt");
            FileOutputStream o = new FileOutputStream(f);
            o.write(String.valueOf(percent).getBytes("UTF-8"));
            o.close();
            f.setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "写 gain 失败: " + t);
        }
    }

    /**
     * 输入 44100Hz 的 short 样本，内部重采样到 48000 写进注入 AudioTrack。
     *
     * <p>写入字节只作诊断。播放游标来自独立认证通道回传的 helper AudioTrack
     * getPlaybackHeadPosition；无回报时显示未知，不用此处写入量估计。
     * 系统 AudioTrack 播放不代表接收 App 已读取。
     */
    public static synchronized void feed44k(short[] in, int n) {
        feed44k(in, 0, n);
    }

    public static synchronized void feed44k(short[] in, int off, int n) {
        if (feedFd == null && sOut == null) throw new IllegalStateException("Vmic model feed unavailable");
        if (n <= 0) return;
        while (sPos < n - 1) {
            int i0 = (int) Math.floor(sPos);
            float f = (float) (sPos - i0);
            float a = (i0 < 0) ? (float) sLast : (float) in[off + i0];
            float b = (float) in[off + i0 + 1];
            int v = (int) (a + (b - a) * f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            sBuf[sBufN++] = (byte) (v & 0xff);
            sBuf[sBufN++] = (byte) ((v >> 8) & 0xff);
            sPos += STEP;
            if (sBufN >= sBuf.length) flush();
        }
        // Position is relative to the NEXT block; -1 interpolates the saved last sample.
        sPos -= n;
        sLast = in[off + n - 1];
        flush();
    }

    private static void flush() {
        if (sBufN == 0) return;
        int offset = 0;
        try {
            while (offset < sBufN) {
                if (RealtimeService.stopReq || Thread.currentThread().isInterrupted()) return;
                java.io.FileDescriptor fd = feedFd;
                if(fd==null && sOut!=null){sOut.write(sBuf,offset,sBufN-offset); LiveWindowState.written(sBufN-offset); WaveProbe.injected(sBufN-offset);offset=sBufN;continue;}
                if (fd == null) throw new IllegalStateException("Vmic feed closed");
                try {
                    int n = android.system.Os.write(fd, sBuf, offset, sBufN - offset);
                    if (n > 0) { offset += n; LiveWindowState.written(n); WaveProbe.injected(n); } else Thread.sleep(5);
                } catch (android.system.ErrnoException e) {
                    if (e.errno != android.system.OsConstants.EAGAIN) throw e;
                    Thread.sleep(5);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            throw new IllegalStateException("Vmic PCM write failed", t);
        } finally { sBufN = 0; }
    }

    /** KernelSU/Magisk 的 su 在 /system/bin，App 的 PATH 里没有；逐个试执行 */
    private static Process execSu(String cmd) {
        String[] cands = { "/system/bin/su", "/debug_ramdisk/su", "/system/xbin/su", "su" };
        Throwable last = null;
        for (String su : cands) {
            try {
                return new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
            } catch (Throwable t) {
                last = t;
            }
        }
        Log.w(TAG, "execSu 全失败: " + last);
        return null;
    }

    private static String sh(String cmd) {
        try {
            Process p = execSu(cmd);
            if (p == null) return "ERR no-su";
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    private static void log(Context c, String s) {
        Log.i(TAG, s);
        try {
            AppLog.i(new Prefs(c).baseDir(), s);
        } catch (Throwable ignore) { }
    }
}