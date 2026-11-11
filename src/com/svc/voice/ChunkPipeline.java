package com.svc.voice;
import java.util.Arrays;
/** File scheduler with bounded streaming storage. A final backshift is chosen only at EOF. */
public final class ChunkPipeline implements AutoCloseable {
 public interface Converter { float[] convert(float[] x) throws Exception; }
 public interface Sink { void audio(float[] x,long start) throws Exception; }
 public interface Observer { void begin(long start,int real); void complete(long start,int real,long nanos); }
 public final int full,step,xf;
 private final AudioMix mix; private final DenoiseGate filter; private long extraNanos;
 private final Converter converter; private final Sink sink; private final Observer observer;
 private float[] input=new float[0],previous; private long base,received,previousInput=-1,previousOutput,committed; private boolean ended;
 public ChunkPipeline(int frames,int fade,Converter c,Sink s,Observer o){
  this(frames,fade,c,s,o,false);
 }
 public ChunkPipeline(int frames,int fade,Converter c,Sink s,Observer o,boolean denoise){
  this(frames,fade,c,s,o,denoise,false);
 }
 public ChunkPipeline(int frames,int fade,Converter c,Sink s,Observer o,boolean denoise,boolean volumeLimit){
  this(frames,fade,c,s,o,denoise?new DenoiseGate():null,volumeLimit);
 }
 ChunkPipeline(int frames,int fade,Converter c,Sink s,Observer o,DenoiseGate denoiser,boolean volumeLimit){
  if(frames<1)throw new IllegalArgumentException("frames must be positive");
  filter=denoiser;
  xf=Math.max(0,Math.min(fade,frames/2));full=(int)Math.round(frames*512.0*16000/44100);step=(int)Math.round(Math.max(1,frames-xf)*512.0*16000/44100);converter=c;sink=s;observer=o;
  mix=(filter!=null||volumeLimit)?new AudioMix(filter!=null,volumeLimit,s):null;
 }
 public static long outputSample(long input){return Math.round(input*44100.0/16000);}
 public void accept(float[] x) throws Exception {
  if(ended)throw new IllegalStateException("after EOF");
  // Slice before denoising and reference retention: a whole-file accept stays bounded.
  for(int pos=0;pos<x.length;pos+=160){
   float[] block=Arrays.copyOfRange(x,pos,Math.min(x.length,pos+160));
   if(mix!=null)mix.raw(block);
   float[] clean=filter==null?block:filter.accept(block,false);
   if(mix!=null)mix.clean(clean);
   acceptReady(clean);
  }
 }
 private void acceptReady(float[] x) throws Exception {
  // Bounded staging even when a caller supplies an entire file in one accept.
  for(int pos=0;pos<x.length;){int n=Math.min(full,x.length-pos),old=input.length;input=Arrays.copyOf(input,old+n);System.arraycopy(x,pos,input,old,n);received+=n;pos+=n;
   while(received >= (previousInput<0?0:previousInput+step)+full){long start=previousInput<0?0:previousInput+step;render(start,full);discard();}
  }
 }
 private void discard(){long keep=Math.max(0,previousInput);int n=(int)(keep-base);if(n>0){input=Arrays.copyOfRange(input,n,input.length);base=keep;}}
 private void render(long start,int real) throws Exception {
  float[] segment=new float[full];System.arraycopy(input,(int)(start-base),segment,0,real);if(real<full)Arrays.fill(segment,real,full,segment[real-1]);
  if(observer!=null)observer.begin(start,real);long t=System.nanoTime();float[] y=converter.convert(segment);long ns=System.nanoTime()-t;
  if(y.length==0)throw new IllegalStateException("empty decoder output");
  long a=outputSample(start);
  if(previous!=null){int overlap=(int)Math.max(0,Math.min(previousOutput+previous.length,a+y.length)-a);long seam=a+overlap/2;int width=Math.min(xf*512,overlap);long left=Math.max(committed,seam-width/2),right=Math.min(outputSample(received),left+width);
   float[] out=new float[(int)(right-committed)];for(int j=0;j<out.length;j++){long i=committed+j;if(i<left)out[j]=previous[(int)Math.min(previous.length-1,i-previousOutput)];else{float w=(i-left+1f)/(right-left+1f);out[j]=previous[(int)(i-previousOutput)]*(1-w)+y[(int)(i-a)]*w;}}
   if(out.length>0)emit(out,committed);committed=right;
  }
  previous=y;previousInput=start;previousOutput=a;if(observer!=null)observer.complete(start,real,ns+takeExtra());
 }
 private void emit(float[] x,long start) throws Exception {if(mix!=null)mix.audio(x,start);else sink.audio(x,start);}
 public void finish() throws Exception {
  if(ended)return;if(filter!=null){float[] clean=filter.accept(new float[0],true);mix.clean(clean);acceptReady(clean);}ended=true;if(received==0)return;
  if(previous==null)render(0,(int)received);
  else if(previousInput+full<received)render(Math.max(0,received-full),full);
  long total=outputSample(received);float[] tail=new float[(int)(total-committed)];for(int j=0;j<tail.length;j++)tail[j]=previous[(int)Math.min(previous.length-1,committed+j-previousOutput)];if(tail.length>0)emit(tail,committed);committed=total;if(mix!=null)mix.finish();
 }
 private long takeExtra(){if(filter==null)return 0;long n=filter.nanos-extraNanos;extraNanos=filter.nanos;return n;}
 public void close(){if(filter!=null)filter.close();}
 public long received(){return received;}
 public long emitted(){return committed;}
}
