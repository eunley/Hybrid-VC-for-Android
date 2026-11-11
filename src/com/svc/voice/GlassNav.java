/* Copyright 2025 Kyant. Licensed under Apache-2.0.
 * Modified 2026: native Java View adaptation of LiquidBottomTabs,
 * Shaders.kt and DampedDragAnimation. See assets/licenses/AndroidLiquidGlass-SOURCE.txt. */
package com.svc.voice;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.*;
import android.widget.FrameLayout;

public class GlassNav extends FrameLayout {
    public interface Listener { void onPick(int idx); void onHover(int idx); }
    private View source, background;
    private SwipePager pager;
    private long captureCount, captureNanos, renderCount, renderNanos;
    private final long[] captureTimes=new long[240], renderTimes=new long[240];
    private final Matrix sampleMatrix=new Matrix();
    private final SwipePager.OnProgress pagerProgress=this::onPagerProgress;
    private void onPagerProgress(float page,boolean moving){
        dirty=true;
        if(!dragging && pager!=null){
            if(moving){removeCallbacks(frame);scheduled=false;lastFrame=0;velocity=0;position=target=center(0)+itemW*page;int idx=Math.max(0,Math.min(tabCount-1,Math.round(page)));if(hover!=idx){hover=idx;if(listener!=null)listener.onHover(idx);}}
            else {selected=hover=pager.currentIndex();target=center(selected);position=target;velocity=0;if(listener!=null)listener.onHover(selected);}
        }
        postInvalidateOnAnimation();
    }
    private final ViewTreeObserver.OnPreDrawListener backdropPreDraw=()->{
        // Observe arbitrary child invalidations (icons, animated backgrounds, pager and scroll).
        // No self-invalidating idle loop: runs only when the window already has a frame.
        if(GlassShader.isEnabled()&&((source!=null&&source.isDirty())||(background!=null&&background.isDirty())||(pager!=null&&pager.isPageMoving()))){dirty=true;invalidate();}return true;
    };
    public long[] probeStatistics(){return new long[]{captureCount,captureNanos,renderCount,renderNanos,percentile(captureTimes,captureCount),percentile(renderTimes,renderCount)};}
    private long percentile(long[] values,long count){int n=(int)Math.min(count,values.length);if(n==0)return 0;long[] sorted=java.util.Arrays.copyOf(values,n);java.util.Arrays.sort(sorted);return sorted[(int)Math.ceil(n*.9)-1];}
    private Listener listener;
    private int tabCount=5, selected, hover;
    private float radiusPx, pad,itemW,pillW,pillH;
    private float position,target,velocity,press,pressVelocity,pressTarget;
    private float downX,downY,lastX,dragOffset;
    private long lastMove,lastFrame,lastCapture;
    private boolean dragging,enabled=true,dirty=true,scheduled,capturing,shaderFailed;
    private final int slop;
    private Bitmap backdrop,rowBitmap;
    private Canvas backdropCanvas,rowCanvas;
    private BitmapShader backdropShader,rowShader;
    private Object barShader,lensShader;
    private final Paint paint=new Paint(3),rim=new Paint(3);
    private final RectF bounds=new RectF(),pill=new RectF();
    private final Path outer=new Path(),capsule=new Path();
    private final int[] navLocation=new int[2],viewLocation=new int[2];
    public GlassNav(Context c){super(c);setWillNotDraw(false);radiusPx=dp(32);slop=ViewConfiguration.get(c).getScaledTouchSlop();rim.setStyle(Paint.Style.STROKE);}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    public void setListener(Listener l){listener=l;}
    public void setSource(View pages,View bg){if(pager!=null)pager.removeProgressListener(pagerProgress);source=pages;background=bg;pager=pages instanceof SwipePager?(SwipePager)pages:null;if(pager!=null)pager.addProgressListener(pagerProgress);refresh();}
    public void setRadiusDp(float r){radiusPx=dp(r);invalidate();}
    public void setTabCount(int n){tabCount=Math.max(1,n);selected=Math.min(selected,tabCount-1);geometry();requestLayout();}
    public float collisionRadius(){return Math.min(radiusPx,Math.min(getWidth(),getHeight())*.5f);}
    public int selectedIndex(){return selected;}
    public void refresh(){dirty=true;invalidate();}
    private boolean motion(){return enabled&&Ui.animationsAllowed();}
    public void setSelected(int idx,boolean animate){
        if(idx<0||idx>=tabCount)return;
        selected=hover=idx;target=center(idx);pressTarget=0;
        if(listener!=null)listener.onHover(idx);
        if(!animate||!motion()){settle();}else{wake();}
    }
    private float center(int i){return pad+itemW*(i+0.5f);}
    private void geometry(){pad=dp(8);itemW=Math.max(1,(getWidth()-pad*2)/tabCount);pillW=Math.max(1,itemW-dp(6));pillH=Math.max(1,getHeight()-dp(14));target=center(selected);if(!dragging){position=target;velocity=0;}invalidate();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);geometry();dirty=true;backdrop=null;backdropShader=null;rowBitmap=null;barShader=lensShader=null;shaderFailed=false;}
    private void settle(){position=target;velocity=0;press=pressTarget=pressVelocity=0;removeCallbacks(frame);scheduled=false;lastFrame=0;invalidate();}
    private void wake(){if(!motion()){if(dragging){press=pressTarget=pressVelocity=velocity=0;invalidate();}else settle();return;}if(!scheduled){scheduled=true;postOnAnimation(frame);}invalidate();}
    // Semi-implicit spring integration, retaining velocity on target changes.
    private final Runnable frame=new Runnable(){public void run(){
        scheduled=false;if(!motion()){if(dragging){press=pressTarget=pressVelocity=velocity=0;lastFrame=0;invalidate();}else settle();return;}
        long now=SystemClock.uptimeMillis();float dt=lastFrame==0?0.016f:Math.min(0.032f,(now-lastFrame)/1000f);lastFrame=now;
        int steps=4;float t=dt/steps;
        for(int i=0;i<steps;i++){
            if(!dragging){velocity+=(500f*(target-position)-2f*0.78f*(float)Math.sqrt(500)*velocity)*t;position+=velocity*t;}
            pressVelocity+=(400f*(pressTarget-press)-2f*0.72f*20f*pressVelocity)*t;press+=pressVelocity*t;
        }
        if(dragging&&now-lastMove>40)velocity*=0.82f;
        invalidate();
        if(dragging||Math.abs(target-position)>0.1f||Math.abs(velocity)>0.5f||Math.abs(pressTarget-press)>0.001f||Math.abs(pressVelocity)>0.005f)wake();
        else {position=target;velocity=0;press=pressTarget;pressVelocity=0;lastFrame=0;}
    }};
    public void setAnimationsEnabled(boolean value){enabled=value;if(!value){dragging=false;releaseParent();target=center(selected);settle();}else refresh();}
    private final ViewTreeObserver.OnScrollChangedListener backdropScroll=()->refresh();
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getRootView().getViewTreeObserver().addOnScrollChangedListener(backdropScroll);getRootView().getViewTreeObserver().addOnPreDrawListener(backdropPreDraw);if(pager!=null)pager.addProgressListener(pagerProgress);}
    @Override protected void onDetachedFromWindow(){if(getRootView().getViewTreeObserver().isAlive()){getRootView().getViewTreeObserver().removeOnScrollChangedListener(backdropScroll);getRootView().getViewTreeObserver().removeOnPreDrawListener(backdropPreDraw);}if(pager!=null)pager.removeProgressListener(pagerProgress);removeCallbacks(frame);scheduled=false;dragging=false;releaseParent();target=center(selected);settle();super.onDetachedFromWindow();}
    private boolean safeSource(View v){if(v==null)return false;for(ViewParent p=this;p!=null;p=p.getParent())if(p==v)return false;return true;}
    private void drawSource(Canvas c,View v){if(!safeSource(v))return;v.getLocationInWindow(viewLocation);int save=c.save();c.translate(viewLocation[0]-navLocation[0],viewLocation[1]-navLocation[1]);c.clipRect(navLocation[0]-viewLocation[0],navLocation[1]-viewLocation[1],navLocation[0]-viewLocation[0]+getWidth(),navLocation[1]-viewLocation[1]+getHeight());v.draw(c);c.restoreToCount(save);}
    private void captureBackground(){
        long now=SystemClock.uptimeMillis();if(!dirty&&backdrop!=null)return;
        long captureStart=System.nanoTime();
        int w=Math.max(1,(getWidth()+1)/2),h=Math.max(1,(getHeight()+1)/2);
        if(backdrop==null||backdrop.getWidth()!=w||backdrop.getHeight()!=h){backdrop=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);backdropCanvas=new Canvas(backdrop);backdropShader=null;barShader=lensShader=null;}
        Canvas c=backdropCanvas;c.setMatrix(null);c.drawColor(Ui.dark?Color.BLACK:Color.WHITE);c.scale(w/(float)getWidth(),h/(float)getHeight());
        getLocationInWindow(navLocation);capturing=true;
        try{drawSource(c,background);drawSource(c,source);}finally{capturing=false;}
        // Preserve captured RGB; no grayscale pass or additional blur.
        if(backdropShader==null){backdropShader=new BitmapShader(backdrop,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);sampleMatrix.setScale(getWidth()/(float)w,getHeight()/(float)h);backdropShader.setLocalMatrix(sampleMatrix);barShader=lensShader=null;}lastCapture=now;dirty=false;long elapsed=System.nanoTime()-captureStart;captureTimes[(int)(captureCount%captureTimes.length)]=elapsed;captureCount++;captureNanos+=elapsed;
    }
    // Draw only navigation children, never this.draw()/dispatchDraw(): no recursive capture.
    private void captureRow(){
        int w=getWidth(),h=getHeight();if(rowBitmap==null||rowBitmap.getWidth()!=w||rowBitmap.getHeight()!=h){rowBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);rowCanvas=new Canvas(rowBitmap);rowShader=new BitmapShader(rowBitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);barShader=lensShader=null;}
        rowBitmap.eraseColor(Color.TRANSPARENT);Canvas c=rowCanvas;
        for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child.getVisibility()!=VISIBLE)continue;int s=c.save();c.translate(child.getLeft()-getScrollX(),child.getTop()-getScrollY());c.concat(child.getMatrix());if(child.getAlpha()<1)c.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),Math.round(child.getAlpha()*255));child.draw(c);c.restoreToCount(s);}
    }
    private void shape(){
        float normalized=velocity/Math.max(1,itemW*10f);float deform=Math.max(-0.2f,Math.min(0.2f,normalized*0.75f));
        float p=Math.max(0,Math.min(1.15f,press));float sx=(1f+0.18f*p)/(1f-deform),sy=(1f+0.12f*p)*(1f-Math.max(-0.2f,Math.min(0.2f,normalized*0.25f)));
        float inset=dp(2);float h=Math.max(1,Math.min(getHeight()-inset*2,pillH*sy));
        float r=Math.min(radiusPx,Math.min(getWidth(),getHeight())/2f);
        float y=(getHeight()-h)/2;
        // Capsule containment: keep its semicircle centers inside the outer inset.
        float edge=inset+Math.max(0,r-h/2-y);
        float w=Math.max(1,Math.min(getWidth()-edge*2,pillW*sx));
        float cx=Math.max(edge+w/2,Math.min(getWidth()-edge-w/2,position));
        pill.set(cx-w/2,y,cx+w/2,y+h);capsule.reset();capsule.addRoundRect(pill,h/2,h/2,Path.Direction.CW);
    }
    private boolean shaders(){if(Build.VERSION.SDK_INT<33||shaderFailed)return false;try{if(barShader==null)barShader=GlassShader.build(backdropShader,rowShader);if(lensShader==null)lensShader=GlassShader.build(backdropShader,rowShader);return true;}catch(Throwable e){shaderFailed=true;android.util.Log.w("GlassNav","AGSL unavailable; bitmap fallback",e);return false;}}
    @Override protected void onDraw(Canvas c){if(capturing)return;super.onDraw(c);}
    @Override protected void dispatchDraw(Canvas c){
        if(capturing||getWidth()<=0||getHeight()<=0)return;
        if(!motion()&&!dragging&&(scheduled||press!=0||position!=target)){settle();}
        if (!GlassShader.isEnabled()) {
            // Ordinary navigation: no background sampling, bitmap capture or refraction.
            shape();
            paint.setShader(null);
            paint.setColor(Ui.dark?0xA0242529:Ui.bg);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), radiusPx, radiusPx, paint);
            paint.setColor(Ui.dark ? 0x24ffffff : 0x16000000);
            c.drawRoundRect(pill, pill.height()/2, pill.height()/2, paint);
            paint.setColor(Color.WHITE);
            super.dispatchDraw(c);
            return;
        }
        long renderStart=System.nanoTime();
        captureBackground();captureRow();shape();bounds.set(0,0,getWidth(),getHeight());outer.reset();outer.addRoundRect(bounds,radiusPx,radiusPx,Path.Direction.CW);
        int save=c.save();c.clipPath(outer);
        boolean runtime=c.isHardwareAccelerated()&&shaders();
        if(runtime){try{GlassShader.configure(barShader,bounds,radiusPx,dp(9),dp(7),0);paint.setShader((Shader)barShader);c.drawRect(bounds,paint);}catch(Throwable e){android.util.Log.e("GlassNav","configure/draw failed",e);shaderFailed=true;runtime=false;}finally{paint.setShader(null);}}
        if(!runtime){paint.setShader(backdropShader);c.drawRect(bounds,paint);paint.setShader(null);}
        // Suppress original row inside capsule, then replace it with the combined refracted sample.
        int rowSave=c.save();c.clipOutPath(capsule);c.drawBitmap(rowBitmap,0,0,null);c.restoreToCount(rowSave);
        int lensSave=c.save();c.clipPath(capsule);
        if(runtime){try{GlassShader.configure(lensShader,pill,pill.height()/2,dp(10),dp(10+4*Math.max(0,press)),1);paint.setShader((Shader)lensShader);c.drawRect(pill,paint);}catch(Throwable e){android.util.Log.e("GlassNav","configure/draw failed",e);shaderFailed=true;runtime=false;}finally{paint.setShader(null);}}
        if(!runtime){paint.setShader(backdropShader);c.drawRect(pill,paint);paint.setShader(null);c.drawBitmap(rowBitmap,0,0,null);}
        c.restoreToCount(lensSave);
        rim.setStrokeWidth(dp(0.8f));rim.setColor(Ui.dark?0x55ffffff:0x66ffffff);c.drawRoundRect(pill,pill.height()/2,pill.height()/2,rim);c.drawRoundRect(bounds,radiusPx,radiusPx,rim);c.restoreToCount(save);
        long elapsed=System.nanoTime()-renderStart;renderTimes[(int)(renderCount%renderTimes.length)]=elapsed;renderCount++;renderNanos+=elapsed;
    }
    private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=lastX=e.getX();downY=e.getY();lastMove=SystemClock.uptimeMillis();dragging=false;velocity=0;dragOffset=position-downX;pressTarget=1;wake();}
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-downX)>slop&&Math.abs(e.getX()-downX)>Math.abs(e.getY()-downY)*1.3f){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);updateDrag(e.getX());return true;}
        else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){pressTarget=0;wake();}
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&dragging){updateDrag(e.getX());return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP&&dragging){updateDrag(e.getX());dragging=false;releaseParent();int idx=nearest(position);pressTarget=0;setSelected(idx,true);if(listener!=null)listener.onPick(idx);return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;releaseParent();pressTarget=0;velocity=0;target=center(selected);hover=selected;if(listener!=null)listener.onHover(selected);wake();return true;}
        return true;
    }
    private void updateDrag(float x){long now=SystemClock.uptimeMillis();float dt=Math.max(1,now-lastMove)/1000f;float next=Math.max(center(0),Math.min(center(tabCount-1),x+dragOffset));float measured=(next-position)/dt;if(measured*velocity<0)velocity=0;velocity=motion()?Math.max(-itemW*10,Math.min(itemW*10,0.65f*velocity+0.35f*measured)):0;lastX=x;lastMove=now;position=Math.max(center(0),Math.min(center(tabCount-1),x+dragOffset));if(!motion())press=pressTarget=0;int idx=nearest(position);if(idx!=hover){hover=idx;if(listener!=null)listener.onHover(idx);}if(motion())wake();else invalidate();}
    private int nearest(float x){return Math.max(0,Math.min(tabCount-1,(int)((x-pad)/Math.max(1,itemW))));}
}
