package com.svc.voice;
/** Producer-thread Hann FFT. Frequencies are logarithmic, powers are absolute full-scale dB. */
final class SpectrumFft {
 static final int N=2048, BANDS=32, HOP=1024;
 final double[] ring=new double[N],re=new double[N],im=new double[N];
 int pos,count,since,rate;
 void reset(int r){pos=count=since=0;rate=r;java.util.Arrays.fill(ring,0);}
 float[] push(float v){ring[pos]=Float.isNaN(v)||Float.isInfinite(v)?0:Math.max(-1,Math.min(1,v));pos=(pos+1)%N;count=Math.min(N,count+1);since++;if(count<N||since<HOP)return null;since=0;return transform();}
 float[] transform(){double sum=0;for(int i=0;i<N;i++){double w=.5-.5*Math.cos(2*Math.PI*i/(N-1));re[i]=ring[(pos+i)%N]*w;im[i]=0;sum+=w;}
 for(int i=1,j=0;i<N;i++){int bit=N>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;}}
 for(int len=2;len<=N;len<<=1){double a=-2*Math.PI/len;for(int base=0;base<N;base+=len){for(int j=0;j<len/2;j++){double c=Math.cos(a*j),s=Math.sin(a*j);int x=base+j,y=x+len/2;double tr=re[y]*c-im[y]*s,ti=re[y]*s+im[y]*c;re[y]=re[x]-tr;im[y]=im[x]-ti;re[x]+=tr;im[x]+=ti;}}}
 double[] power=new double[BANDS];double lo=40,hi=Math.min(16000,rate*.5),log=Math.log(hi/lo);
 for(int k=1;k<=N/2;k++){double hz=k*(double)rate/N;if(hz<lo||hz>hi)continue;int b=Math.min(BANDS-1,(int)(Math.log(hz/lo)/log*BANDS));power[b]+=2*(re[k]*re[k]+im[k]*im[k])/(sum*sum*1.5);}
 float[] out=new float[BANDS];for(int b=0;b<BANDS;b++){double db=10*Math.log10(Math.max(1e-12,power[b]));out[b]=(float)Math.max(0,Math.min(1,(db+72)/72));}return out;
 }
}
