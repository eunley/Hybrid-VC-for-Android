package com.svc.voice;
/** Per-drop fixed storage. Direction is latched once per growth cycle. */
public final class LockedTail {
 public static final int GROW=1,HOLD=2,FADE=3,REBIRTH=4;
 public int phase; public float axisX,axisY=1,lockX,lockY=1,length,stretch,alpha=1,travel,originX,originY,tipX,tipY;
 public void reset(){phase=0;length=stretch=travel=0;alpha=1;}
 public void limit(float x,float y,float r){float dot=lockX*x+lockY*y;if(dot<.9063078f){float cross=x*lockY-y*lockX;float sn=cross<0?-.4226183f:.4226183f;lockX=x*.9063078f-y*sn;lockY=y*.9063078f+x*sn;tipX=originX-lockX*(length+r*1.2f);tipY=originY-lockY*(length+r*1.2f);}}
 public void update(float x,float y,float vx,float vy,float r,float dt){
  float speed=(float)Math.hypot(vx,vy);if(speed>.01f){axisX=vx/speed;axisY=vy/speed;}
  if(phase==0||phase==REBIRTH){lockX=axisX;lockY=axisY;originX=x;originY=y;length=travel=stretch=0;alpha=1;phase=GROW;}
  float target=r*3.2f;
  if(phase==GROW){length=Math.min(target,length+dt*r*9);if(length>=target)phase=HOLD;}
  float mismatch=(float)Math.sqrt(Math.max(0,2-2*(axisX*lockX+axisY*lockY)));
  travel+=speed*dt*mismatch;
  tipX=originX-lockX*(length+r*1.2f);tipY=originY-lockY*(length+r*1.2f);
  float separation=(float)Math.hypot(x-originX,y-originY);
  travel=Math.max(travel,Math.max(0,separation-r*2));
  stretch=travel/Math.max(1,r*9);
  if(stretch>.12f)phase=FADE;
  alpha=Math.max(0,1-Math.max(0,stretch-.12f)/.88f);
  if(alpha<=0)phase=REBIRTH;
 }
}
