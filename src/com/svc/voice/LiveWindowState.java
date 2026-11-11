package com.svc.voice;

/** Coordinates are absolute 16k input samples; FIFO delivery is counted in 48k bytes.
 * FIFO accepted bytes do NOT imply helper AudioTrack writes or receiving-app consumption. */
public final class LiveWindowState {
    private LiveWindowState() { }
    public static volatile double windowMs, bufferMs;
    public static volatile int pendingBlocks, outputPendingBlocks;
    public static volatile long processedBlocks, lastComputeMs;
    public static volatile boolean running;
    private static long input, entered, completed, bytes, generation;
    private static final long[] durations=new long[32002];
    private static int win, hop;
    private static final long[] times=new long[32002], costs=new long[32002];
    private static int head, count;
    public interface Listener { void changed(); }
    private static volatile Listener listener;
    public static void listen(Listener l){listener=l;}
    private static void signal(){Listener l=listener;if(l!=null)l.changed();}
    public static synchronized void reset(int w,int h){win=w;hop=h;input=entered=completed=bytes=0;count=head=0;generation++;signal();}
    public static synchronized void inputQuiet(int n){input+=n;}
    public static void publish(){signal();}
    public static void stopped(){running=false;signal();}
    public static synchronized void input(int n){input+=n;signal();}
    public static synchronized void begin(long end){entered=end;signal();}
    public static synchronized void complete(long end,long ms){long duration=Math.max(1,end-completed);completed=end;long now=android.os.SystemClock.elapsedRealtime();prune(now);if(count==32002){head=(head+1)%32002;count--;}int i=(head+count++)%32002;times[i]=now;costs[i]=ms;durations[i]=duration;signal();}
    public static synchronized void written(int n){bytes+=n;signal();}
    private static void prune(long now){while(count>0&&now-times[head]>2000){head=(head+1)%32002;count--;}}
    public static synchronized double[] snapshot(){long now=android.os.SystemClock.elapsedRealtime();prune(now);long cost=0,duration=0;for(int j=0;j<count;j++){int i=(head+j)%32002;cost+=costs[i];duration+=durations[i];}return new double[]{generation,win,hop,input,entered,completed,bytes/2*16000.0/48000.0,!running||count==0||hop==0?Double.NaN:cost/(duration/16.0),running?1:0};}
}
