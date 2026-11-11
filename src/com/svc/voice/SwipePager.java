package com.svc.voice;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;

/**
 * 横向翻页容器：左右滑动在 5 个页面间切换，纵向滚动交给内部 ScrollView。
 * 只移动当前页与相邻页，切换后复位，避免整页重排。
 */
public class SwipePager extends FrameLayout {

    public interface OnPageChanged { void onPage(int idx); }

    public interface OnProgress { void onProgress(float page, boolean moving); }
    private final java.util.ArrayList<OnProgress> progressListeners = new java.util.ArrayList<>();
    public void addProgressListener(OnProgress l) { if (!progressListeners.contains(l)) progressListeners.add(l); }
    public void removeProgressListener(OnProgress l) { progressListeners.remove(l); }
    public float pageProgress() { return current - (getWidth() == 0 || count == 0 ? 0 : getChildAt(current).getTranslationX()/getWidth()); }
    public boolean isPageMoving() { return dragging || settling; }
    private void progress() { for (OnProgress l : new java.util.ArrayList<>(progressListeners)) l.onProgress(pageProgress(), isPageMoving()); }
    private void cancelSettle() { ValueAnimator old=settleAnim; settleAnim=null; settling=false; if(old!=null) old.cancel(); }
    private OnPageChanged callback;
    private int current = 0;
    private int count = 0;
    private float downX, downY, lastX;
    private boolean dragging;
    private VelocityTracker vt;
    private final int slop;
    private int neighbor = -1;
    private int dirSign = 0;
    private ValueAnimator settleAnim;
    private boolean settling;
    private int settleDestination = -1;
    private boolean seekGesture;

    public SwipePager(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    public void setOnPageChanged(OnPageChanged cb) { this.callback = cb; }

    public void addPage(View v) {
        addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        count = getChildCount();
        v.setVisibility(count - 1 == current ? View.VISIBLE : View.GONE);
    }

    public int currentIndex() { return current; }

    public void setCurrent(int idx, boolean animate) {
        if (count == 0) return;
        if (idx < 0) idx = 0;
        if (idx >= count) idx = count - 1;
        cancelSettle();
        current = idx;
        for (int i = 0; i < count; i++) {
            View v = getChildAt(i);
            v.animate().cancel();
            v.setTranslationX(0);
            v.setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        neighbor = -1;
        if(vt!=null){vt.recycle();vt=null;}
        dragging = false;
        dirSign = 0;
        progress();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Finish the old decision before establishing a new gesture. The page
                // callback may call setCurrent(), so never rebase a live drag around it.
                if (settling) finishSettle(settleDestination);
                downX = ev.getX();
                downY = ev.getY();
                lastX = downX;
                dragging = false;
                neighbor = -1;
                dirSign = 0;
                seekGesture = isInsideSeekBar((int) ev.getX(), (int) ev.getY());
                if (seekGesture) return false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (seekGesture) return false;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    int dir = dx > 0 ? -1 : 1;
                    int n = current + dir;
                    if (n < 0 || n >= count) return false;
                    dragging = true;
                    dirSign = dir;
                    neighbor = n;
                    getChildAt(neighbor).setVisibility(View.VISIBLE);
                    getChildAt(neighbor).setTranslationX(dir > 0 ? getWidth() : -getWidth());
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (vt == null) vt = VelocityTracker.obtain();
                    vt.clear();
                    vt.addMovement(ev);
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        float w = getWidth();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                if (dx > w) dx = w;
                if (dx < -w) dx = -w;
                int wantDir = Math.abs(dx)<.5f && dirSign!=0 ? dirSign : dx >= 0 ? -1 : 1;
                if (wantDir != dirSign) {
                    int n = current + wantDir;
                    if (n < 0 || n >= count) {
                        dx = 0;
                    } else {
                        if (neighbor >= 0) getChildAt(neighbor).setVisibility(View.GONE);
                        dirSign = wantDir;
                        neighbor = n;
                        getChildAt(neighbor).setVisibility(View.VISIBLE);
                        getChildAt(neighbor).setTranslationX(wantDir > 0 ? w : -w);
                    }
                }
                getChildAt(current).setTranslationX(dx);
                if (neighbor >= 0 && neighbor < count) {
                    getChildAt(neighbor).setTranslationX(dx + dirSign * w);
                }
                progress();
                lastX = ev.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float dx2 = getChildAt(current).getTranslationX();
                float vx = 0;
                try {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                } catch (Throwable ignore) {}
                boolean commit = Math.abs(dx2) > w * 0.26f || (Math.abs(vx) > 900 && vx * dx2 > 0);
                if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) commit = false;
                if (commit && neighbor >= 0 && dx2 * dirSign < 0) {
                    final int target = neighbor;
                    final float end = dx2 > 0 ? w : -w;
                    animateSettle(end, true, target);
                } else {
                    animateSettle(0, false, -1);
                }
                dragging = false;
                if (vt != null) { vt.recycle(); vt = null; }
                return true;
        }
        return true;
    }

    private void animateSettle(final float end, final boolean commit, final int target) {
        final View cur = getChildAt(current);
        final View nb = (neighbor >= 0 && neighbor < count) ? getChildAt(neighbor) : null;
        final float start = cur.getTranslationX();
        final float w = getWidth();
        cancelSettle();
        settling = true;
        settleDestination = commit ? target : current;
        settleAnim = ValueAnimator.ofFloat(start, end);
        settleAnim.setDuration(220);
        settleAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        final ValueAnimator active = settleAnim;
        settleAnim.addUpdateListener(a -> {
            if (settleAnim != active) return;
            float x = (float) a.getAnimatedValue();
            cur.setTranslationX(x);
            if (nb != null) nb.setTranslationX(x + dirSign * w);
            progress();
        });
        settleAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (settleAnim != a) return;
                finishSettle(commit ? target : current);
            }
        });
        settleAnim.start();
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void finishSettle(int next) {
        int previous = current;
        cancelSettle();
        settleDestination = -1;
        dragging = false;
        if (vt != null) { vt.recycle(); vt = null; }
        if (count == 0) return;
        current = Math.max(0, Math.min(next, count - 1));
        for (int i = 0; i < count; i++) {
            View v = getChildAt(i);
            v.setTranslationX(0);
            v.setVisibility(i == current ? View.VISIBLE : View.GONE);
        }
        neighbor = -1;
        dirSign = 0;
        progress();
        if (previous != current && callback != null) callback.onPage(current);
    }

    /** 手势打断动画时立即归位（不切换页面） */
    private void snapNow() {
        cancelSettle();
        settling = false;
        if (count == 0) return;
        for (int i = 0; i < count; i++) {
            View v = getChildAt(i);
            v.setTranslationX(0);
            v.setVisibility(i == current ? View.VISIBLE : View.GONE);
        }
        neighbor = -1;
        dirSign = 0;
        dragging = false;
        progress();
    }

    @Override protected void onDetachedFromWindow() { snapNow(); if(vt!=null){vt.recycle();vt=null;} super.onDetachedFromWindow(); }

    /** 起手落在 SeekBar 上时让给控件本身（水平拖动滑块） */
    private boolean isInsideSeekBar(int x, int y) {
        View v = getChildAt(current);
        return v != null && findSeek(v, x, y);
    }

    private boolean findSeek(View v, int x, int y) {
        if (v instanceof android.widget.SeekBar) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            int[] root = new int[2];
            getLocationInWindow(root);
            int lx = loc[0] - root[0], ly = loc[1] - root[1];
            return x >= lx && x <= lx + v.getWidth() && y >= ly && y <= ly + v.getHeight();
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (findSeek(g.getChildAt(i), x, y)) return true;
            }
        }
        return false;
    }
}