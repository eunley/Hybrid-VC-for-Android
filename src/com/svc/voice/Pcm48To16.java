package com.svc.voice;
/** Stateful lowpass FIR decimator; byte and sample phase survive arbitrary IPC reads. */
public final class Pcm48To16 {
    private final double[] taps=new double[127];
    private final short[] ring=new short[127];
    private int pos,phase,low=-1;
    public Pcm48To16(){double sum=0;for(int i=0;i<taps.length;i++){double x=i-63;double h=x==0?0.30:Math.sin(Math.PI*0.30*x)/(Math.PI*x);h*=0.54-0.46*Math.cos(2*Math.PI*i/126);taps[i]=h;sum+=h;}for(int i=0;i<taps.length;i++)taps[i]/=sum;}
    public float[] accept(byte[] b,int n){float[] out=new float[(n/2+2)/3+1];int used=0;for(int i=0;i<n;i++){if(low<0){low=b[i]&255;continue;}short v=(short)(low|((b[i]&255)<<8));low=-1;ring[pos]=v;pos=(pos+1)%ring.length;if(++phase==3){phase=0;double y=0;for(int k=0;k<taps.length;k++)y+=taps[k]*ring[(pos-1-k+ring.length)%ring.length];out[used++]=(float)(y/32768);}}return java.util.Arrays.copyOf(out,used);}
}
