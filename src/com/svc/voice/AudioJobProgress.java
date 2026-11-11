package com.svc.voice;
/** One immutable publication, no Activity or View references. Seconds refer to source audio. */
public final class AudioJobProgress {
 public final double fileDone,fileTotal,done,total; public final int index,count,unknown; public final String phase; public final boolean success;
 public AudioJobProgress(double f,double ft,double d,double t,int i,int c,int u,String p,boolean ok){fileDone=f;fileTotal=ft;done=d;total=t;index=i;count=c;unknown=u;phase=p;success=ok;}
 public int value(){return total>0?(int)Math.min(success?1000:999,Math.max(0,done/total*1000)):0;}
 public static volatile AudioJobProgress current=new AudioJobProgress(0,0,0,0,0,0,0,"",false);
}
