package com.svc.voice;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Independent, draggable auxiliary strip owned by FloatingMenuService.
 *
 * <p>Lifetime: the timers only run while the strip is attached and the menu switch is on; every
 * callback is removed on stop so nothing keeps the main looper or the window alive.
 */
public final class WaveAuxWindow {

    private static final int FRAME_MS = 33;          // ~30 fps
    private static final int POLL_MS = 100;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WaveStore store = new WaveStore();
    private final WaveAuxView view;
    private final TextView title;
    private final FrameLayout root;
    private WindowManager.LayoutParams pos;
    private boolean attached, showing;
    private long lastRevision = Long.MIN_VALUE;
    private int x = Integer.MIN_VALUE, y = Integer.MIN_VALUE;   // 未定位过
    private final int density;

    private final Runnable frame = new Runnable() {
        public void run() {
            if (!attached) return;
            if (store.publishing() && store.revision() != lastRevision) {
                lastRevision = store.revision();
                view.invalidate();
            }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    /** Cursor text and detection of "live conversion ended while displayed". */
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!attached) return;
            refreshTitle();
            // 唯一权威：显示中 且 实时在跑，才排帧。窗口保留（可拖动看最后一段），
            // 停止后不再刷帧也不再重绘。
            boolean live = RealtimeService.running;
            boolean want = showing && live;
            store.setPublishing(want);
            if (want && !handler.hasCallbacks(frame)) handler.post(frame);
            else if (!want) handler.removeCallbacks(frame);
            handler.postDelayed(this, POLL_MS);
        }
    };

    public WaveAuxWindow(Context c) {
        ctx = c;
        density = Math.round(c.getResources().getDisplayMetrics().density);
        wm = c.getSystemService(WindowManager.class);
        root = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xcc14181f);
        bg.setCornerRadius(px(10));
        bg.setStroke(Math.max(1, density / 2), 0x55ffffff);
        root.setBackground(bg);
        root.setPadding(px(4), px(2), px(4), px(2));

        title = new TextView(c);
        title.setTextColor(0xffccd6e0);
        title.setTextSize(9);
        title.setSingleLine(true);

        view = new WaveAuxView(c, () -> store, c.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        vl.topMargin = px(13);
        vl.bottomMargin = px(1);

        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, px(14));
        root.addView(title, tl);
        root.addView(view, vl);
        refreshTitle();
    }

    public WaveStore store() { return store; }

    private int px(float dp) { return Math.round(dp * ctx.getResources().getDisplayMetrics().density); }

    private void refreshTitle() {
        String s = WaveProbe.cursorText();
        if (!s.contentEquals(title.getText())) title.setText(s);
    }

    /** Drag anywhere on the strip; the whole window moves, so the traces never re-lay-out. */
    private void installDrag() {
        root.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int ox, oy;
            boolean moving;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY();
                        ox = WaveAuxWindow.this.pos.x;
                        oy = WaveAuxWindow.this.pos.y;
                        moving = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(e.getRawX() - dx, e.getRawY() - dy) > px(4)) moving = true;
                        if (moving) {
                            WaveAuxWindow.this.pos.x = ox + (int) (e.getRawX() - dx);
                            WaveAuxWindow.this.pos.y = oy + (int) (e.getRawY() - dy);
                            clampPos();
                            try { wm.updateViewLayout(root, WaveAuxWindow.this.pos); }
                            catch (RuntimeException ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        WaveAuxWindow.this.x = WaveAuxWindow.this.pos.x;
                        WaveAuxWindow.this.y = WaveAuxWindow.this.pos.y;
                        moving = false;
                        return true;
                }
                return false;
            }
        });
    }

    private void clampPos() {
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        int w = pos.width > 0 ? pos.width : px(240);
        int h = pos.height > 0 ? pos.height : px(96);
        pos.x = Math.max(-w / 6, Math.min(pos.x, sw - w / 2));
        pos.y = Math.max(0, Math.min(pos.y, sh - h / 2));
    }

    /** Attach the strip. Safe to call repeatedly. */
    public boolean show() {
        if (attached) return true;
        pos = new WindowManager.LayoutParams(
                px(240), px(96),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        pos.gravity = Gravity.TOP | Gravity.LEFT;
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        if (x == Integer.MIN_VALUE) {
            x = sw - px(246);
            y = sh / 3;
        }
        pos.x = x;
        pos.y = y;
        installDrag();
        try {
            wm.addView(root, pos);
        } catch (RuntimeException e) {
            return false;
        }
        attached = true;
        showing = true;
        lastRevision = Long.MIN_VALUE;      // 首帧必定重绘一次，避免窗口空白
        store.setPublishing(RealtimeService.running);
        startTimers();
        return true;
    }

    /** Detach the strip and drop every scheduled callback. */
    public void hide() {
        stopTimers();
        store.setPublishing(false);
        showing = false;
        if (attached) {
            try { wm.removeView(root); } catch (RuntimeException ignored) { }
            attached = false;
        }
    }

    public boolean isShowing() { return attached; }

    /** Service destruction: drop callbacks, the window and the ring. Idempotent. */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        release();
    }

    /** Menu switch turned off: detach and release the ring. */
    public void release() {
        hide();
        store.clear();
        lastRevision = Long.MIN_VALUE;
    }

    private void startTimers() {
        handler.removeCallbacks(frame);
        handler.removeCallbacks(poll);
        handler.post(frame);
        handler.post(poll);
    }

    /** Force one repaint now (e.g. right after the switch is turned on). */
    public void invalidateNow() {
        lastRevision = Long.MIN_VALUE;
        if (attached) view.invalidate();
    }

    private void stopTimers() {
        handler.removeCallbacks(frame);
        handler.removeCallbacks(poll);
        store.setPublishing(false);
    }

    /**
     * Hint only: the 100 ms poll decides whether the frame timer should actually run, so the menu
     * tick and the poll can never fight over the flag.
     */
    public void setPublishing(boolean on) {
        if (!attached) return;
        if (on && store.publishing() && handler.hasCallbacks(frame)) store.setPublishing(true);
        refreshTitle();
    }
}
