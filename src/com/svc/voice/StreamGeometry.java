package com.svc.voice;
/** Shared fixed cubic samples, head to visible tail. */
public final class StreamGeometry {
 public static final int N=49;public final float[] x=new float[N],y=new float[N],width=new float[N],alpha=new float[N],nx=new float[N],ny=new float[N];public float minX,maxX,minY,maxY;
 public int nearest(float px,float py){int best=0;float d=Float.POSITIVE_INFINITY;for(int i=0;i<N;i++){if(alpha[i]<.18f)continue;float q=(px-x[i])*(px-x[i])+(py-y[i])*(py-y[i]);if(q<d){d=q;best=i;}}return best;}
 public void build(RainPhysics.Drop d){float r=d.radius(),ax=d.middle.axisX,ay=d.middle.axisY;
 // Three sections: short front, long middle, long tail. Nonzero end radii
 // describe round caps, also used by the segment-distance collision field.
 float w=r*.96f,px=d.x-ax*r,py=d.y-ay*r;
 section(0,d.x+ax*r*.55f,d.y+ay*r*.55f,d.x,d.y,d.x,d.y,px,py,r*1.02f,w,1,1);
 float lx=ax,ly=ay,a=1,ww=w;
 for(int k=0;k<2;k++){LockedTail t=k==0?d.middle:d.tail;float h=Math.min(r*2,Math.max(r*.15f,(float)Math.hypot(t.tipX-px,t.tipY-py)*.3f));float wa=r*(k==0?.65f:.25f),aa=a*t.alpha*(k==0?.82f:.35f);section((k+1)*16,px,py,px-lx*h,py-ly*h,t.tipX+t.lockX*h,t.tipY+t.lockY*h,t.tipX,t.tipY,ww,wa,a,aa);px=t.tipX;py=t.tipY;lx=t.lockX;ly=t.lockY;ww=wa;a=aa;}
 minX=minY=Float.POSITIVE_INFINITY;maxX=maxY=Float.NEGATIVE_INFINITY;for(int i=0;i<N;i++)if(alpha[i]>=.18f){minX=Math.min(minX,x[i]-width[i]);maxX=Math.max(maxX,x[i]+width[i]);minY=Math.min(minY,y[i]-width[i]);maxY=Math.max(maxY,y[i]+width[i]);}}
 private void section(int start,float x0,float y0,float x1,float y1,float x2,float y2,float x3,float y3,float w0,float w1,float a0,float a1){for(int k=0;k<=16;k++){int i=start+k;float t=k/16f,u=1-t;x[i]=u*u*u*x0+3*u*u*t*x1+3*u*t*t*x2+t*t*t*x3;y[i]=u*u*u*y0+3*u*u*t*y1+3*u*t*t*y2+t*t*t*y3;float dx=3*u*u*(x1-x0)+6*u*t*(x2-x1)+3*t*t*(x3-x2),dy=3*u*u*(y1-y0)+6*u*t*(y2-y1)+3*t*t*(y3-y2),n=Math.max(.001f,(float)Math.hypot(dx,dy));nx[i]=dy/n;ny[i]=-dx/n;float q=t*t*(3-2*t);width[i]=w0+(w1-w0)*q;alpha[i]=a0+(a1-a0)*q;}}
}
