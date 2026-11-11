package com.svc.voice;

import java.util.Arrays;
import java.util.Random;

/** Pixel units, mass = projected water area (pi*r*r). Fixed, bounded storage. */
public final class RainPhysics {
 public static final int FREE=1,SPLASH=2,STATIC=3,SLIDING=4,EDGE=5,PENDANT=6,DETACHED=7;
 public static final int CAPACITY=2048, SURFACES=96, TRAILS=1024;
 public boolean edgeSlidingEnabled=true;
 public static float edgeSlideAngle(float radius){return 5+70*Math.max(0,Math.min(1,(radius-4)/16));}
 public float gravityX=0,gravityY=1;
 public void setGravity(float x,float y){float n=(float)Math.hypot(x,y);if(n<.01f)return;gravityX=x/n;gravityY=y/n;}
 public static final float STEP=1f/120f;
 public static final class Drop { public boolean residue; public int residueParent=-1,residueCount; public long residueGeneration,residueIdentity; public float residueDistance,residueGap; public long generation,followGeneration; public int follow=-1; public float followTime; public final StreamGeometry geometry=new StreamGeometry(); public int state, surface=-1; public int departedSurface=-1; public long departedIdentity; public int ownerPage=-1; public float x,y,px,py,vx,vy,mass,age,rainSpeed,grace; public boolean draining; public int drainCorner=-1; public float drainSpeed; public boolean entering; public float entryNX,entryNY,entrySpeed,entryClock; public float edgeStall; public float slideAngle,slideTarget,slideClock; public void resetSlide(){edgeStall=0;slideAngle=slideTarget=slideClock=0;} public final LockedTail middle=new LockedTail(),tail=new LockedTail(); public void resetTail(){middle.reset();tail.reset();follow=-1;followTime=0;} public float radius(){return (float)Math.sqrt(mass/Math.PI);} }
 public static final class Surface { public int ownerPage=-1; public long identity; public float clipLeft=Float.NEGATIVE_INFINITY,clipTop=Float.NEGATIVE_INFINITY,clipRight=Float.POSITIVE_INFINITY,clipBottom=Float.POSITIVE_INFINITY; public float left,top,right,bottom,vx,vy,oldLeft,oldTop,impulseX,impulseY,sweepX,sweepY; public boolean live; public final float[] corners=new float[8];
 public float edge(float x,boolean bottomEdge){float w=right-left,h=bottom-top;int i=bottomEdge?(x<(left+right)/2?6:4):(x<(left+right)/2?0:2);float rx=Math.min(w/2,corners[i]),ry=Math.min(h/2,corners[i+1]);float base=bottomEdge?bottom:top;if(rx<=0||ry<=0)return base;float cx=(i==0||i==6)?left+rx:right-rx;float q=(x-cx)/rx;if((i==0||i==6)?x>=cx:x<=cx)return base;q=Math.max(-1,Math.min(1,q));float inset=ry*(1-(float)Math.sqrt(1-q*q));return bottomEdge?base-inset:base+inset;} }
 public void moveSurface(int index,float left,float top,float right,float bottom,float dt){Surface s=surfaces[index];float dx=left-s.left,dy=top-s.top;s.oldLeft=s.left;s.oldTop=s.top;s.left=left;s.top=top;s.right=right;s.bottom=bottom;float nvx=dx/Math.max(STEP,dt),nvy=dy/Math.max(STEP,dt);s.impulseX-=nvx-s.vx;s.impulseY-=nvy-s.vy;s.vx=nvx;s.vy=nvy;s.sweepX+=dx;s.sweepY+=dy;for(Drop d:drops)if(d.state!=0&&d.surface==index){d.x+=dx;d.px+=dx;d.y+=dy;d.py+=dy;}}
 public static final class Trail { public float x,y,x2,y2,mass,age; public int surface,ownerPage=-1; }
 public final Drop[] drops=new Drop[CAPACITY]; public final Surface[] surfaces=new Surface[SURFACES]; public final Trail[] trails=new Trail[TRAILS];
 private final boolean[] mergedStep=new boolean[CAPACITY];
 private final int[] heads=new int[4096],next=new int[CAPACITY],gx=new int[CAPACITY],gy=new int[CAPACITY];
 private final Random slideRandom=new Random(0x534c494445L);
 private final Random random=new Random(0x5241494eL); private float spawnCredit; private int trailCursor;
 public float width=1080,height=1920;
 /** Spawn size is physical from birth: contact and splash never manufacture mass. */
 public static final float SPLASH_RADIUS_SCALE=2.25f;
 public float rainRadiusScale=2.25f, rainMinimumRadius=1.1f;
 public long spawned; public int intensity=20;
 public long steps,merges,collisions,drips,poolMisses,pairTests,stepNanos,maxStepNanos;
 public double injected,escaped,evaporated; public float accumulator;
 public RainPhysics(){for(int i=0;i<CAPACITY;i++)drops[i]=new Drop();for(int i=0;i<SURFACES;i++)surfaces[i]=new Surface();for(int i=0;i<TRAILS;i++)trails[i]=new Trail();}
 public int add(int state,float x,float y,float radius,int surface,float vx,float vy){for(int i=0;i<CAPACITY;i++)if(drops[i].state==0){Drop d=drops[i];d.resetTail();d.resetSlide();d.generation++;d.entering=false;d.draining=false;d.drainCorner=-1;resetResidue(d);d.state=(state==EDGE||state==PENDANT)?DETACHED:state;if(state==EDGE||state==PENDANT)surface=-1;d.x=d.px=x;d.y=d.py=y;d.mass=(float)Math.PI*radius*radius;d.surface=surface;d.vx=vx;d.vy=vy;d.rainSpeed=state==FREE?(float)Math.hypot(vx,vy)/speedScale():0;d.age=0;d.grace=0;d.departedSurface=-1;d.departedIdentity=0;d.ownerPage=surface>=0?surfaces[surface].ownerPage:-1;injected+=d.mass;return i;}poolMisses++;return -1;}
 public void clearOtherPages(int page){for(Drop d:drops)if(d.state!=0&&d.ownerPage!=-1&&d.ownerPage!=page){escaped+=d.mass;d.state=0;d.resetTail();d.resetSlide();}for(Trail t:trails)if(t.mass>0&&t.ownerPage!=-1&&t.ownerPage!=page){evaporated+=t.mass;t.mass=0;}}
 public void retireSurface(int index){for(Drop d:drops)if(d.state!=0&&d.surface==index){escaped+=d.mass;d.state=0;d.resetTail();d.resetSlide();}for(Trail t:trails)if(t.mass>0&&t.surface==index){evaporated+=t.mass;t.mass=0;}}
 public void clear(){for(Drop d:drops){d.state=0;d.resetTail();d.resetSlide();}for(Trail t:trails)t.mass=0;injected=escaped=evaporated=0;accumulator=spawnCredit=0;for(Surface s:surfaces){s.impulseX=s.impulseY=s.sweepX=s.sweepY=s.vx=s.vy=0;}}
 public void advance(float seconds,boolean spawn){accumulator+=Math.max(0,Math.min(.1f,seconds));int n=0;while(accumulator>=STEP&&n++<12){step(spawn);accumulator-=STEP;}}
 /** Continuous monotone speed curve, calibrated to the default intensity 20. */
 public float speedScale(){return .5f+Math.max(0,Math.min(100,intensity))*.025f;}
 private static int hash(int x,int y){return (x*73856093^y*19349663)&4095;}
 private void step(boolean spawn){long start=System.nanoTime();steps++;
  if(spawn&&intensity>0){spawnCredit+=STEP*Math.max(0,Math.min(100,intensity))*3.84f;while(spawnCredit>=1){spawnCredit--;float speed=(300+random.nextFloat()*650)*speedScale();float x=random.nextFloat()*width,y=random.nextFloat()*height;
 float t=Float.POSITIVE_INFINITY;if(gravityX>0.0001f)t=Math.min(t,x/gravityX);if(gravityX<-.0001f)t=Math.min(t,(x-width)/gravityX);if(gravityY>.0001f)t=Math.min(t,y/gravityY);if(gravityY<-.0001f)t=Math.min(t,(y-height)/gravityY);
 if(add(FREE,x-gravityX*(t+12),y-gravityY*(t+12),Math.max(rainMinimumRadius,1.1f+random.nextFloat()*1.5f)*rainRadiusScale,-1,gravityX*speed,gravityY*speed)>=0)spawned++;}}
  for(Trail t:trails)if(t.mass>0){t.age+=STEP;if(t.age>2.5f){evaporated+=t.mass;t.mass=0;}}
  residueLive=0;for(Drop d:drops)if(d.state!=0&&d.residue)residueLive++;
  for(Drop d:drops){if(d.state==0)continue;d.px=d.x;d.py=d.y;d.age+=STEP;float r=d.radius();
   if(d.surface>=0){Surface s=surfaces[d.surface];if(!s.live){d.surface=-1;d.state=DETACHED;}else{

    boolean entering=d.entering;
    boolean draining=entering?advanceEntry(d,s,r):drainEdge(d,s,r);
    if(d.surface<0)continue;
    float mobility=Math.min(1,d.mass/720f);
    d.vx+=s.impulseX*mobility;d.vy+=s.impulseY*mobility;
    if(d.state==STATIC&&Math.hypot(d.vx,d.vy)>18/Math.max(.1f,mobility))d.state=SLIDING;
    if(d.state==SPLASH&&d.age>.18f)d.state=r<7.2f?STATIC:SLIDING;
    if(d.state==STATIC&&r>=7.2f)d.state=SLIDING;
    if(d.state==SPLASH&&!entering){d.x+=d.vx*STEP;d.y+=d.vy*STEP;}
    if(d.state==SLIDING&&!draining&&!entering){float target=Math.min(750,(r*.5f-3.2f)*(r*.5f-3.2f)*14);d.slideClock-=STEP;if(d.slideClock<=0){float candidate=(slideRandom.nextFloat()-slideRandom.nextFloat())*.48f;
      // Opposite side: redraw once, then accept either side without recentering.
      if(d.slideTarget*candidate<0)candidate=(slideRandom.nextFloat()-slideRandom.nextFloat())*.48f;
      d.slideTarget=candidate;d.slideClock=.15f+slideRandom.nextFloat()*.20f;}
     d.slideAngle+=(d.slideTarget-d.slideAngle)*.0884352f; // 1-exp(-STEP/.09)
     float cs=(float)Math.cos(d.slideAngle),sn=(float)Math.sin(d.slideAngle);
     float dirX=gravityX*cs-gravityY*sn,dirY=gravityY*cs+gravityX*sn;
     target*=Math.max(0,cs);
     if(validFollow(d)){Drop b=drops[d.follow];StreamGeometry g=b.geometry;int k=g.nearest(d.x,d.y);float dx=g.x[Math.max(0,k-1)]-d.x,dy=g.y[Math.max(0,k-1)]-d.y;float nn=(float)Math.hypot(dx,dy);if(nn>.01f){dirX=dx/nn;dirY=dy/nn;}target=Math.min(1100,Math.max(target,(float)Math.hypot(b.vx,b.vy)+180));d.followTime+=STEP;if(d.followTime>8){d.follow=-1;d.followTime=0;}}d.vy+=(dirY*target-d.vy)*.06f;d.vx+=(dirX*target-d.vx)*.06f;float ox=d.x,oy=d.y;d.x+=d.vx*STEP;d.y+=d.vy*STEP;float distance=(float)Math.hypot(d.x-ox,d.y-oy);leaveResidue(d,s,ox,oy,distance);if(distance>.02f){float debit=Math.min(d.mass*.003f,distance*r*.07f);Trail t=trails[trailCursor++%TRAILS];if(t.mass>0)evaporated+=t.mass;t.x=ox-s.left;t.y=oy-s.top;t.x2=d.x-s.left;t.y2=d.y-s.top;t.surface=d.surface;t.ownerPage=d.ownerPage;t.mass=debit;t.age=0;d.mass-=debit;}}
    if(d.surface>=0){if(d.entering||d.draining)continue;d.x=Math.max(s.left,Math.min(s.right,d.x));d.y=Math.max(s.edge(d.x,false),Math.min(s.edge(d.x,true),d.y));continue;}
   }}
   if(d.state==FREE||d.state==DETACHED){if(d.state==FREE){float target=d.rainSpeed*speedScale();float blend=1-(float)Math.exp(-STEP*5);d.vy+=(gravityY*target-d.vy)*blend;d.vx+=(gravityX*target-d.vx)*blend;}if(d.state==DETACHED){d.vx+=gravityX*1200*STEP;d.vy+=gravityY*1200*STEP;}d.x+=d.vx*STEP;d.y+=d.vy*STEP;
    float earliest=2;int hit=-1;
    for(int k=0;k<SURFACES;k++){Surface s=surfaces[k];if(!s.live)continue;if(d.departedSurface>=0 && s.identity==d.departedIdentity){
     if(d.age<d.grace || distance(s,d.px+s.sweepX,d.py+s.sweepY)<=r+.05f)continue;
     d.departedSurface=-1;
     // The first separated sweep belongs to departure, never reattachment.
     continue;
    }float t=contact(d,s,r);if(t<earliest){earliest=t;hit=k;}}
    if(hit>=0&&earliest<=1){Surface s=surfaces[hit];d.x=d.px+(d.x-d.px)*earliest+s.sweepX*(1-earliest);d.y=d.py+(d.y-d.py)*earliest+s.sweepY*(1-earliest);d.surface=hit;d.ownerPage=s.ownerPage;d.state=SPLASH;d.age=0;float rvx=d.vx-s.vx,rvy=d.vy-s.vy;float speed=(float)Math.hypot(rvx,rvy);float nx=distance(s,d.x+.25f,d.y)-distance(s,d.x-.25f,d.y),ny=distance(s,d.x,d.y+.25f)-distance(s,d.x,d.y-.25f);
     float nn=(float)Math.hypot(nx,ny);if(nn<1e-5f){nx=-rvx;ny=-rvy;nn=Math.max(1e-5f,speed);}nx/=nn;ny/=nn;
     d.draining=false;d.drainCorner=-1;d.entering=true;d.entryNX=nx;d.entryNY=ny;d.entryClock=0;d.entrySpeed=Math.max(24,-(rvx*nx+rvy*ny)*.65f);
     float tangent=rvx*(-ny)+rvy*nx;d.vx=-nx*d.entrySpeed-ny*tangent*.12f;d.vy=-ny*d.entrySpeed+nx*tangent*.12f;collisions++;spray(d,speed,rvx,rvy);}

   }
   if(d.y+r< -40||d.y-r>height+40||d.x+r< -40||d.x-r>width+40||d.age>35){escaped+=d.mass;d.state=0;d.resetTail();d.resetSlide();}
  }
  for(Surface s:surfaces){s.impulseX=s.impulseY=s.sweepX=s.sweepY=0;}
  Arrays.fill(mergedStep,false);Arrays.fill(heads,-1);float maxRadius=0,maxMove=0;for(int i=0;i<CAPACITY;i++){Drop d=drops[i];if(d.state==0)continue;maxRadius=Math.max(maxRadius,d.radius());maxMove=Math.max(maxMove,(float)Math.hypot(d.x-d.px,d.y-d.py));gx[i]=(int)Math.floor(d.x/32);gy[i]=(int)Math.floor(d.y/32);int h=hash(gx[i],gy[i]);next[i]=heads[h];heads[h]=i;}
  for(int i=0;i<CAPACITY;i++){Drop a=drops[i];if(a.state==0)continue;int reach=Math.min(16,(int)Math.ceil((a.radius()+maxRadius+maxMove*2)/32));for(int x=gx[i]-reach;x<=gx[i]+reach;x++)for(int y=gy[i]-reach;y<=gy[i]+reach;y++)for(int j=heads[hash(x,y)];j>=0;j=next[j]){if(j<=i||mergedStep[i]||mergedStep[j]||gx[j]!=x||gy[j]!=y)continue;Drop b=drops[j];if(b.state==0||(a.surface<0&&(a.age<a.grace||b.age<b.grace))||a.surface!=b.surface||a.ownerPage!=b.ownerPage)continue;if(protectedResidue(a,b,j)||protectedResidue(b,a,i))continue;pairTests++;float dx=a.px-b.px,dy=a.py-b.py,ux=(a.x-a.px)-(b.x-b.px),uy=(a.y-a.py)-(b.y-b.py);float den=ux*ux+uy*uy,t=den>0?Math.max(0,Math.min(1,-(dx*ux+dy*uy)/den)):0;float rr=a.radius()+b.radius();if((dx+ux*t)*(dx+ux*t)+(dy+uy*t)*(dy+uy*t)>rr*rr)continue;float m=a.mass+b.mass;if(a.follow==j){a.x=b.x;a.y=b.y;}else if(b.follow!=i){a.x=(a.x*a.mass+b.x*b.mass)/m;a.y=(a.y*a.mass+b.y*b.mass)/m;}a.vx=(a.vx*a.mass+b.vx*b.mass)/m;a.vy=(a.vy*a.mass+b.vy*b.mass)/m;a.mass=m;a.residue=a.residue&&b.residue;a.residueCount=Math.max(a.residueCount,b.residueCount);a.px=a.x;a.py=a.y;b.state=0;b.resetTail();b.resetSlide();a.resetTail();a.resetSlide();mergedStep[i]=mergedStep[j]=true;merges++;break;}}
  for(Drop d:drops){if(d.state==SLIDING){float r=d.radius();LockedTail previous=null;float x=d.x,y=d.y,vx=d.vx,vy=d.vy;
   for(int k=0;k<2;k++){LockedTail t=k==0?d.middle:d.tail;float ox=t.tipX,oy=t.tipY;boolean reborn=t.phase==LockedTail.REBIRTH;t.update(x,y,vx,vy,r*1.35f,STEP);if(previous!=null)t.limit(previous.lockX,previous.lockY,r*1.35f);if(reborn&&k==0){LockedTail child=d.tail;child.originX+=t.tipX-ox;child.originY+=t.tipY-oy;}previous=t;x=t.tipX;y=t.tipY;float speed=(float)Math.hypot(d.vx,d.vy);vx=t.lockX*speed;vy=t.lockY*speed;}
   d.geometry.build(d);
  }else {d.resetTail();d.resetSlide();}}
  acquireStreams();
  long duration=System.nanoTime()-start;stepNanos+=duration;maxStepNanos=Math.max(maxStepNanos,duration);
 }
 public long residuesCreated; private int residueLive;
 private final Random residueChanceRandom=new Random(0x48414c46524553L);
 private final Random residueRandom=new Random(0x52455349445545L);
 private void resetResidue(Drop d){d.residue=false;d.residueParent=-1;d.residueCount=0;d.residueDistance=0;d.residueGap=0;d.residueIdentity=0;}
 private boolean protectedResidue(Drop child,Drop parent,int index){
  if(!child.residue||child.residueParent!=index)return false;
  if(child.residueGeneration!=parent.generation){child.residueParent=-1;return false;}
  float rr=child.radius()+parent.radius()+3,dx=child.x-parent.x,dy=child.y-parent.y;
  if(dx*dx+dy*dy>rr*rr&&child.age>.2f){child.residueParent=-1;return false;}return true;
 }
 private void leaveResidue(Drop parent,Surface s,float ox,float oy,float travel){
  if(parent.residue||parent.follow>=0||travel<=.001f)return;
  if(parent.residueIdentity!=s.identity){parent.residueIdentity=s.identity;parent.residueDistance=0;parent.residueGap=0;}
  if(parent.residueGap==0)parent.residueGap=48+residueRandom.nextFloat()*36;
  parent.residueDistance+=travel;if(parent.residueDistance<parent.residueGap)return;
  // One attempt per distance interval, no backlog or stationary spawning.
  parent.residueDistance=0;parent.residueGap=48+residueRandom.nextFloat()*36;
  if(!s.live||s.ownerPage!=parent.ownerPage)return;
  float radius=3+residueRandom.nextFloat(),mass=(float)Math.PI*radius*radius;
  if(parent.mass-mass<Math.PI*81||mass>parent.mass*.045f)return;
  float margin=radius+3;
  if(distance(s,ox,oy)>-margin||ox-margin<s.clipLeft||ox+margin>s.clipRight||oy-margin<s.clipTop||oy+margin>s.clipBottom)return;
  int slot=-1,pi=-1;for(int i=0;i<CAPACITY;i++){if(drops[i]==parent)pi=i;if(slot<0&&drops[i].state==0)slot=i;}
  if(slot<0)return;
  if(residueChanceRandom.nextFloat()>=.5f)return;
  Drop child=drops[slot];child.resetTail();child.resetSlide();resetResidue(child);child.generation++;
  child.entering=false;child.draining=false;child.drainCorner=-1;child.residue=true;child.residueParent=pi;child.residueGeneration=parent.generation;child.residueIdentity=s.identity;
  child.state=STATIC;child.surface=parent.surface;child.ownerPage=parent.ownerPage;child.x=child.px=ox;child.y=child.py=oy;
  child.vx=child.vy=child.age=child.rainSpeed=0;child.grace=.2f;child.departedSurface=-1;child.departedIdentity=0;
  child.mass=mass;parent.mass-=mass;parent.residueCount++;residueLive++;residuesCreated++;
 }
 public long streamHits,streamTests;
 private final int[] streamOrder=new int[CAPACITY]; private int streamCount; private final boolean[] streamLeader=new boolean[CAPACITY];
 private boolean validFollow(Drop a){if(a.follow<0)return false;Drop b=drops[a.follow];if(b.state!=SLIDING||b.generation!=a.followGeneration||b.surface!=a.surface||b.ownerPage!=a.ownerPage||b.follow>=0){a.follow=-1;a.followTime=0;return false;}return true;}
 private void acquireStreams(){streamCount=0;Arrays.fill(streamLeader,false);for(Drop d:drops)if(d.state!=0&&validFollow(d))streamLeader[d.follow]=true;
  for(int i=0;i<CAPACITY;i++)if(drops[i].state==SLIDING){int k=streamCount++;float min=drops[i].geometry.minX;while(k>0&&drops[streamOrder[k-1]].geometry.minX>min){streamOrder[k]=streamOrder[k-1];k--;}streamOrder[k]=i;}
  for(int i=0;i<CAPACITY;i++){Drop a=drops[i];if(a.state!=SLIDING||validFollow(a))continue;if(streamLeader[i])continue;
   float radius=a.radius(),best=Float.POSITIVE_INFINITY;int target=-1;
   for(int z=0;z<streamCount;z++){int j=streamOrder[z];Drop b=drops[j];StreamGeometry g=b.geometry;if(g.minX>a.x+radius)break;if(j==i||b.follow>=0||b.surface!=a.surface||b.ownerPage!=a.ownerPage||g.maxX<a.x-radius||g.minY>a.y+radius||g.maxY<a.y-radius)continue;
    for(int k=1;k<StreamGeometry.N;k++){if(Math.min(g.alpha[k-1],g.alpha[k])<.18f)continue;streamTests++;float dx=g.x[k]-g.x[k-1],dy=g.y[k]-g.y[k-1],den=dx*dx+dy*dy;float t=den>.001f?Math.max(0,Math.min(1,((a.x-g.x[k-1])*dx+(a.y-g.y[k-1])*dy)/den)):0;float x=g.x[k-1]+dx*t,y=g.y[k-1]+dy*t;float ds=(a.x-x)*(a.x-x)+(a.y-y)*(a.y-y),rr=radius+g.width[k-1]+(g.width[k]-g.width[k-1])*t;if(ds<=rr*rr&&ds<best){best=ds;target=j;}}
   }if(target>=0){a.follow=target;a.followGeneration=drops[target].generation;a.followTime=0;streamLeader[target]=true;streamHits++;}
  }
 }
 /** Entry is integrated before perimeter drainage, without position projection.
  * Keep incoming normal momentum until the center is at least one radius inside.
  * Adverse gravity may reverse an unsuccessful entry; release it once, with the
  * same identity separation guard used by normal downstream departures. */
 private boolean advanceEntry(Drop d,Surface s,float r){
  d.entryClock+=STEP;
  float depth=Math.min(Math.max(2,r),Math.min(s.right-s.left,s.bottom-s.top)*.2f);
  if(distance(s,d.x,d.y)<=-depth){d.entering=false;d.state=r<7.2f?STATIC:SLIDING;return true;}
  float nx=d.entryNX,ny=d.entryNY;
  d.entrySpeed+=-(gravityX*nx+gravityY*ny)*720*STEP;
  float tangent=d.vx*(-ny)+d.vy*nx;
  tangent=(tangent+(gravityX*(-ny)+gravityY*nx)*360*STEP)*.995f;
  d.vx=-nx*d.entrySpeed-ny*tangent;d.vy=-ny*d.entrySpeed+nx*tangent;
  d.x+=d.vx*STEP;d.y+=d.vy*STEP;
  if(d.entrySpeed<0&&distance(s,d.x,d.y)>r+.1f || d.entryClock>2f){
   d.entering=false;d.departedSurface=d.surface;d.departedIdentity=s.identity;
   d.surface=-1;d.state=DETACHED;d.age=0;d.grace=STEP*2;d.vx+=s.vx;d.vy+=s.vy;drips++;
  }
  return true;
 }
 // Straight-only drainage: no rounded contour can turn velocity uphill.
 private float cornerRadius(Surface s,int i){return Math.max(0,Math.min((i%2==0?s.right-s.left:s.bottom-s.top)/2,s.corners[i]));}
 private void releaseStraight(Drop d,Surface s){
  d.draining=false;d.departedSurface=d.surface;d.departedIdentity=s.identity;
  d.surface=-1;d.state=DETACHED;d.age=0;d.grace=STEP*2;
  d.vx+=gravityX*80+s.vx;d.vy+=gravityY*80+s.vy;
  d.resetTail();d.resetSlide();drips++;
 }
 private boolean drainEdge(Drop d,Surface s,float r){
  if(!edgeSlidingEnabled){
   if(!d.draining&&(distance(s,d.x,d.y)<-1f||(d.state==STATIC&&Math.hypot(d.vx,d.vy)<.1f&&r<7.2f)))return false;
   releaseStraight(d,s);return true;
  }
  if(!d.draining){
   if(distance(s,d.x,d.y)<-1f)return false;
   if(d.state==STATIC&&Math.hypot(d.vx,d.vy)<.1f&&r<7.2f)return false;
   if(d.state!=STATIC&&d.state!=SLIDING&&d.state!=SPLASH)return false;
   int edge=-1;float best=Float.MAX_VALUE;
   // Only actual straight spans may capture a drop. Rounded spans release.
   for(int e=0;e<4;e++){
    float lo=e%2==0?s.left+cornerRadius(s,e==0?0:6):s.top+cornerRadius(s,e==1?3:1);
    float hi=e%2==0?s.right-cornerRadius(s,e==0?2:4):s.bottom-cornerRadius(s,e==1?5:7);
    float along=e%2==0?d.x:d.y;
    float gap=Math.abs(e==0?d.y-s.top:e==1?d.x-s.right:e==2?d.y-s.bottom:d.x-s.left);
    if(along>=lo-.01f&&along<=hi+.01f&&gap<best){best=gap;edge=e;}
   }
   if(edge<0||best>1.1f){releaseStraight(d,s);return true;}
   d.draining=true;d.drainCorner=edge;d.drainSpeed=edge%2==0?d.vx:d.vy;
  }
  int e=d.drainCorner;boolean horizontal=e%2==0;
  float projection=horizontal?gravityX:gravityY;
  // Acute angle between the straight edge and the current rain direction.
  if(Math.abs(projection)<=(float)Math.sin(Math.toRadians(edgeSlideAngle(r)))){releaseStraight(d,s);return true;}
  float lo=horizontal?s.left+cornerRadius(s,e==0?0:6):s.top+cornerRadius(s,e==1?3:1);
  float hi=horizontal?s.right-cornerRadius(s,e==0?2:4):s.bottom-cornerRadius(s,e==1?5:7);
  float at=horizontal?d.x:d.y;
  float sign=Math.abs(projection)>.0001f?(projection<0?-1:1):at-lo<hi-at?-1:1;
  d.drainSpeed=Math.max(-750,Math.min(750,(d.drainSpeed+(projection*720+sign*280)*STEP)*.995f));
  float next=at+d.drainSpeed*STEP;boolean release=next<=lo||next>=hi;
  next=Math.max(lo,Math.min(hi,next));
  if(horizontal){d.x=next;d.y=e==0?s.top:s.bottom;d.vx=d.drainSpeed;d.vy=0;}
  else {d.y=next;d.x=e==1?s.right:s.left;d.vx=0;d.vy=d.drainSpeed;}
  d.state=SLIDING;d.follow=-1;
  if(release)releaseStraight(d,s);
  return true;
 }
 /** Signed distance to the actual elliptical corner boundary, conservative outside. */
 private float distance(Surface s,float x,float y){
  float dx=Math.max(s.left-x,Math.max(0,x-s.right)),dy=Math.max(s.top-y,Math.max(0,y-s.bottom));

  int i=y<(s.top+s.bottom)*.5f?(x<(s.left+s.right)*.5f?0:2):(x<(s.left+s.right)*.5f?6:4);
  float rx=Math.max(0,Math.min((s.right-s.left)*.5f,s.corners[i])),ry=Math.max(0,Math.min((s.bottom-s.top)*.5f,s.corners[i+1]));
  float cx=(i==0||i==6)?s.left+rx:s.right-rx,cy=i<4?s.top+ry:s.bottom-ry;
  if(rx>0&&ry>0&&((i==0||i==6)?x<cx:x>cx)&&(i<4?y<cy:y>cy))return ((float)Math.hypot((x-cx)/rx,(y-cy)/ry)-1)*Math.min(rx,ry);
  if(dx>0||dy>0)return (float)Math.hypot(dx,dy);
  return -Math.min(Math.min(x-s.left,s.right-x),Math.min(y-s.top,s.bottom-y));
 }
 private float contact(Drop d,Surface s,float r){
  float x=d.px+s.sweepX,y=d.py+s.sweepY,ux=d.x-x,uy=d.y-y,len=(float)Math.hypot(ux,uy),t=0;
  for(int n=0;n<160&&t<=1;n++){float px=x+ux*t,py=y+uy*t,gap=distance(s,px,py)-r;
   if(gap<=.02f){if(px+r<s.clipLeft||px-r>s.clipRight||py+r<s.clipTop||py-r>s.clipBottom)return 2;return t;}
   if(len<.0001f)return 2;t+=Math.max(.00001f,gap/len*.85f);
  }return 2;
 }
 private void spray(Drop parent,float speed,float vx,float vy){
  float budget=parent.mass*Math.min(.65f,Math.max(0,(speed-180)/1800));
  // Sky-radius fragments, fewer visible droplets rather than subpixel spray.
  float desiredRadius=(1.8f+Math.min(.8f,speed/3000f))*SPLASH_RADIUS_SCALE;
  int count=Math.min(12,(int)(budget/((float)Math.PI*desiredRadius*desiredRadius)));
  if(count==0 && budget>=Math.PI*rainMinimumRadius*rainMinimumRadius*SPLASH_RADIUS_SCALE*SPLASH_RADIUS_SCALE)count=1;
  if(count<1)return;
  for(int n=0;n<count;n++){int slot=-1;for(int j=0;j<CAPACITY;j++)if(drops[j].state==0){slot=j;break;}if(slot<0){poolMisses++;break;}
   Drop d=drops[slot];float mass=budget/count;if(mass<.16f)break;parent.mass-=mass;d.resetTail();d.resetSlide();d.generation++;resetResidue(d);d.mass=mass;d.entering=false;d.draining=false;d.drainCorner=-1;d.state=DETACHED;d.surface=-1;d.ownerPage=parent.ownerPage;d.age=0;d.departedSurface=-1;d.departedIdentity=0;d.grace=.05f;float angle=(n+1f)/(count+1)*(float)Math.PI;float side=(float)Math.cos(angle),up=1+(float)Math.sin(angle);d.x=d.px=parent.x+(gravityY*side-gravityX*up)*parent.radius();d.y=d.py=parent.y+(-gravityX*side-gravityY*up)*parent.radius();d.vx=vx*.15f+(gravityY*side*.25f-gravityX*up*.2f)*speed;d.vy=vy*.15f+(-gravityX*side*.25f-gravityY*up*.2f)*speed;
  }
 }
 public double liveMass(){double m=0;for(Drop d:drops)if(d.state!=0)m+=d.mass;for(Trail t:trails)m+=t.mass;return m;}
 public String statistics(){int n=0;for(Drop d:drops)if(d.state!=0)n++;return "spawned="+spawned+" drops="+n+" steps="+steps+" merges="+merges+" collisions="+collisions+" drips="+drips+" poolMisses="+poolMisses+" pairTests="+pairTests+" meanStepUs="+(steps==0?0:stepNanos/steps/1000)+" maxStepUs="+maxStepNanos/1000+" massError="+(injected-liveMass()-escaped-evaporated);}
}
