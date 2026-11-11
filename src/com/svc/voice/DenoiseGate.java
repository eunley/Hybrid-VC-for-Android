package com.svc.voice;
import java.util.*;
/** Stateful RNNoise adapter. Returns time-aligned clean audio; never gates output. */
public final class DenoiseGate {
 interface FrameProcessor { void frame(float[] x); void close(); }
 private final FrameProcessor rn;
 private final float[] pending=new float[160],nativeFrame=new float[480];
 private int count,frames;private long fed,emitted;private boolean ended;
 public long nanos;
 public DenoiseGate(){this(new FrameProcessor(){final RnNoise nativeRn=new RnNoise();public void frame(float[] x){nativeRn.frame(x);}public void close(){nativeRn.close();}});}
 DenoiseGate(FrameProcessor processor){rn=processor;}
 public float[] accept(float[] x,boolean end){long t=System.nanoTime();if(ended)throw new IllegalStateException("RNNoise after EOF");
  float[] out=new float[x.length+640];int len=0;
  for(float v:x){fed++;pending[count++]=v;if(count==160){len=render(out,len);count=0;}}
  if(end){try{if(count>0){Arrays.fill(pending,count,160,0);len=render(out,len);count=0;}Arrays.fill(pending,0);while(emitted<fed)len=render(out,len);}finally{ended=true;rn.close();}}
  nanos+=System.nanoTime()-t;return Arrays.copyOf(out,len);
 }
 private int render(float[] out,int off){
  // Band-limited 16k input: interpolate within this frame, avoiding alias-prone zero stuffing.
  for(int i=0;i<160;i++){float a=pending[i],b=pending[Math.min(159,i+1)];for(int j=0;j<3;j++)nativeFrame[i*3+j]=(a+(b-a)*j/3f)*32768f;}
  rn.frame(nativeFrame);frames++;
  // Official implementation has 960 samples / 20ms array displacement at 48k.
  if(frames<=2)return off;
  for(int i=0;i<160&&emitted<fed;i++){float v=(nativeFrame[i*3]+nativeFrame[i*3+1]+nativeFrame[i*3+2])/(3*32768f);out[off++]=v;emitted++;}return off;
 }
 public void close(){rn.close();}
 /** Compatibility hook: mixing is now owned by ChunkPipeline; no gate is applied. */
 public void apply(float[] x,long start){}
}
