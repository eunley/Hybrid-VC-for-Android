package com.svc.voice;

/** Invocable without Android or instrumentation: runs the actual RainPhysics engine. */
public final class RainProbe {
 private RainProbe(){}
 private static int checks;
 private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 private static RainPhysics fixture(){RainPhysics p=new RainPhysics();RainPhysics.Surface s=p.surfaces[0];s.live=true;s.left=100;s.top=200;s.right=700;s.bottom=500;return p;}
 private static void extended(){
  RainPhysics p=fixture();RainPhysics.Surface s=p.surfaces[0];
  int small=p.add(RainPhysics.STATIC,400,480,3.5f,0,0,0);for(int n=0;n<120;n++)p.advance(RainPhysics.STEP,false);
  check(p.drops[small].state==RainPhysics.STATIC,"stationary bead remains");
  p=fixture();s=p.surfaces[0];int up=p.add(RainPhysics.STATIC,400,350,4,0,0,0);p.moveSurface(0,100,190,700,490,.05f);p.advance(RainPhysics.STEP,false);check(p.drops[up].vy>0,"upward component downward inertia");
  p=fixture();int down=p.add(RainPhysics.STATIC,400,350,4,0,0,0);p.moveSurface(0,100,210,700,510,.05f);p.advance(RainPhysics.STEP,false);check(p.drops[down].vy<0,"downward component upward inertia");
  p=fixture();s=p.surfaces[0];int light=p.add(RainPhysics.STATIC,250,350,1,0,0,0),heavy=p.add(RainPhysics.STATIC,500,350,4,0,0,0);p.moveSurface(0,100,190,700,490,.05f);p.advance(RainPhysics.STEP,false);check(p.drops[heavy].vy>p.drops[light].vy,"mass increases mobility");
  p=fixture();p.add(RainPhysics.FREE,40,350,2,-1,12000,0);p.advance(RainPhysics.STEP,false);check(p.collisions>0,"swept side hit");
  p=fixture();s=p.surfaces[0];for(int n=0;n<8;n++)s.corners[n]=60;p.add(RainPhysics.FREE,101,130,1,-1,0,9000);p.advance(RainPhysics.STEP,false);check(p.collisions==0,"rounded corner gap");
  p=fixture();s=p.surfaces[0];s.clipLeft=400;p.add(RainPhysics.FREE,200,180,2,-1,0,6000);p.advance(RainPhysics.STEP,false);check(p.collisions==0,"visible clip");
  p=fixture();p.add(RainPhysics.FREE,400,160,3,-1,0,12000);p.advance(RainPhysics.STEP,false);int spray=0;for(RainPhysics.Drop d:p.drops)if(d.state==RainPhysics.DETACHED)spray++;check(spray>0,"velocity splash fragments");check(Math.abs(p.injected-p.liveMass()-p.escaped-p.evaporated)<.001,"splash mass conserved");
  p=fixture();s=p.surfaces[0];s.ownerPage=2;p.add(RainPhysics.STATIC,400,350,2,0,0,0);int free=p.add(RainPhysics.FREE,900,300,2,-1,0,0);int owned=p.add(RainPhysics.DETACHED,800,300,2,-1,0,0);p.drops[owned].ownerPage=2;p.clearOtherPages(1);check(p.drops[free].state!=0&&p.drops[owned].state==0,"page cleanup preserves global free");check(Math.abs(p.injected-p.liveMass()-p.escaped-p.evaporated)<.001,"cleanup mass");
  p=fixture();p.add(RainPhysics.FREE,400,150,2,-1,0,0);p.moveSurface(0,100,100,700,400,RainPhysics.STEP);p.advance(RainPhysics.STEP,false);check(p.collisions>0,"moving surface relative sweep");
 }

 public static String run(){
  RainPhysics p=new RainPhysics();p.width=1080;p.height=1920;
  RainPhysics.Surface s=p.surfaces[0];s.live=true;s.left=30;s.right=1000;s.top=200;s.bottom=450;
  p.add(RainPhysics.FREE,200,170,3,-1,0,1400);
  p.advance(.05f,false);boolean swept=p.collisions>0;
  p.clear();p.add(RainPhysics.STATIC,300,300,2,0,0,0);p.add(RainPhysics.STATIC,303,300,2,0,0,0);
  p.advance(RainPhysics.STEP,false);boolean merge=p.merges>0;double error=Math.abs(p.injected-p.liveMass()-p.escaped-p.evaporated);
  p.clear();p.add(RainPhysics.SLIDING,500,445,10,0,0,100);
  for(int i=0;i<120;i++)p.advance(RainPhysics.STEP,false);boolean detached=p.drips>0;
  p.clear();int pin=p.add(RainPhysics.STATIC,500,300,2,0,0,0);for(int i=0;i<60;i++)p.advance(RainPhysics.STEP,false);boolean pinned=Math.abs(p.drops[pin].y-300)<.01;
  float before=p.drops[pin].y;p.moveSurface(0,s.left,s.top-12,s.right,s.bottom-12,.1f);boolean scrolling=Math.abs(p.drops[pin].y-before+12)<.1;s.vy=0;
  p.clear();int edge=p.add(RainPhysics.SLIDING,500,445,10,0,0,100);p.advance(RainPhysics.STEP,false);boolean direct=p.drops[edge].state==RainPhysics.DETACHED;for(int i=0;i<24;i++)p.advance(RainPhysics.STEP,false);boolean released=p.drops[edge].surface<0;
  p.clear();p.intensity=100;for(int i=0;i<3600;i++)p.advance(RainPhysics.STEP,true);
  return "pinned="+pinned+" scrolling="+scrolling+" direct="+direct+" released="+released+" capacity="+RainPhysics.CAPACITY+" trails="+RainPhysics.TRAILS+" swept="+swept+" merge="+merge+" mergeMassError="+error+" detached="+detached+" "+p.statistics();
 }
 public static void main(String[] args){extended();String result=run();check(!result.contains("=false"),"legacy regression: "+result);System.out.println("checks="+checks+" failures=0 "+result);}
}
