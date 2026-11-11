package com.svc.voice;
import android.content.Context;import android.graphics.*;import android.view.*;import android.widget.SeekBar;
/** Native SeekBar input; all dots display output PCM frequency bands. */
public class DotSeekBar extends SeekBar {
 private final Paint dots=new Paint(3);private int mode=-1;private boolean host=true;private boolean readOnly;
 public void setReadOnly(boolean value){readOnly=value;setFocusable(!value);}
 @Override public boolean onKeyDown(int key,android.view.KeyEvent event){return readOnly||super.onKeyDown(key,event);}
 @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){if(readOnly&&(action==4096||action==8192||action==16908349))return false;return super.performAccessibilityAction(action,args);}
 public static volatile long barsDrawn,barsChanged;public static volatile float visibleHeight;private float lastLevel=-1;private final float[] spectrum=new float[OutputEnvelope.BANDS];
 private final Runnable tick=new Runnable(){public void run(){if(canAnimate()){invalidate();postDelayed(this,20);}}};
 public DotSeekBar(Context c){super(c);setMinimumHeight(Ui.dp(c,48));setPadding(Ui.dp(c,12),0,Ui.dp(c,12),0);setProgressDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));android.graphics.drawable.GradientDrawable t=Ui.round(Ui.accent,Ui.dp(c,8));t.setSize(Ui.dp(c,16),Ui.dp(c,24));setThumb(t);setSplitTrack(false);}
 public void setEnvelopeMode(int m){if(m < -1||m>2)throw new IllegalArgumentException("mode");mode=m;restart();}
 public void setHostResumed(boolean v){host=v;restart();}
 private boolean canAnimate(){return mode>=0&&host&&isAttachedToWindow()&&isShown()&&getWindowVisibility()==VISIBLE&&hasWindowFocus();}
 private void restart(){removeCallbacks(tick);invalidate();if(canAnimate())post(tick);}
 @Override protected synchronized void onDraw(Canvas c){
  float l=getPaddingLeft(),r=getWidth()-getPaddingRight(),y=getHeight()/2f;
  float fraction=Math.max(0,Math.min(1,(getProgress()-getMin())/(float)Math.max(1,getMax()-getMin())));
  boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;float x=rtl?r-(r-l)*fraction:l+(r-l)*fraction;
  if(canAnimate())OutputEnvelope.spectrum(mode,android.os.SystemClock.uptimeMillis(),spectrum);else java.util.Arrays.fill(spectrum,0);
  float level=0;
  float radius=Ui.dp(getContext(),2),step=Ui.dp(getContext(),8);visibleHeight=radius;
  for(float q=l+radius;q<=r-radius;q+=step){
   boolean filled=rtl?q>=x:q<=x;
   dots.setColor(filled?Ui.accent:Ui.textFaint);dots.setAlpha(isEnabled()?255:110);
   float position=(q-l)/Math.max(1,r-l);int band=Math.min(spectrum.length-1,(int)(position*spectrum.length));float amplitude=spectrum[band];level=Math.max(level,amplitude);float h=radius+amplitude*Ui.dp(getContext(),12);visibleHeight=Math.max(visibleHeight,h);
   c.drawRoundRect(q-radius,y-h,q+radius,y+h,radius,radius,dots);if(amplitude>0)barsDrawn++;
  }
  if(Math.abs(level-lastLevel)>.004f){barsChanged++;lastLevel=level;}super.onDraw(c);
 }
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();restart();}
 @Override protected void onDetachedFromWindow(){removeCallbacks(tick);super.onDetachedFromWindow();}
 @Override protected void onWindowVisibilityChanged(int v){super.onWindowVisibilityChanged(v);if(tick!=null)restart();}
 @Override protected void onVisibilityChanged(View v,int visibility){super.onVisibilityChanged(v,visibility);if(tick!=null)restart();}
 @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);restart();}
 @Override public boolean onTouchEvent(MotionEvent e){if(readOnly)return false;ViewParent p=getParent();if(isEnabled()&&e.getActionMasked()==MotionEvent.ACTION_DOWN&&p!=null)p.requestDisallowInterceptTouchEvent(true);boolean b=super.onTouchEvent(e);if((e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)&&p!=null)p.requestDisallowInterceptTouchEvent(false);return b;}
}
