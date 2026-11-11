package com.svc.voice;
import java.util.Arrays;

/** Bounded original/clean history and final, absolute-clock 20ms output limiter. */
final class AudioMix {
 private final boolean residual,limit;
 private final ChunkPipeline.Sink sink;
 private final History raw=new History(),clean=new History();
 private final float[] window=new float[882];
 private int used; private long written; private double referenceEnergy;
 AudioMix(boolean residual,boolean limit,ChunkPipeline.Sink sink){this.residual=residual;this.limit=limit;this.sink=sink;}
 void raw(float[] x){raw.add(x);}
 void clean(float[] x){if(residual)clean.add(x);}
 void audio(float[] x,long start)throws Exception{
  if(start!=written+used)throw new IllegalStateException("noncontiguous mix");
  for(int j=0;j<x.length;j++){
   long at=start+j;float original=raw.sample(at);
   float value=x[j]+(residual?original-clean.sample(at):0);
   if(!Float.isFinite(value))value=0;
   if(limit){window[used++]=value;referenceEnergy+=(double)original*original;if(used==882)flush();}
   else {x[j]=value;}
  }
  if(!limit){sink.audio(x,start);written+=x.length;}
  // Retain the interpolation anchor and any future reference, not the whole file.
  long keep=(start+x.length)*160/441;
  raw.discard(keep);if(residual)clean.discard(keep);
 }
 private void flush()throws Exception{
  if(used==0)return;
  double energy=0,peak=0;for(int i=0;i<used;i++){double v=window[i];energy+=v*v;peak=Math.max(peak,Math.abs(v));}
  double gain=Math.min(1,Math.min(peak>0?1/peak:1,energy>0?Math.sqrt(referenceEnergy/energy):1));
  // Round down very slightly so float rounding cannot raise RMS above its reference.
  if(gain<1)gain*=1-1e-7;
  float[] out=new float[used];for(int i=0;i<used;i++)out[i]=(float)Math.max(-1,Math.min(1,window[i]*gain));
  sink.audio(out,written);written+=used;used=0;referenceEnergy=0;
 }
 void finish()throws Exception{flush();}
 static final class History {
  private float[] data=new float[1024];private int size;private long base;
  void add(float[] x){if(size+x.length>data.length)data=Arrays.copyOf(data,Math.max(data.length*2,size+x.length));System.arraycopy(x,0,data,size,x.length);size+=x.length;}
  float sample(long output){
   long numerator=output*160;long index=numerator/441;double fraction=(numerator%441)/441.0;
   int i=(int)(index-base);if(i<0||i>=size)throw new IllegalStateException("missing reference at "+output);
   float a=data[i],b=data[Math.min(i+1,size-1)];return (float)(a+(b-a)*fraction);
  }
  void discard(long before){int n=(int)Math.min(size,Math.max(0,before-base));System.arraycopy(data,n,data,0,size-n);size-=n;base+=n;}
 }
}
