package com.svc.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Two-trace auxiliary strip: microphone input on top, converted output below.
 *
 * <p>Both traces are drawn on the same absolute 16 kHz timeline (see {@link WaveStore}); the view
 * never left-aligns them independently, so an older converted trace keeps its own leading boundary
 * instead of being stretched to the window edge.
 */
public final class WaveAuxView extends View {

    public interface Source {
        WaveStore store();
    }

    private final Source source;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private float[] peaks = new float[0];
    private float[] rms = new float[0];
    private double displayCursor = Double.NaN;
    private long lastFrameNs;
    private long seenGeneration = -1;
    private long[] vp = new long[4];

    public WaveAuxView(Context c, Source s, float density) {
        super(c);
        this.source = s;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, density));
        axis.setColor(0x44ffffff);
        axis.setStrokeWidth(Math.max(1f, density * 0.7f));
        label.setTextSize(density * 9f);
        bg.setColor(0x00000000);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /** Rows: 0 = input (newer, flush right), 1 = output (older, own boundary). */
    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawRect(0, 0, w, h, bg);
        int half = h / 2;
        WaveStore st = source == null ? null : source.store();
        if (st == null) return;

        int span = Math.max(16, Math.min(WaveStore.CAPACITY, w / 2));
        if (peaks.length < span) {
            peaks = new float[span];
            rms = new float[span];
        }
        // One revision read, one geometry read: both traces come from the same snapshot so the
        // axis cannot tear between channels.
        synchronized (st) {
            vp = st.viewport(span);
        }
        long[] cursors=WaveProbe.counters();
        long played16=WaveProbe.playbackPosition16();
        double input16=cursors[0];
        long generation=(long)LiveWindowState.snapshot()[0];
        long now=System.nanoTime();
        if(Double.isNaN(displayCursor)||generation!=seenGeneration||input16<displayCursor){
            displayCursor=input16;seenGeneration=generation;
        }else{
            double dt=lastFrameNs==0?0.016:Math.min(0.1,(now-lastFrameNs)/1e9);
            displayCursor+=(input16-displayCursor)*(1-Math.exp(-dt/0.025));
        }
        lastFrameNs=now;
        // Capture drives both rows; slow conversion never stops the shared time axis.
        double firstCell=displayCursor/WaveStore.IN_PER_CELL-span*0.95;
        float cx=(float)((played16/WaveStore.IN_PER_CELL-firstCell)*w/span);
        cx=Math.max(0,Math.min(w,cx));
        long first=(long)Math.floor(firstCell);
        float offset=(float)((firstCell-first)*w/span);
        canvas.save();
        canvas.translate(-offset,0);
        drawTrace(canvas, st, WaveStore.SOURCE_INPUT, 0, half, first, span, 0xff66d9ef);
        drawTrace(canvas, st, WaveStore.SOURCE_OUTPUT, half, h, first, span, 0xffffb454);
        canvas.restore();
        axis.setColor(0xffeeff99);
        if(played16>=0){
            canvas.drawLine(cx,half+2,cx,h-2,axis);
            canvas.drawLine(cx-6,half+2,cx+6,half+2,axis);
            canvas.drawLine(cx-6,h-2,cx+6,h-2,axis);
        }
        axis.setColor(0x44ffffff);
        // Separator and the shared axis: the right edge is "now" for the newest trace.
        canvas.drawLine(0, half, w, half, axis);
        canvas.drawLine(w - 1, 0, w - 1, h, axis);

        String top = "MIC 16k";
        String bottom = "OUT 44.1k";
        label.setColor(0xddeeeeee);
        canvas.drawText(top, 4 * getResources().getDisplayMetrics().density, half * 0.35f, label);
        canvas.drawText(bottom, 4 * getResources().getDisplayMetrics().density, half + half * 0.35f, label);
    }

    private void drawTrace(Canvas canvas, WaveStore st, int which, int top, int bottom,
                           long first, int span, int color) {
        if (!st.cells(which, first, span, peaks, rms)) return;
        long head = which == WaveStore.SOURCE_INPUT ? vp[1] : vp[2];
        if (head < first) return;   // fully outside the window
        int w = getWidth();
        float mid = (top + bottom) / 2f;
        float amp = (bottom - top) / 2f - 2f;
        line.setColor(color);
        float step = w / (float) span;
        float x0 = -1, y0 = 0;
        for (int i = 0; i < span; i++) {
            long cell = first + i;
            if (cell > head) break;
            float v = Math.max(peaks[i], rms[i]);
            float x = (i + 0.5f) * step;
            float y = mid - v * amp;
            canvas.drawLine(x, mid-v*amp, x, mid+v*amp, line);
            if (x0 >= 0) canvas.drawLine(x0, y0, x, y, line);
            x0 = x;
            y0 = y;
        }
    }
}
