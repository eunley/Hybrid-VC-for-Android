package com.svc.voice;
import java.util.concurrent.*;
/** Bounded asynchronous PCM spectrum, shared by every UI consumer. Mono PCM. */
public final class OutputEnvelope {
 public static final int FILE=0,LIVE=1,BANDS=SpectrumFft.BANDS;
 public static volatile long published,consumed,dropped;
 private static final State[] states={new State(),new State()};
 private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(4),new ThreadFactory(){public Thread newThread(Runnable r){Thread t=new Thread(r,"OutputSpectrum");t.setDaemon(true);return t;}},new RejectedExecutionHandler(){public void rejectedExecution(Runnable r,ThreadPoolExecutor e){dropped++;}});
 static {worker.allowCoreThreadTimeOut(true);}
 static final class State {long generation,stamp,lastSample,inputStamp,dropSeen;boolean active;java.util.ArrayDeque<float[]> frames=new java.util.ArrayDeque<>();long nextFrame;float[] target=new float[BANDS],value=new float[BANDS];SpectrumFft fft=new SpectrumFft();}
 private static State state(int mode){if(mode<0||mode>1)throw new IllegalArgumentException("mode");return states[mode];}
 public static synchronized long begin(int mode){clear(mode);State s=state(mode);s.active=true;return s.generation;}
 public static synchronized void clear(int mode){State s=state(mode);s.generation++;s.active=false;s.frames.clear();s.nextFrame=0;s.stamp=s.lastSample=s.inputStamp=0;java.util.Arrays.fill(s.target,0);java.util.Arrays.fill(s.value,0);s.fft=new SpectrumFft();}
 private static synchronized boolean valid(int mode,long token){State s=state(mode);return s.active&&s.generation==token;}
 private static void bounds(int size,int offset,int length){if(offset<0||length<0||offset>size-length)throw new IndexOutOfBoundsException();}
 public static void submit(int mode,long token,float[] pcm,int offset,int length,int rate){bounds(pcm.length,offset,length);if(rate<1000||length==0||!valid(mode,token))return;int n=Math.min(length,rate*3);float[] copy=new float[n];System.arraycopy(pcm,offset+length-n,copy,0,n);enqueue(mode,token,copy,rate,length>n);}
 public static void submit(int mode,long token,short[] pcm,int offset,int length,int rate){bounds(pcm.length,offset,length);if(rate<1000||length==0||!valid(mode,token))return;int n=Math.min(length,rate*3);float[] copy=new float[n];for(int i=0;i<n;i++)copy[i]=pcm[offset+length-n+i]/32768f;enqueue(mode,token,copy,rate,length>n);}
 /** End input without invalidating queued real PCM spectra. */
 public static synchronized void end(int mode,long token){if(valid(mode,token))clear(mode);}
 private static void enqueue(final int mode,final long token,final float[] pcm,final int rate,final boolean gap){worker.execute(new Runnable(){public void run(){synchronized(OutputEnvelope.class){if(!valid(mode,token))return;State s=state(mode);SpectrumFft fft=s.fft;if(gap||fft.rate!=rate)fft.reset(rate);for(float v:pcm){float[] frame=fft.push(v);if(frame!=null){if(s.frames.size()>=Math.max(1,rate*3/SpectrumFft.HOP)){s.frames.removeFirst();dropped++;}s.frames.addLast(frame);published++;}}}}});}
 /** Copies a shared timestamp-smoothed snapshot; duplicate readers never advance it. */
 public static synchronized void spectrum(int mode,long now,float[] out){if(out.length<BANDS)throw new IllegalArgumentException("bands");if(mode==2){float[] other=new float[BANDS];spectrum(FILE,now,out);spectrum(LIVE,now,other);for(int i=0;i<BANDS;i++)out[i]=Math.max(out[i],other[i]);return;}State s=state(mode);if(!s.frames.isEmpty()&&(s.nextFrame==0||now>=s.nextFrame)){int hopMs=Math.max(1,1000*SpectrumFft.HOP/Math.max(1000,s.fft.rate));int steps=s.nextFrame==0?1:(int)Math.min(s.frames.size(),1+(now-s.nextFrame)/hopMs);for(int j=0;j<steps&&!s.frames.isEmpty();j++)s.target=s.frames.removeFirst();s.stamp=now;s.nextFrame=now+hopMs;}else if(s.frames.isEmpty())s.nextFrame=0;long dt=s.lastSample==0?20:Math.max(0,Math.min(1000,now-s.lastSample));s.lastSample=Math.max(now,s.lastSample);boolean fresh=s.active&&now-s.stamp<=250;for(int i=0;i<BANDS;i++){float t=fresh?s.target[i]:0;double tau=t>s.value[i]?25:100;s.value[i]+=(t-s.value[i])*(float)(1-Math.exp(-dt/tau));if(s.value[i]<.001f)s.value[i]=0;out[i]=s.value[i];}if(!fresh)java.util.Arrays.fill(s.target,0);consumed++;}
 public static synchronized float sample(int mode,long now){float[] a=new float[BANDS];spectrum(mode,now,a);float max=0;for(float v:a)max=Math.max(max,v);return max;}
 public static float sampleUi(long now){return sample(2,now);}
 public static int queued(int mode){state(mode);return worker.getQueue().size();}
}
