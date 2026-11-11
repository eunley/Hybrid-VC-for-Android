package com.svc.voice;
import android.content.Context;
import android.graphics.*;
import android.widget.LinearLayout;
/** Samples ONLY background rain, never captures pager, text or foreground drops. */
final class FrostCard extends LinearLayout {
 private final Paint paint=new Paint(3);private final RectF bounds=new RectF();private final int[] loc=new int[2];
 FrostCard(Context c){super(c);setWillNotDraw(false);if(BackdropRain.source!=null)BackdropRain.source.register(this);}
 @Override protected void onDraw(Canvas c){super.onDraw(c);if(!Ui.dark)return;bounds.set(0,0,getWidth(),getHeight());BackdropRain b=BackdropRain.source;if(b!=null){b.drawSample(c,this,bounds,paint,loc);}paint.setColor(0x60242529);c.drawRoundRect(bounds,Ui.dp(getContext(),22),Ui.dp(getContext(),22),paint);}
}
