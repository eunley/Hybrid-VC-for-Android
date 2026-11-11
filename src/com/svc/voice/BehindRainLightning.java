package com.svc.voice;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Background lightning. Scheduling and lifetime belong exclusively to RainOverlay. */
public final class BehindRainLightning extends View {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private final Path bolt=new Path();
 private final RectF bounds=new RectF(); private float flash; private Shader glow;
 public BehindRainLightning(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setClickable(false);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 // Geometry and gradient change only when a new bounded bolt is generated.
 void setBolt(Path path){bolt.set(path);bolt.computeBounds(bounds,true);float radius=Math.max(100,Math.max(bounds.width(),bounds.height())*.75f);glow=new RadialGradient(bounds.centerX(),bounds.centerY(),radius,0x38b8c9ed,0x00b8c9ed,Shader.TileMode.CLAMP);}
 void update(Path path,float value){float next=Math.max(0,Math.min(1,value));if(next==flash)return;flash=next;invalidate();}
 @Override protected void onDraw(Canvas c){super.onDraw(c);if(flash<=0)return;c.drawColor(Color.argb((int)(flash*65),230,238,255));paint.setStyle(Paint.Style.FILL);paint.setShader(glow);paint.setAlpha((int)(255*flash));c.drawRect(0,0,getWidth(),getHeight(),paint);paint.setShader(null);paint.setStyle(Paint.Style.STROKE);for(int i=0;i<3;i++){paint.setMaskFilter(new BlurMaskFilter(new float[]{32,14,5}[i],BlurMaskFilter.Blur.NORMAL));paint.setColor(Color.argb((int)(flash*new int[]{115,170,230}[i]),190,211,255));paint.setStrokeWidth(new float[]{12,7,4}[i]);c.drawPath(bolt,paint);}paint.setMaskFilter(null);paint.setColor(Color.argb((int)(flash*255),255,255,255));paint.setStrokeWidth(2.4f);c.drawPath(bolt,paint);}

 @Override public boolean onTouchEvent(MotionEvent e){return false;}
}
