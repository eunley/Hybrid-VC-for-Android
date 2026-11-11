package com.svc.voice;
/** Pure screen-space direction filter and projection geometry. */
public final class RainDirection {
 public float x=0,y=1; private boolean valid;
 public void reset(){x=0;y=1;valid=false;}
 public void sample(float gx,float gy,float dt){float n=(float)Math.hypot(gx,gy);if(n<1.4f)return;gx/=n;gy/=n;if(!valid){valid=true;}float a=(float)Math.atan2(x*gy-y*gx,x*gx+y*gy);if(Math.abs(a)<.012f)return;float step=a*(1-(float)Math.exp(-Math.min(.1f,Math.max(0,dt))/.22f));float c=(float)Math.cos(step),s=(float)Math.sin(step),nx=x*c-y*s;y=x*s+y*c;x=nx;}
 public static float min(float x,float y,float w,float h){return Math.min(0,x*w)+Math.min(0,y*h);}
 public static float span(float x,float y,float w,float h){return Math.abs(x)*w+Math.abs(y)*h;}
 public static void map(float x,float y,int rotation,float[] out){switch(rotation){case 1:out[0]=y;out[1]=-x;break;case 2:out[0]=-x;out[1]=-y;break;case 3:out[0]=-y;out[1]=x;break;default:out[0]=x;out[1]=y;}}
}
