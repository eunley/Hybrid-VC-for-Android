package com.svc.voice;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** 统一日志出口：文件 + logcat + 界面回显 */
public final class AppLog {

    public interface Sink { void log(String s); }

    /** 界面注册的回显回调，Activity 销毁时置空 */
    public static volatile Sink sink;

    private AppLog() {}
    public static long generation, revision;
    public static synchronized void clear(File dir) throws Exception {
        generation++; revision++;
        try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(new File(dir,"app.log"),"rw")){f.setLength(0);}
        Process proc=new ProcessBuilder("su","-c","if [ -f /data/local/tmp/svcvmic/vmic.log ]; then : > /data/local/tmp/svcvmic/vmic.log; fi").start();
        if(proc.waitFor()!=0)throw new java.io.IOException("helper log truncate failed");
    }
    public static synchronized String tail(File dir) throws Exception {
        File file=new File(dir,"app.log");if(!file.isFile())return "";
        try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(file,"r")){
            long from=Math.max(0,f.length()-65536);f.seek(from);byte[] b=new byte[(int)(f.length()-from)];f.readFully(b);
            String text=new String(b,"UTF-8");if(from>0){int n=text.indexOf('\n');if(n>=0)text=text.substring(n+1);}return text;
        }
    }

    /** 精确到毫秒的时间前缀，方便对时序 */
    private static String ts() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.MILLISECOND));
    }

    public static synchronized void i(File baseDir, String s) {
        s = "[" + ts() + "] " + s;
        revision++;
        Log.i("SVC", s);
        if (baseDir != null) {
            try {
                FileOutputStream fo = new FileOutputStream(new File(baseDir, "app.log"), true);
                fo.write((s + "\n").getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignore) {}
        }
        Sink k = sink;
        if (k != null) {
            try { k.log(s); } catch (Throwable ignore) {}
        }
    }
}