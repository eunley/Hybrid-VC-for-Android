package com.svc.voice;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Bounded visual-only rain. Driven exclusively by RainOverlay lifecycle. */
final class BackdropRain extends View {
 static BackdropRain source;
 private static final int MAX_RAIN=720;
 private final float[] x=new float[MAX_RAIN],y=new float[MAX_RAIN],speed=new float[MAX_RAIN],lines=new float[MAX_RAIN*4];
 private final java.util.Random random=new java.util.Random();
 private final float[] length=new float[MAX_RAIN];
 private float gravityX=0,gravityY=1;
 void setGravity(float gx,float gy){float n=(float)Math.hypot(gx,gy);if(n>.001f){gravityX=gx/n;gravityY=gy/n;}}
 private void resetRain(int i,boolean initial){
  speed[i]=180+random.nextFloat()*340;length[i]=10+random.nextFloat()*20;
  if(initial){x[i]=random.nextFloat();y[i]=random.nextFloat();return;}
  float w=Math.max(1,getWidth()),h=Math.max(1,getHeight());
  float side=Math.abs(gravityX)*h,top=Math.abs(gravityY)*w;
  float gap=2+random.nextFloat()*20;
  if(random.nextFloat()*(side+top)<side){x[i]=gravityX>0?-gap/w:1+gap/w;y[i]=random.nextFloat();}
  else{x[i]=random.nextFloat();y[i]=gravityY>0?-gap/h:1+gap/h;}
 }

 private final Paint paint=new Paint(3); private int count; private Bitmap bitmap; private Canvas sample; private BitmapShader shader; private final Matrix matrix=new Matrix(); private RuntimeShader blur;
 private final java.util.ArrayList<java.lang.ref.WeakReference<View>> cards=new java.util.ArrayList<>();
 BackdropRain(Context c){super(c);source=this;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);for(int i=0;i<MAX_RAIN;i++){resetRain(i,true);}paint.setStrokeWidth(1.5f);paint.setColor(0x6695A6BA);}
 void register(View v){cards.add(new java.lang.ref.WeakReference<View>(v));}
 void update(float dt,int intensity){count=Ui.dark?MAX_RAIN*Math.max(0,Math.min(100,intensity))/100:0;for(int i=0;i<count;i++){float step=Math.min(.05f,dt)*speed[i];float w=Math.max(1,getWidth()),h=Math.max(1,getHeight());x[i]+=gravityX*step/w;y[i]+=gravityY*step/h;float margin=length[i]+speed[i]*.025f+24;if((gravityX>0&&x[i]>1+margin/w)||(gravityX<0&&x[i]<-margin/w)||(gravityY>0&&y[i]>1+margin/h)||(gravityY<0&&y[i]<-margin/h))resetRain(i,false);}refreshSample();invalidate();for(int i=cards.size()-1;i>=0;i--){View v=cards.get(i).get();if(v==null)cards.remove(i);else if(v.isShown())v.invalidate();}}
 private void drawRain(Canvas c){for(int i=0;i<count;i++){int k=i*4;lines[k]=x[i]*getWidth();lines[k+1]=y[i]*getHeight();float tail=length[i]+speed[i]*.025f;lines[k+2]=lines[k]-gravityX*tail;lines[k+3]=lines[k+1]-gravityY*tail;}c.drawLines(lines,0,count*4,paint);}
 private void refreshSample(){int w=Math.max(1,getWidth()/4),h=Math.max(1,getHeight()/4);if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h){if(bitmap!=null)bitmap.recycle();bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);sample=new Canvas(bitmap);shader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);}bitmap.eraseColor(Ui.bg);int save=sample.save();sample.scale(w/(float)Math.max(1,getWidth()),h/(float)Math.max(1,getHeight()));drawRain(sample);sample.restoreToCount(save);}
 void drawSample(Canvas c,View card,RectF bounds,Paint p,int[] loc){if(shader==null||!Ui.dark)return;card.getLocationInWindow(loc);float cx=loc[0],cy=loc[1];getLocationInWindow(loc);matrix.setScale(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());matrix.postTranslate(loc[0]-cx,loc[1]-cy);shader.setLocalMatrix(matrix);p.setShader(c.isHardwareAccelerated()?blurShader():shader);c.drawRoundRect(bounds,Ui.dp(getContext(),22),Ui.dp(getContext(),22),p);p.setShader(null);}
 static final String BLUR="uniform shader rain; half4 main(float2 p){half4 s=half4(0);for(int x=-2;x<=2;x++){for(int y=-2;y<=2;y++){s+=rain.eval(p+float2(float(x),float(y))*3.5);}}return s/25.0;}";
 private Shader blurShader(){if(android.os.Build.VERSION.SDK_INT<33)return shader;if(blur==null)blur=new RuntimeShader(BLUR);blur.setInputShader("rain",shader);return blur;}
 @Override protected void onDraw(Canvas c){if(Ui.dark)drawRain(c);}
 @Override protected void onDetachedFromWindow(){if(source==this)source=null;if(bitmap!=null){bitmap.recycle();bitmap=null;sample=null;shader=null;}super.onDetachedFromWindow();}
}
