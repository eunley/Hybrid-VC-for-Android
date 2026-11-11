package com.svc.voice;
import java.util.*;
public final class AudioCoreTest {
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 static final class Fake implements DenoiseGate.FrameProcessor {
  float[][] delay={new float[480],new float[480]};int n; final float gain;
  Fake(float g){gain=g;}
  public void frame(float[] x){float[] old=delay[n%2];delay[n%2]=x.clone();for(int i=0;i<480;i++)x[i]=old[i]*gain;n++;}
  public void close(){}
 }
 static float[] run(float[] input,int split,boolean dn,boolean limit,float conversion)throws Exception{
  ArrayList<Float> values=new ArrayList<>();
  ChunkPipeline.Sink sink=(x,start)->{check(start==values.size(),"sink position");for(float v:x)values.add(v);};
  ChunkPipeline.Converter converter=x->{float[] y=new float[(int)ChunkPipeline.outputSample(x.length)];for(int i=0;i<y.length;i++)y[i]=conversion;return y;};
  try(ChunkPipeline p=new ChunkPipeline(8,2,converter,sink,null,dn?new DenoiseGate(new Fake(0.25f)):null,limit)){
   for(int i=0;i<input.length;i+=split)p.accept(Arrays.copyOfRange(input,i,Math.min(input.length,i+split)));
   p.finish();p.finish();check(p.received()==input.length,"input length");check(p.emitted()==ChunkPipeline.outputSample(input.length),"output length");
   boolean rejected=false;try{p.accept(new float[0]);}catch(IllegalStateException e){rejected=true;}check(rejected,"after EOF");
  }
  float[] out=new float[values.size()];for(int i=0;i<out.length;i++)out[i]=values.get(i);return out;
 }
 static float[] reference(float[] x){float[] y=new float[(int)ChunkPipeline.outputSample(x.length)];for(int i=0;i<y.length;i++){double at=i*160.0/441;int j=(int)at;float a=x[Math.min(j,x.length-1)],b=x[Math.min(j+1,x.length-1)];y[i]=(float)(a+(b-a)*(at-j));}return y;}
 static void same(float[] a,float[] b,float eps,String text){check(a.length==b.length,text+" length");for(int i=0;i<a.length;i++)check(Math.abs(a[i]-b[i])<=eps,text+" at "+i+": "+a[i]+" vs "+b[i]);}
 static void limiter(float[] input,float[] out){float[] ref=reference(input);for(int b=0;b<out.length;b+=882){double e=0,r=0;for(int i=b;i<Math.min(out.length,b+882);i++){check(Math.abs(out[i])<=1,"peak");e+=(double)out[i]*out[i];r+=(double)ref[i]*ref[i];}check(e<=r+1e-9,"RMS "+e+">"+r);}}
 public static void main(String[] args)throws Exception{
  for(int n:new int[]{0,1,17,159,160,161,319,320,321,1470,1486,4001,16001}){
   float[] x=new float[n];for(int i=0;i<n;i++)x[i]=(float)(.2*Math.sin(i*.031)+.01);
   for(boolean dn:new boolean[]{false,true})for(boolean limit:new boolean[]{false,true}){
    float[] whole=run(x,Math.max(1,n),dn,limit,2.5f);
    for(int split:new int[]{1,79,160,997})same(whole,run(x,split,dn,limit,2.5f),0,"split");
    if(limit)limiter(x,whole);
   }
  }
  // Zero clean conversion must return the entire background, not gate it away.
  float[] background=new float[5120];Arrays.fill(background,.0001f);
  same(reference(background),run(background,37,true,false,0.000025f),1e-9f,"background reconstruct");
  // Clean zero is a useful identity split: original-clean = original exactly.
  float[] raw=new float[1377];for(int i=0;i<raw.length;i++)raw[i]=(float)Math.sin(i*.2)*.2f;
  ArrayList<Float> identity=new ArrayList<>();
  ChunkPipeline.Sink collect=(a,start)->{for(float v:a)identity.add(v);};
  try(ChunkPipeline p=new ChunkPipeline(8,2,x->new float[(int)ChunkPipeline.outputSample(x.length)],collect,null,new DenoiseGate(new Fake(0)),false)){p.accept(raw);p.finish();}
  float[] y=new float[identity.size()];for(int i=0;i<y.length;i++)y[i]=identity.get(i);same(reference(raw),y,1e-7f,"original reconstruction");
  for(float level:new float[]{0,.001f,.5f,2}){float[] x=new float[657];Arrays.fill(x,level);limiter(x,run(x,1,false,true,20));}
  // A sparse converter spike exercises peak limiting independently of RMS.
  float[] x=new float[320];Arrays.fill(x,.5f);ArrayList<Float> spikes=new ArrayList<>();
  try(ChunkPipeline p=new ChunkPipeline(8,2,a->{float[] v=new float[(int)ChunkPipeline.outputSample(a.length)];v[0]=20;return v;},(a,start)->{for(float v:a)spikes.add(v);},null,false,true)){p.accept(x);p.finish();}
  check(spikes.get(0)<=1&&spikes.get(0)>.99,"spike constrained");
  // Adapter frame delay, partial-frame flush, and no stale post-EOF samples.
  for(int n:new int[]{1,159,160,161,320,321,1003}){float[] a=new float[n];Arrays.fill(a,.4f);DenoiseGate g=new DenoiseGate(new Fake(.25f));float[] first=g.accept(a,false),last=g.accept(new float[0],true);check(first.length+last.length==n,"flush length");for(float v:first)check(Math.abs(v-.1)<1e-6,"delay first");for(int i=0;i<last.length;i++)check(Math.abs(last[i]-.1)<1e-6 || first.length+i==n-1,"delay tail");}
  // One whole-file accept under a 64MB heap: no extra full-file clean/reference copy.
  float[] large=new float[4_000_000];final long[] count={0};
  try(ChunkPipeline p=new ChunkPipeline(8,2,a->new float[(int)ChunkPipeline.outputSample(a.length)],(a,start)->{check(start==count[0],"large position");count[0]+=a.length;},null,new DenoiseGate(new Fake(0)),true)){p.accept(large);p.finish();}
  check(count[0]==ChunkPipeline.outputSample(large.length),"large output");
  System.out.println("PASS audio-core: reconstruction/no-gate, RMS/peak/silence/short EOF, RNNoise delay, split invariance");
 }
}
