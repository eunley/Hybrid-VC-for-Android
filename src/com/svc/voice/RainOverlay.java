package com.svc.voice;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.Random;

/** Canvas geometry and Android 13+ AGSL lens composition over a rain-free UI snapshot. */
public final class RainOverlay extends View {
 private final RainPhysics physics=new RainPhysics();
 private final Paint paint=new Paint(3); private final float[] uniformPair=new float[2],uniformFlash=new float[1]; private final Rect ancestorClip=new Rect();
 private final View[] views=new View[RainPhysics.SURFACES];
 private final int[] location=new int[2],origin=new int[2]; private final Rect visible=new Rect(); private final Outline outline=new Outline(); private long structureScans;
 private final Random random=new Random(); private int count,intensity=20;
 private boolean enabled=true,resumed,scheduled,dirty=true,capturing,observing;
 private float surfaceMotionRemaining;
 private final java.util.IdentityHashMap<View,Integer> pageOwners=new java.util.IdentityHashMap<>();
 private int nextPageOwner,settledOwner=-1;
 private SwipePager pager;
 private boolean captureDirty=true;
 private long previous,lastSnapshot,lastCapture,drawNanos,maxDrawNanos,draws,captureNanos;
 private final long[] drawTimes=new long[2048]; private int timeCount;
 private BitmapShader cachedInput; private final Matrix captureMatrix=new Matrix();
 private Bitmap background; private Canvas backgroundCanvas; private RuntimeShader lens;
 private float flash,flashRemaining,flashDuration=1f,nextFlash=4; private final BehindRainLightning lightning=new BehindRainLightning(getContext());
 public BehindRainLightning lightningView(){return lightning;}
 private void publishLightning(){lightning.update(bolt,active()&&Ui.dark&&intensity>80?flash:0);} private final Path bolt=new Path(); private final Path dropShape=new Path();
 public long ticks,collisions,splashes,slides,merges,drips;
 private static final String LENS="uniform shader ui; uniform float2 center; uniform float2 radii; uniform float uFlash; uniform float2 axis; uniform float2 screenAxis; "+GlassShader.RAIN_DISPERSION_AGSL+" half4 main(float2 p) { float2 v=p-center; float2 local=float2(dot(v,float2(axis.y,-axis.x)),dot(v,axis));float2 q=local/radii; if(dot(q,q)>1.0) return half4(0.0); float z=sqrt(max(0.0,1.0-dot(q,q))); float2 offset=-q*(2.0+z*5.0); float2 sample=v+offset;float2 at=center+float2(screenAxis.y*sample.x+screenAxis.x*sample.y,-screenAxis.x*sample.x+screenAxis.y*sample.y);float2 radial=float2(screenAxis.y*q.x+screenAxis.x*q.y,-screenAxis.x*q.x+screenAxis.y*q.y);half3 c=rainSample(at,radial/max(length(radial),0.001),1.0-z); float spec=pow(max(0.0,dot(normalize(float3(q/radii,z/max(radii.x,radii.y))),normalize(float3(-0.5,-0.7,1.0)))),18.0); return half4(clamp(c+half3(spec*.32+uFlash*.48)-half3((1.0-z)*.055),0.0,1.0),1.0); }";
 private final ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())){public void onChange(boolean self){sync();}};
 private final Choreographer choreographer=Choreographer.getInstance();
 private final ViewTreeObserver.OnGlobalLayoutListener layout=()->{dirty=true;};
 private final ViewTreeObserver.OnScrollChangedListener scroll=()->{surfaceMotionRemaining=.25f;};
 private final ViewTreeObserver.OnPreDrawListener preDraw=()->{if(active())snapshot();return true;};
 private final Profile updateProfile=new Profile(),geometryProfile=new Profile(),captureProfile=new Profile(),drawProfile=new Profile();
 private static final class Profile {
  final long[] samples=new long[512]; long total,max,n;
  void add(long ns){samples[(int)(n++%samples.length)]=ns;total+=ns;max=Math.max(max,ns);}
  String report(String name){long[] a=java.util.Arrays.copyOf(samples,(int)Math.min(n,samples.length));java.util.Arrays.sort(a);return " "+name+"Count="+n+" "+name+"MeanUs="+(n==0?0:total/n/1000)+" "+name+"P50Us="+(a.length==0?0:a[(a.length-1)/2]/1000)+" "+name+"P90Us="+(a.length==0?0:a[(int)Math.ceil(a.length*.9)-1]/1000)+" "+name+"MaxUs="+max/1000;}
 }
 public void onPageSettled(int page){dirty=true;captureDirty=true;snapshot();invalidate();}
 private RainGravitySensor gravitySensor;private boolean tiltEnabled=true;
 public void setTiltEnabled(boolean on){tiltEnabled=on;physics.edgeSlidingEnabled=on;if(!on)gravitySensor.reset();sync();}
 public RainOverlay(Context c){super(c);gravitySensor=new RainGravitySensor(c,(x,y)->{physics.setGravity(x,y);if(BackdropRain.source!=null)BackdropRain.source.setGravity(x,y);});setClickable(false);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);if(Build.VERSION.SDK_INT>=33)try{lens=new RuntimeShader(LENS);ribbonLens=new RuntimeShader(RIBBON_GLASS);ribbonBasic=new RuntimeShader(RIBBON_BASIC);}catch(RuntimeException ignored){lens=null;}}
 public void setRainEnabled(boolean on){enabled=on;if(!on)physics.clear();sync();}
 public void setRainIntensity(int value){intensity=Math.max(0,Math.min(100,value));physics.intensity=intensity;sync();}
 public void setHostResumed(boolean on){resumed=on;sync();}
 public boolean isRainEnabled(){return enabled;}
 public boolean isTicking(){return scheduled;}
 public void invalidateSurfaces(){dirty=true;}
 public RainPhysics simulationForProbe(){return physics;}
 /** Deterministic real simulation entry; caller must use the UI thread, no spawning unless requested. */
 public String simulateForProbe(float seconds,boolean spawn){physics.width=getWidth();physics.height=getHeight();snapshot();int n=Math.min(3600,Math.max(0,(int)Math.ceil(seconds/RainPhysics.STEP)));for(int i=0;i<n;i++)physics.advance(RainPhysics.STEP,spawn);return performanceStatistics();}
 private long p90Draw(){if(timeCount==0)return 0;long[] a=java.util.Arrays.copyOf(drawTimes,Math.min(timeCount,drawTimes.length));java.util.Arrays.sort(a);return a[(int)Math.ceil(a.length*.9)-1]/1000;}
 public String performanceStatistics(){return physics.statistics()+" structureScans="+structureScans+" draws="+draws+" meanDrawUs="+(draws==0?0:drawNanos/draws/1000)+" p90DrawUs="+p90Draw()+" maxDrawUs="+maxDrawNanos/1000+" captureUs="+captureNanos/1000+" agsl="+(lens!=null)+updateProfile.report("update")+geometryProfile.report("geometry")+captureProfile.report("capture")+drawProfile.report("draw");}
 final BackdropRain backdrop=new BackdropRain(getContext());
 private boolean active(){return Ui.dark&&enabled&&resumed&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&isShown()&&Ui.animationsAllowed();}
 private void sync(){backdrop.update(0,enabled?intensity:0);if(gravitySensor!=null)gravitySensor.active(tiltEnabled&&active());choreographer.removeFrameCallback(frame);scheduled=false;previous=0;flash=flashRemaining=0;if(active()){scheduled=true;choreographer.postFrameCallback(frame);}publishLightning();invalidate();}
 private final Choreographer.FrameCallback frame=new Choreographer.FrameCallback(){public void doFrame(long now){scheduled=false;if(!active()){previous=0;flash=flashRemaining=0;publishLightning();invalidate();return;}long start=System.nanoTime();float dt=previous==0?0:(now-previous)*1e-9f;previous=now;surfaceMotionRemaining=Math.max(0,surfaceMotionRemaining-dt);physics.width=getWidth();physics.height=getHeight();backdrop.update(dt,intensity);if(dt>0)physics.advance(dt,true);updateFlash(dt);ticks++;collisions=physics.collisions;splashes=collisions;merges=physics.merges;drips=physics.drips;slides=0;for(RainPhysics.Drop d:physics.drops)if(d.state==RainPhysics.SLIDING)slides++;updateProfile.add(System.nanoTime()-start);invalidate();scheduled=true;choreographer.postFrameCallback(this);}};
 public void refreshGeometryForProbe(){snapshot();}
 @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);if(changed){dirty=true;captureDirty=true;}}
 private int pageOwner(View page){Integer id=pageOwners.get(page);if(id==null){id=nextPageOwner++;pageOwners.put(page,id);}return id;}
 private int ownerPage(View v){View child=v;for(ViewParent parent=v.getParent();parent instanceof View;parent=child.getParent()){if(parent instanceof SwipePager){pager=(SwipePager)parent;return pageOwner(child);}child=(View)parent;}return -1;}
 private void cleanSettledPage(){if(pager==null)return;int index=pager.currentIndex();if(index<0||index>=pager.getChildCount())return;View current=pager.getChildAt(index);for(int i=0;i<pager.getChildCount();i++){View child=pager.getChildAt(i);if(child.getVisibility()==VISIBLE&&(i!=index||Math.abs(child.getTranslationX())>.5f))return;}int owner=pageOwner(current);if(owner!=settledOwner){physics.clearOtherPages(owner);settledOwner=owner;captureDirty=true;}}
 private boolean clipped(View v,RainPhysics.Surface s){
  if(!v.isShown()||!v.isAttachedToWindow()||v.getAlpha()<=0||v.getWidth()<=0||v.getHeight()<=0)return false;
  float l=Math.max(0,s.left),t=Math.max(0,s.top),r=Math.min(getWidth(),s.right),b=Math.min(getHeight(),s.bottom);
  View node=v;
  while(node!=null){if(node.getVisibility()!=VISIBLE||node.getAlpha()<=0)return false;node.getLocationInWindow(location);float x=location[0]-origin[0],y=location[1]-origin[1];Rect cb=ancestorClip;if(node.getClipBounds(cb)){l=Math.max(l,x+cb.left);t=Math.max(t,y+cb.top);r=Math.min(r,x+cb.right);b=Math.min(b,y+cb.bottom);}if(node!=v&&node instanceof ViewGroup){ViewGroup g=(ViewGroup)node;if(g.getClipChildren()){l=Math.max(l,x);t=Math.max(t,y);r=Math.min(r,x+g.getWidth());b=Math.min(b,y+g.getHeight());}if(g.getClipToPadding()){l=Math.max(l,x+g.getPaddingLeft());t=Math.max(t,y+g.getPaddingTop());r=Math.min(r,x+g.getWidth()-g.getPaddingRight());b=Math.min(b,y+g.getHeight()-g.getPaddingBottom());}}ViewParent parent=node.getParent();node=parent instanceof View?(View)parent:null;}
  s.clipLeft=l;s.clipTop=t;s.clipRight=r;s.clipBottom=b;return r>l&&b>t;
 }
 private void snapshot(){long start=System.nanoTime(),now=System.nanoTime();float dt=lastSnapshot==0?RainPhysics.STEP:Math.max(1e-6f,(now-lastSnapshot)*1e-9f);lastSnapshot=now;getLocationInWindow(origin);if(dirty){dirty=false;structureScans++;for(int i=0;i<count;i++)physics.surfaces[i].live=false;scan(getRootView());}for(int i=0;i<count;i++){View v=views[i];RainPhysics.Surface s=physics.surfaces[i];if(v==null||!v.isAttachedToWindow()){s.live=false;continue;}v.getLocationInWindow(location);float l=location[0]-origin[0],t=location[1]-origin[1];if(s.left!=l||s.top!=t||s.right!=l+v.getWidth()||s.bottom!=t+v.getHeight()){surfaceMotionRemaining=.25f;physics.moveSurface(i,l,t,l+v.getWidth(),t+v.getHeight(),dt);}else if(s.vx!=0||s.vy!=0)physics.moveSurface(i,l,t,l+v.getWidth(),t+v.getHeight(),dt);s.ownerPage=ownerPage(v);s.live=clipped(v,s);java.util.Arrays.fill(s.corners,0);Drawable bg=v.getBackground();if(v instanceof GlassNav){java.util.Arrays.fill(s.corners,((GlassNav)v).collisionRadius());}else if(bg instanceof GradientDrawable){GradientDrawable g=(GradientDrawable)bg;float[] radii=g.getCornerRadii();if(radii!=null)System.arraycopy(radii,0,s.corners,0,8);else java.util.Arrays.fill(s.corners,g.getCornerRadius());}else if(v.getOutlineProvider()!=null){outline.setEmpty();v.getOutlineProvider().getOutline(v,outline);if(outline.getRect(visible))java.util.Arrays.fill(s.corners,Math.max(0,outline.getRadius()));}}cleanSettledPage();geometryProfile.add(System.nanoTime()-start);}
 private void scan(View v){if(v==this||v==lightning)return;boolean surface=v instanceof GlassNav||v instanceof Button||v instanceof Switch||v instanceof EditText||(v instanceof LinearLayout&&v.getBackground()!=null&&!(v.getBackground() instanceof ColorDrawable));if(surface){int k=-1;for(int i=0;i<count;i++)if(views[i]==v){k=i;break;}if(k<0){for(int i=0;i<count;i++)if(views[i]==null||!views[i].isAttachedToWindow()){k=i;break;}if(k<0&&count<RainPhysics.SURFACES)k=count++;if(k>=0){physics.retireSurface(k);views[k]=v;physics.surfaces[k].identity=System.identityHashCode(v);}}}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)scan(g.getChildAt(i));}}
 // Record visible content siblings only; never re-enter decor/root or the rain/lightning layers.
 // Downsampled cached real pixels still feed the original AGSL lens.
 private final Rect captureClip=new Rect();
 private void capture(){if(!GlassShader.isEnabled()||lens==null||getWidth()<1||getHeight()<1)return;long now=SystemClock.uptimeMillis();long interval=surfaceMotionRemaining>0?33:100;if(background!=null&&!captureDirty&&now-lastCapture<interval)return;long start=System.nanoTime();int w=Math.max(1,getWidth()/3),h=Math.max(1,getHeight()/3);if(background==null||background.getWidth()!=w||background.getHeight()!=h){if(background!=null)background.recycle();background=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);backgroundCanvas=new Canvas(background);cachedInput=new BitmapShader(background,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);}captureMatrix.setScale((float)getWidth()/w,(float)getHeight()/h);cachedInput.setLocalMatrix(captureMatrix);background.eraseColor(Ui.bg);capturing=true;int save=backgroundCanvas.save();try{getLocationInWindow(origin);backgroundCanvas.scale((float)w/getWidth(),(float)h/getHeight());ViewParent parent=getParent();if(parent instanceof ViewGroup){ViewGroup group=(ViewGroup)parent;group.getLocationInWindow(location);int groupSave=backgroundCanvas.save();backgroundCanvas.translate(location[0]-origin[0],location[1]-origin[1]);Drawable bg=group.getBackground();if(bg!=null)bg.draw(backgroundCanvas);backgroundCanvas.restoreToCount(groupSave);for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);if(child==this)break;if(child==lightning||child.getVisibility()!=VISIBLE||child.getAlpha()<=0||!child.getLocalVisibleRect(captureClip))continue;child.getLocationInWindow(location);int layer=backgroundCanvas.save();backgroundCanvas.translate(location[0]-origin[0],location[1]-origin[1]);backgroundCanvas.clipRect(captureClip);if(child.getAlpha()<1)backgroundCanvas.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),(int)(255*child.getAlpha()));child.draw(backgroundCanvas);backgroundCanvas.restoreToCount(layer);}}}finally{backgroundCanvas.restoreToCount(save);capturing=false;}lens.setInputShader("ui",cachedInput);if(ribbonLens!=null)ribbonLens.setInputShader("ui",cachedInput);captureDirty=false;lastCapture=now;captureNanos=System.nanoTime()-start;captureProfile.add(captureNanos);}
 private void updateFlash(float dt){if(!Ui.dark||intensity<=80){flash=flashRemaining=0;publishLightning();return;}nextFlash-=dt;if(nextFlash<=0){flashDuration=.1f+random.nextFloat()*.2f;flashRemaining=flashDuration;nextFlash=3+random.nextFloat()*9;makeBolt();}if(flashRemaining>0){flashRemaining=Math.max(0,flashRemaining-dt);float t=flashDuration-flashRemaining;flash=(flashRemaining/flashDuration)*(.5f+.5f*Math.abs((float)Math.sin(t*97)));}else flash=0;publishLightning();}
 // Triangular distribution over angle degrees: 0 up, 180 down. No Cartesian jitter.
 public static int sampleLightningAngle(int r){if(r<0||r>=32400)throw new IllegalArgumentException();if(r<16290)return (int)((Math.sqrt(8.0*r+1)+1+1e-12)/2);int rr=32399-r;int k=(int)((Math.sqrt(8.0*rr+1)+1+1e-12)/2);return 360-k;}
 public static int lightningSide(int degrees){return degrees==180||degrees==0?0:degrees<180?1:-1;}
 private final float[] tipX=new float[16],tipY=new float[16];
 private void makeBolt(){bolt.reset();float gx=physics.gravityX,gy=physics.gravityY;float min=RainDirection.min(gx,gy,getWidth(),getHeight()),span=RainDirection.span(gx,gy,getWidth(),getHeight());tipX[0]=gx<0?getWidth():0;tipY[0]=gy<0?getHeight():0;for(int attempt=0;attempt<128;attempt++){float sx=random.nextFloat()*getWidth(),sy=random.nextFloat()*getHeight();if(sx*gx+sy*gy<=min+span*.15f){tipX[0]=sx;tipY[0]=sy;break;}}int tips=1,segments=0;for(int tip=0;tip<tips&&segments<40;tip++){float x=tipX[tip],y=tipY[tip];bolt.moveTo(x,y);int previousSide=0;for(int i=0;i<(tip==0?22:6)&&segments<40&&x*gx+y*gy<min+span*.85f;i++){int degrees=sampleLightningAngle(random.nextInt(32400));for(int retry=0;retry<2&&previousSide!=0&&lightningSide(degrees)==-previousSide;retry++)degrees=sampleLightningAngle(random.nextInt(32400));int side=lightningSide(degrees);if(side!=0)previousSide=side;float angle=(float)Math.toRadians(degrees),len=18+random.nextFloat()*40;float lateral=(float)Math.sin(angle)*len,down=-(float)Math.cos(angle)*len;x+=gy*lateral+gx*down;y+=-gx*lateral+gy*down;bolt.lineTo(x,y);segments++;if(tips<4&&random.nextFloat()<.1f){tipX[tips]=x;tipY[tips++]=y;}}}lightning.setBolt(bolt);}
 // Fixed sampled cubic ribbon: both sides share analytic tangents at every join.
 private static final int RIBBON=StreamGeometry.N;
 private final float[] ribbonX=new float[RIBBON],ribbonY=new float[RIBBON],ribbonW=new float[RIBBON],ribbonA=new float[RIBBON],ribbonNX=new float[RIBBON],ribbonNY=new float[RIBBON];
 private RuntimeShader ribbonLens,ribbonBasic;
 private static final String RIBBON_COMMON="uniform float2 points[49]; uniform float widths[49]; uniform float opacity[49]; uniform float relief[49]; uniform float uFlash; float4 field(float2 p,out float depth){depth=1.0;float best=1e20;float side=0.0;float alpha=1.0;float2 normal=float2(1.0,0.0);for(int i=0;i<48;i++){float2 v=points[i+1]-points[i];float t=clamp(dot(p-points[i],v)/max(dot(v,v),0.0001),0.0,1.0);float2 q=p-mix(points[i],points[i+1],t);float w=max(0.1,mix(widths[i],widths[i+1],t));float dist=length(q)/w;if(dist<best){best=dist;normal=float2(v.y,-v.x)/max(length(v),0.001);side=length(q)/w;normal=q/max(length(q),0.001);alpha=mix(opacity[i],opacity[i+1],t);depth=mix(relief[i],relief[i+1],t);}}float z=sqrt(max(0.0,1.0-min(1.0,side*side)));float light=pow(max(0.0,dot(normalize(float3(normal*side,z)),normalize(float3(-0.5,-0.7,1.0)))),18.0);return float4(normal*side,light,alpha);}";
 private static final String RIBBON_GLASS=RIBBON_COMMON+"uniform shader ui;"+GlassShader.RAIN_DISPERSION_AGSL+"half4 main(float2 p){float depth;float4 f=field(p,depth);float edge=clamp(length(f.xy),0.0,1.0);half3 c=rainSample(p-f.xy*(4.0*depth),f.xy/max(edge,0.001)*depth,1.0-sqrt(max(0.0,1.0-edge*edge)));float shade=length(f.xy)*0.065*depth;return half4(clamp(c+f.z*.34*depth+uFlash*.48*depth-shade,0.0,1.0)*f.w,f.w);}";
 private static final String RIBBON_BASIC=RIBBON_COMMON+"half4 main(float2 p){float depth;float4 f=field(p,depth);float a=(0.24+f.z*.46*depth)*f.w;return half4(half3(0.67,0.79,0.87)*a,a);}";
 // Optical depth only: full rounded head, flat middle and tail.
 private final float[] ribbonRelief=new float[RIBBON];
 private final float[] ribbonPoints=new float[RIBBON*2];
 private void ribbonCap(int i,int end){
  float x=ribbonX[i],y=ribbonY[i],w=ribbonW[i],nx=ribbonNX[i],ny=ribbonNY[i];
  float sx=nx*end,sy=ny*end,tx=-ny*end,ty=nx*end,c=.55228475f;
  dropShape.cubicTo(x+sx*w+tx*w*c,y+sy*w+ty*w*c,x+tx*w+sx*w*c,y+ty*w+sy*w*c,x+tx*w,y+ty*w);
  dropShape.cubicTo(x+tx*w-sx*w*c,y+ty*w-sy*w*c,x-sx*w+tx*w*c,y-sy*w+ty*w*c,x-sx*w,y-sy*w);
 }
 private void drawLockedDrop(Canvas c,RainPhysics.Drop d,float r){
  StreamGeometry g=d.geometry;for(int i=0;i<RIBBON;i++){ribbonX[i]=g.x[i];ribbonY[i]=g.y[i];ribbonW[i]=g.width[i];ribbonA[i]=g.alpha[i];ribbonNX[i]=g.nx[i];ribbonNY[i]=g.ny[i];float t=Math.max(0,(i-16)/16f);float q=Math.min(1,t);q=q*q*(3-2*q);float tail=Math.max(0,t-1);tail=tail*tail*(3-2*tail);ribbonRelief[i]=t<=1?1-.76f*q:.24f-.20f*tail;}
  dropShape.reset();for(int i=0;i<RIBBON;i++){float x=ribbonX[i]+ribbonNX[i]*ribbonW[i],y=ribbonY[i]+ribbonNY[i]*ribbonW[i];if(i==0)dropShape.moveTo(x,y);else dropShape.lineTo(x,y);}
  ribbonCap(RIBBON-1,1);
  for(int i=RIBBON-1;i>=0;i--)dropShape.lineTo(ribbonX[i]-ribbonNX[i]*ribbonW[i],ribbonY[i]-ribbonNY[i]*ribbonW[i]);ribbonCap(0,-1);dropShape.close();
  RuntimeShader shader=c.isHardwareAccelerated()?(GlassShader.isEnabled()&&background!=null?ribbonLens:ribbonBasic):null;
  paint.setStyle(Paint.Style.FILL);if(shader!=null){for(int i=0;i<RIBBON;i++){ribbonPoints[i*2]=ribbonX[i];ribbonPoints[i*2+1]=ribbonY[i];}shader.setFloatUniform("points",ribbonPoints);shader.setFloatUniform("widths",ribbonW);shader.setFloatUniform("opacity",ribbonA);shader.setFloatUniform("relief",ribbonRelief);shader.setFloatUniform("uFlash",flash);paint.setShader(shader);}else paint.setColor(0x55d8e6f0);
  c.drawPath(dropShape,paint);paint.setShader(null);
 }
 @Override protected void onDraw(Canvas c){super.onDraw(c);if(capturing||!enabled||!Ui.dark)return;long start=System.nanoTime();if(c.isHardwareAccelerated())capture();paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.7f);for(RainPhysics.Drop d:physics.drops){if(d.state==0)continue;if(d.surface>=0&&!physics.surfaces[d.surface].live)continue;int clipSave=c.save();if(d.surface>=0){RainPhysics.Surface s=physics.surfaces[d.surface];c.clipRect(s.clipLeft,s.clipTop,s.clipRight,Math.min(getHeight(),s.clipBottom));}float r=d.radius(),rx=r,ry=r;if(d.state==RainPhysics.FREE){
 // Thin streak aligned with the physical velocity.
 paint.setShader(null);paint.setStrokeCap(Paint.Cap.ROUND);
 float speed=(float)Math.hypot(d.vx,d.vy);

 paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.15f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Color.argb((int)(180+75*flash),255,255,255));
 float lineLength=Math.min(24f,Math.max(7f,speed*.018f));
 if(speed>.001f)c.drawLine(d.x,d.y,d.x-d.vx/speed*lineLength,d.y-d.vy/speed*lineLength,paint);
 paint.setStrokeWidth(.8f);paint.setStyle(Paint.Style.FILL);c.restoreToCount(clipSave);
 continue;}if(d.state==RainPhysics.SLIDING){drawLockedDrop(c,d,r);c.restoreToCount(clipSave);continue;}if(d.state==RainPhysics.SPLASH){rx=r*(1.6f-d.age*5);ry=r*r/rx;}

 float axisX=physics.gravityX,axisY=physics.gravityY;float renderAngle=(float)Math.toDegrees(Math.atan2(axisY,axisX))-90; c.rotate(renderAngle,d.x,d.y);
 paint.setStyle(Paint.Style.FILL);if(c.isHardwareAccelerated()&&GlassShader.isEnabled()&&lens!=null&&background!=null){uniformPair[0]=0;uniformPair[1]=1;lens.setFloatUniform("axis",uniformPair);uniformPair[0]=axisX;uniformPair[1]=axisY;lens.setFloatUniform("screenAxis",uniformPair);uniformPair[0]=d.x;uniformPair[1]=d.y;lens.setFloatUniform("center",uniformPair);uniformPair[0]=rx;uniformPair[1]=ry;lens.setFloatUniform("radii",uniformPair);uniformFlash[0]=flash;lens.setFloatUniform("uFlash",uniformFlash);paint.setShader(lens);}else paint.setColor(Ui.dark?0x45D8E0E5:0x35AAB8C0);c.drawOval(d.x-rx,d.y-ry,d.x+rx,d.y+ry,paint);paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.8f);paint.setColor(0x99e1f5ff);c.drawArc(d.x-rx*.8f,d.y-ry*.8f,d.x+rx*.8f,d.y+ry*.8f,205,65,false,paint);c.restoreToCount(clipSave);}
 paint.setStyle(Paint.Style.FILL);long duration=System.nanoTime()-start;drawProfile.add(duration);drawTimes[timeCount++%drawTimes.length]=duration;draws++;drawNanos+=duration;maxDrawNanos=Math.max(maxDrawNanos,duration);}
 @Override public boolean onTouchEvent(MotionEvent e){return false;}
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();ViewTreeObserver t=getRootView().getViewTreeObserver();t.addOnGlobalLayoutListener(layout);t.addOnScrollChangedListener(scroll);t.addOnPreDrawListener(preDraw);getContext().getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer);observing=true;dirty=true;sync();}
 @Override protected void onDetachedFromWindow(){gravitySensor.active(false);choreographer.removeFrameCallback(frame);scheduled=false;flash=flashRemaining=0;lightning.update(bolt,0);if(observing){getContext().getContentResolver().unregisterContentObserver(observer);observing=false;}ViewTreeObserver t=getRootView().getViewTreeObserver();if(t.isAlive()){t.removeOnGlobalLayoutListener(layout);t.removeOnScrollChangedListener(scroll);t.removeOnPreDrawListener(preDraw);}physics.clear();count=0;java.util.Arrays.fill(views,null);if(background!=null){background.recycle();background=null;backgroundCanvas=null;}super.onDetachedFromWindow();}
 @Override protected void onWindowVisibilityChanged(int v){super.onWindowVisibilityChanged(v);if(frame!=null)sync();}
 @Override protected void onVisibilityChanged(View v,int visibility){super.onVisibilityChanged(v,visibility);if(frame!=null)sync();}
 @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);sync();}
}
