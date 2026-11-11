/* Copyright 2025 Kyant. Licensed under Apache-2.0.
 * Modified 2026: Java View port, nav coordinates, combined row sampler,
 * RGB dispersion with shared rain sampling. See assets/licenses. */
package com.svc.voice;
import android.graphics.*;
final class GlassShader {
    private static volatile boolean enabled = true;
    static void setEnabled(boolean value) { enabled = value; }
    static boolean isEnabled() { return enabled; }
    // Same subtle pixel spread for UI glass and all refractive rain shapes.
    static final String DISPERSION_AGSL =
        "float2 glassSpread(float2 axis,float edge){return axis*(0.35+1.15*clamp(edge,0.0,1.0));}";
    static final String RAIN_DISPERSION_AGSL = DISPERSION_AGSL +
        "half3 rainSample(float2 at,float2 axis,float edge){float2 spread=glassSpread(axis,edge);half3 c=ui.eval(at).rgb;c.r=ui.eval(at+spread).r;c.b=ui.eval(at-spread).b;return c;}";
    // RoundedRectSDF and circleMap refraction from Kyant0/AndroidLiquidGlass.
    private static final String AGSL = DISPERSION_AGSL +
        "uniform shader content; uniform shader row; uniform float4 bounds;" +
        "uniform float radius; uniform float height; uniform float amount; uniform float depth; uniform float rowMix;" +
        "float sdRoundedRect(float2 p,float2 h,float r){float2 q=abs(p)-(h-float2(r));return length(max(q,0.0))-r+min(max(q.x,q.y),0.0);}" +
        "float2 gradSdRoundedRect(float2 p,float2 h,float r){float2 q=abs(p)-(h-float2(r));if(q.x>=0.0||q.y>=0.0){float2 v=max(q,0.0);return sign(p)*v/max(length(v),0.0001);}float x=step(q.y,q.x);return sign(p)*float2(x,1.0-x);}" +
        "float circleMap(float x){return 1.0-sqrt(max(0.0,1.0-x*x));}" +
        "half4 main(float2 coord){float2 h=bounds.zw*0.5;float2 p=coord-bounds.xy-h;float sd=sdRoundedRect(p,h,radius);float2 at=coord;" +
        "if(-sd<height){float d=circleMap(clamp(1.0+min(sd,0.0)/max(height,0.001),0.0,1.0))*amount;float gr=min(radius*1.5,min(h.x,h.y));float2 g=gradSdRoundedRect(p,h,gr)+depth*p/max(length(p),0.0001);at+=d*g/max(length(g),0.0001);}" +
        "float edge=1.0-smoothstep(0.0,max(height,1.0),abs(sd));float2 axis=normalize(p+float2(0.001));float2 spread=glassSpread(axis,edge);half4 bg=content.eval(at);bg.r=content.eval(at+spread).r;bg.b=content.eval(at-spread).b;half4 fg=row.eval(at)*rowMix;half4 fr=row.eval(at+spread)*rowMix;half4 fb=row.eval(at-spread)*rowMix;fg.r=fr.r;fg.b=fb.b;half4 c=fg+bg*(1.0-fg.a);float rim=1.0-smoothstep(0.0,1.4,abs(sd));return half4(min(c.rgb+half3(rim*0.12)*c.a,half3(c.a,c.a,c.a)),c.a);}";
    static Object build(BitmapShader bg, BitmapShader row) {
        android.graphics.RuntimeShader s=new android.graphics.RuntimeShader(AGSL);
        s.setInputShader("content",bg); s.setInputShader("row",row); return s;
    }
    static void configure(Object shader, RectF r,float radius,float height,float amount,float rowMix) {
        android.graphics.RuntimeShader s=(android.graphics.RuntimeShader)shader;
        s.setFloatUniform("bounds",r.left,r.top,r.width(),r.height());
        s.setFloatUniform("radius",radius);s.setFloatUniform("height",height);
        s.setFloatUniform("amount",-amount);s.setFloatUniform("depth",0.08f);s.setFloatUniform("rowMix",rowMix);
    }
    private GlassShader() {}
}
