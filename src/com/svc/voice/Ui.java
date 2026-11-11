package com.svc.voice;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 原生设计系统：Miuix 风格的圆角卡片 / 大标题留白 / 分组，克制蓝色重心。
 * 这里只做"风格参考"，不接入 Miuix 库（Kotlin/Compose，本项目是手工 aapt 构建的纯 Java）。
 */
public final class Ui {

    public static boolean dark;
    public static int bg, card, cardStroke, text, textDim, textFaint, accent, accentSoft,
            danger, divider, navTint, auraA, auraB, fieldBg;
    public static int navActive, navIcon, navIconActive;

    private Ui() {}

    public static boolean animationsAllowed() {
        return Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }


    public static void init(Context c) {
        int mode = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bg = 0xFF000000; card = 0xFF141519; cardStroke = 0x1FFFFFFF; fieldBg = 0x14FFFFFF;
            text = 0xFFF2F4F8; textDim = 0xFF9AA4B2; textFaint = 0xFF6E7885;
            accent = 0xFF5B8CFF; accentSoft = 0x2E5B8CFF; danger = 0xFFFF6B6B;
            divider = 0x1FFFFFFF; navTint = 0xE6000000;
            auraA = bg; auraB = bg;
            navActive = 0x1FFFFFFF; navIcon = 0xFFB6BDC8; navIconActive = 0xFFFFFFFF;
        } else {
            bg = 0xFFFFFFFF; card = 0xFFF4F4F6; cardStroke = 0x12000000; fieldBg = 0x0A000000;
            text = 0xFF101317; textDim = 0xFF6B7280; textFaint = 0xFF9AA3AE;
            accent = 0xFF2F6BFF; accentSoft = 0x162F6BFF; danger = 0xFFD1453B;
            divider = 0x0F000000; navTint = 0xF2FFFFFF;
            auraA = bg; auraB = bg;
            navActive = 0x14000000; navIcon = 0xFF7B8290; navIconActive = 0xFF111317;
        }
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static Typeface regular() {
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable round(int color, float radiusPx, int strokeWidth, int strokeColor) {
        GradientDrawable d = round(color, radiusPx);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean med) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(med ? medium() : regular());
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView kicker(Context c, String s) {
        TextView t = text(c, s, 10.5f, textFaint, true);
        t.setLetterSpacing(0.18f);
        return t;
    }

    public static TextView hero(Context c, String s) {
        TextView t = text(c, s, 30f, text, true);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView subtitle(Context c, String s) {
        TextView t = text(c, s, 13f, textDim, false);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    public static TextView label(Context c, String s) {
        TextView t = text(c, s, 11f, textFaint, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    public static TextView hint(Context c, String s) {
        TextView t = text(c, s, 12f, textDim, false);
        t.setLineSpacing(dp(c, 2), 1f);
        return t;
    }

    public static TextView value(Context c, String s) {
        return text(c, s, 13f, textDim, false);
    }

    public static LinearLayout box(Context c, int orientation) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        return l;
    }

    public static LinearLayout pageBody(Context c) {
        LinearLayout l = box(c, LinearLayout.VERTICAL);
        int p = dp(c, 20);
        l.setPadding(p, dp(c, 10), p, dp(c, 24));
        return l;
    }

    /** 大标题 + kicker + 副标题；留白是刻意的 */
    public static void header(Context c, LinearLayout parent, String kicker, String title, String sub) {
        parent.addView(kicker(c, kicker), lp());
        TextView t = hero(c, title);
        LinearLayout.LayoutParams tlp = lp();
        tlp.topMargin = dp(c, 6);
        parent.addView(t, tlp);
        if (sub != null) {
            TextView s = subtitle(c, sub);
            LinearLayout.LayoutParams slp = lp();
            slp.topMargin = dp(c, 6);
            slp.bottomMargin = dp(c, 14);
            parent.addView(s, slp);
        }
    }

    public static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new FrostCard(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 18));
        l.setBackground(round(dark?0x00242529:card, dp(c, 22), Math.max(1, dp(c, 0.7f)), cardStroke));
        if (Build.VERSION.SDK_INT >= 21) l.setElevation(dp(c, 1));
        return l;
    }

    public static void cardTop(Context c, LinearLayout parent, Integer sectionRes) {
        if (sectionRes != null) {
            TextView l = label(c, c.getString(sectionRes));
            LinearLayout.LayoutParams lp = lp();
            lp.bottomMargin = dp(c, 10);
            parent.addView(l, lp);
        }
    }

    public static void sectionGap(Context c, LinearLayout parent) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, dp(c, 14));
        parent.addView(v, lp);
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(c, 0.6f)));
        lp.topMargin = dp(c, 12);
        lp.bottomMargin = dp(c, 6);
        v.setLayoutParams(lp);
        return v;
    }

    /** 主/次/幽灵/危险 按钮，高度与圆角统一 */
    public static Button button(Context c, String s, int style) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14.5f);
        b.setTypeface(medium());
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        int fill, txt;
        switch (style) {
            case 0: fill = accent; txt = Color.WHITE; break;
            case 2: fill = 0x00000000; txt = accent; break;
            case 3: fill = 0x22D1453B; txt = danger; break;
            default: fill = accentSoft; txt = accent; break;
        }
        GradientDrawable g = round(fill, dp(c, 14));
        if (style == 1 || style == 3) g.setStroke(Math.max(1, dp(c, 0.7f)), (style == 1 ? 0x202F6BFF : 0x22FF6B6B));
        if (style == 2) g.setStroke(Math.max(1, dp(c, 0.7f)), cardStroke);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        b.setTextColor(txt);
        return b;
    }

    public static void addButtonRow(Context c, LinearLayout parent, View... views) {
        LinearLayout row = box(c, LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(c, 10);
            row.addView(views[i], lp);
        }
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(c, 12);
        parent.addView(row, lp);
    }

    /** 标题 + 副标题 + 右侧控件 的一行 */
    public static LinearLayout row(Context c, String title, String sub, View right) {
        LinearLayout r = box(c, LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 52));
        LinearLayout col = box(c, LinearLayout.VERTICAL);
        TextView t = text(c, title, 14.5f, text, false);
        col.addView(t, lp());
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11.5f, textFaint, false);
            LinearLayout.LayoutParams slp = lp();
            slp.topMargin = dp(c, 3);
            col.addView(s, slp);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (right != null) r.addView(right);
        return r;
    }

    /** 可点击的设置行：右侧显示当前值 + chevron */
    public static LinearLayout pickerRow(Context c, String title, String sub, TextView valueView,
                                         View.OnClickListener click) {
        LinearLayout r = box(c, LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 56));
        r.setPadding(dp(c, 2), dp(c, 8), dp(c, 2), dp(c, 8));
        r.setBackground(ripple(c, 0x00000000, dp(c, 12)));
        LinearLayout col = box(c, LinearLayout.VERTICAL);
        col.addView(text(c, title, 14.5f, text, false), lp());
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 11.5f, textFaint, false);
            LinearLayout.LayoutParams slp = lp();
            slp.topMargin = dp(c, 3);
            col.addView(s, slp);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (valueView != null) r.addView(valueView);
        IconView chev = icon(c, IconView.CHEVRON, textFaint, dp(c, 16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16));
        clp.leftMargin = dp(c, 8);
        r.addView(chev, clp);
        r.setOnClickListener(click);
        return r;
    }

    /** LSPosed 风格单行卡：标题 + 值 + chevron */
    public static LinearLayout linkCard(Context c, String title, TextView value,
                                        View.OnClickListener click) {
        LinearLayout row = box(c, LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 18), dp(c, 16), dp(c, 14), dp(c, 16));
        row.setBackground(ripple(c, card, dp(c, 22)));
        row.addView(text(c, title, 15.5f, text, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (value != null) row.addView(value);
        IconView chev = icon(c, IconView.CHEVRON, textFaint, dp(c, 15));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, 15), dp(c, 15));
        clp.leftMargin = dp(c, 8);
        row.addView(chev, clp);
        row.setOnClickListener(click);
        return row;
    }

    public static RippleDrawable ripple(Context c, int fill, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x1A000000), g, null);
    }

    public static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setTextSize(14f);
        e.setTextColor(text);
        e.setHintTextColor(textFaint);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setBackground(round(fieldBg, dp(c, 12), Math.max(1, dp(c, 0.7f)), cardStroke));
        e.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    public static Switch switchView(Context c, boolean checked) {
        Switch s = new Switch(c);
        s.setChecked(checked);
        int[][] states = new int[][]{{android.R.attr.state_checked}, new int[]{}};
        s.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, Color.WHITE}));
        s.setTrackTintList(new ColorStateList(states, new int[]{accent, dark ? 0x33FFFFFF : 0x22000000}));
        s.setPadding(0, 0, 0, 0);
        return s;
    }

    /** 小胶囊（设备 / 状态） */
    public static TextView chip(Context c, String s, boolean accentOn) {
        TextView t = text(c, s, 11f, accentOn ? accent : textDim, true);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        t.setBackground(round(accentOn ? accentSoft : fieldBg, dp(c, 20)));
        t.setLetterSpacing(0.04f);
        return t;
    }

    /** Shared LSPosed-style status surface. Icons remain vector drawables at 24dp. */
    public static final class StatusCard extends LinearLayout {
        public static final int IDLE=0, LOADING=1, RUNNING=2, ERROR=3;
        public final TextView title, subtitle;
        public final IconView icon;
        private int lastState = -1;
        private boolean rootDecoration;
        private final Paint rootPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path rootMark=new Path();
        public void setRootDecoration(){
            rootDecoration=true;lastState=-1;icon.setVisibility(GONE);subtitle.setVisibility(GONE);
            setMinimumHeight(dp(getContext(),92));setPadding(dp(getContext(),20),dp(getContext(),14),dp(getContext(),20),dp(getContext(),14));setClipToOutline(true);setState(ERROR);
            LinearLayout col=(LinearLayout)title.getParent();col.setOrientation(HORIZONTAL);col.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams content=(LinearLayout.LayoutParams)col.getLayoutParams();content.leftMargin=0;col.setLayoutParams(content);
            TextView fixed=Ui.text(getContext(),"SU",24,text,true);fixed.setTextColor(Color.WHITE);fixed.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD));
            col.addView(fixed,0,new LinearLayout.LayoutParams(-2,-2));
            LinearLayout.LayoutParams status=new LinearLayout.LayoutParams(-2,-2);status.leftMargin=dp(getContext(),12);title.setLayoutParams(status);title.setTextSize(24);
            title.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence t,int st,int count,int after){}
                public void onTextChanged(CharSequence t,int st,int before,int count){}
                public void afterTextChanged(android.text.Editable t){if(t.toString().startsWith("SU "))t.delete(0,3);}
            });invalidate();
        }
        @Override protected void dispatchDraw(Canvas c){
            if(rootDecoration){
                float r=dp(getContext(),39),x=getWidth()-r*.72f,y=getHeight()-r*.65f;
                rootPaint.setColor(lastState==RUNNING?0xFF36D16A:0xFFEA7C85);rootPaint.setStyle(Paint.Style.STROKE);rootPaint.setStrokeWidth(dp(getContext(),9));rootPaint.setStrokeCap(Paint.Cap.ROUND);rootPaint.setStrokeJoin(Paint.Join.ROUND);
                c.drawCircle(x,y,r,rootPaint);rootMark.reset();
                if(lastState==RUNNING){rootMark.moveTo(x-r*.43f,y);rootMark.lineTo(x-r*.1f,y+r*.30f);rootMark.lineTo(x+r*.44f,y-r*.32f);}
                else {rootMark.moveTo(x-r*.28f,y-r*.28f);rootMark.lineTo(x+r*.28f,y+r*.28f);rootMark.moveTo(x+r*.28f,y-r*.28f);rootMark.lineTo(x-r*.28f,y+r*.28f);}
                c.drawPath(rootMark,rootPaint);
            }
            super.dispatchDraw(c);
        }
        public StatusCard(Context c) {
            super(c); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(c,20),dp(c,20),dp(c,20),dp(c,20));
            icon=Ui.icon(c,IconView.STATUS,text,dp(c,24));
            addView(icon,new LinearLayout.LayoutParams(dp(c,24),dp(c,24)));
            LinearLayout col=box(c,VERTICAL);
            title=Ui.text(c,"",16,text,true); subtitle=Ui.text(c,"",14,textDim,false);
            col.addView(title); LinearLayout.LayoutParams sub=lp(); sub.topMargin=dp(c,8); col.addView(subtitle,sub);
            LinearLayout.LayoutParams content=new LinearLayout.LayoutParams(0,-2,1);content.leftMargin=dp(c,24);addView(col,content);
            setState(IDLE);
        }
        public void setState(int state) {
            if (lastState == state) return;
            lastState = state;
            int fill=state==RUNNING?(rootDecoration?0xFF194D2E:0xFF216C3C):state==ERROR?0xFF873C42:state==LOADING?(dark?0xA0242529:card):card;
            int fg=state==IDLE?text:Color.WHITE;
            setBackground(round(fill,dp(getContext(),12)));setElevation(dp(getContext(),2));
            title.setTextColor(fg);subtitle.setTextColor(state==IDLE?textDim:0xDDFFFFFF);
            icon.setColor(fg);icon.setStatusKind(state==RUNNING?1:state==ERROR?3:state==LOADING?4:0);
        }
    }

    public static void dialogList(Context c, String title, String[] items, int checked,
                                  DialogInterface.OnClickListener onPick) {
        DialogSurface.builder(c)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (d, w) -> { onPick.onClick(d, w); d.dismiss(); })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void toast(Context c, String s) {
        android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    public static void space(Context c, LinearLayout parent, float dpH) {
        View v = new View(c);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(c, dpH)));
    }

    /** 手绘矢量图标，避免 emoji / 位图 */
    public static IconView icon(Context c, int type, int color, int sizePx) {
        IconView v = new IconView(c, type, color);
        v.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        return v;
    }

    public static class IconView extends View {
        public static final int WAVE = 0, MIC = 1, USER = 2, GEAR = 3, LOG = 4, FOLDER = 5,
                PLAY = 6, STOP = 7, CHECK = 8, PLUS = 9, TRASH = 10, CHEVRON = 11,
                CPU = 12, CHIP = 13, DOT = 14, HOME = 15, STATUS = 16;
        private final int type;
        private boolean selectedIcon; private int statusKind;
        private android.graphics.drawable.Drawable cachedOfficial; private int cachedOfficialId;
        public void setSelectedIcon(boolean v){selectedIcon=v;invalidate();}
        public void setStatusKind(int v){statusKind=v;invalidate();}
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        public IconView(Context c, int type, int color) {
            super(c);
            this.type = type;
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        private final android.database.ContentObserver animationScaleObserver = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { updateWaveAnimation(); }
        };
        private boolean observingAnimationScale;
        private android.animation.ValueAnimator waveAnimator;
        private boolean animationActive;
        private boolean hostResumed = true;
        private float wavePhase;
        private static final float[] STATIC_HEIGHTS = {6f, 13f, 17f, 11f, 5f};

        /** Enable only for the icon belonging to the currently active page. */
        public void setAnimationActive(boolean active) {
            animationActive = active;
            updateWaveAnimation();
        }

        /** Activity onResume/onPause should pass true/false. */
        public void setHostResumed(boolean resumed) {
            hostResumed = resumed;
            updateWaveAnimation();
        }

        private void updateWaveAnimation() {
            boolean run = type == WAVE && JobService.running && !JobService.cancelReq && animationActive && hostResumed
                    && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                    && animationsAllowed();
            if (!run) {
                if (waveAnimator != null) { waveAnimator.cancel(); waveAnimator = null; }
                wavePhase = 0f;
                invalidate();
            } else if (waveAnimator == null) {
                waveAnimator = android.animation.ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
                waveAnimator.setDuration(1400);
                waveAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                waveAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
                waveAnimator.addUpdateListener(a -> {
                    if (!animationsAllowed() || !JobService.running || JobService.cancelReq) { updateWaveAnimation(); return; }
                    wavePhase = (float) a.getAnimatedValue();
                    invalidate();
                });
                waveAnimator.start();
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if(type==WAVE && !observingAnimationScale){
                getContext().getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),false,animationScaleObserver);
                observingAnimationScale=true;
            }
            post(waveBusyPoll);
        }
        private final Runnable waveBusyPoll=new Runnable(){public void run(){updateWaveAnimation();if(isAttachedToWindow()&&type==WAVE)postDelayed(this,100);}};
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused); updateWaveAnimation();
        }
        @Override protected void onDetachedFromWindow() {
            removeCallbacks(waveBusyPoll);
            if(observingAnimationScale){getContext().getContentResolver().unregisterContentObserver(animationScaleObserver);observingAnimationScale=false;}
            if (waveAnimator != null) { waveAnimator.cancel(); waveAnimator = null; }
            super.onDetachedFromWindow();
        }
        @Override protected void onVisibilityChanged(View changed, int visibility) {
            super.onVisibilityChanged(changed, visibility);
            updateWaveAnimation();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateWaveAnimation();
        }

        public void setColor(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            if(type==HOME || type==GEAR || type==STATUS){
                int id=type==HOME?(selectedIcon?R.drawable.lsp_ic_baseline_home_24:R.drawable.lsp_ic_outline_home_24):type==GEAR?(selectedIcon?R.drawable.lsp_ic_baseline_settings_24:R.drawable.lsp_ic_outline_settings_24):statusKind==0?R.drawable.ui_status_idle:statusKind==4?R.drawable.ui_status_loading:statusKind==1?R.drawable.lsp_ic_round_check_circle_24:statusKind==2?R.drawable.lsp_ic_round_warning_24:R.drawable.lsp_ic_round_error_outline_24;
                if(cachedOfficial==null||cachedOfficialId!=id){cachedOfficial=getContext().getDrawable(id).mutate();cachedOfficialId=id;}
                android.graphics.drawable.Drawable icon=cachedOfficial;icon.setTint(p.getColor());int side=Math.min(getWidth(),getHeight());icon.setBounds((getWidth()-side)/2,(getHeight()-side)/2,(getWidth()+side)/2,(getHeight()+side)/2);icon.draw(cv);return;
            }
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            cv.save();
            cv.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            cv.scale(s / 24f, s / 24f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.9f);
            path.reset();
            switch (type) {
                case WAVE:
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 5; i++) {
                        float height = waveAnimator == null
                                ? STATIC_HEIGHTS[i]
                                : 4f + 14f * (0.5f + 0.5f * (float) Math.sin(wavePhase + i * 1.35f));
                        bar(cv, 2.4f + i * 4.2f, 12f - height / 2f, 2.4f, height);
                    }
                    break;
                case MIC:
                    rect.set(9f, 3f, 15f, 13f);
                    cv.drawRoundRect(rect, 3f, 3f, p);
                    rect.set(6f, 7.5f, 18f, 19f);
                    cv.drawArc(rect, 0, 180, false, p);
                    cv.drawLine(12f, 19f, 12f, 22f, p);
                    cv.drawLine(8.5f, 22f, 15.5f, 22f, p);
                    break;
                case USER:
                    cv.drawCircle(12f, 8.6f, 3.6f, p);
                    rect.set(5f, 13.5f, 19f, 25f);
                    cv.drawArc(rect, 180, 180, false, p);
                    break;
                case GEAR:
                    cv.drawCircle(12f, 12f, 3.1f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        float x1 = 12 + (float) Math.cos(a) * 5.6f, y1 = 12 + (float) Math.sin(a) * 5.6f;
                        float x2 = 12 + (float) Math.cos(a) * 8.4f, y2 = 12 + (float) Math.sin(a) * 8.4f;
                        cv.drawLine(x1, y1, x2, y2, p);
                    }
                    cv.drawCircle(12f, 12f, 8.6f, p);
                    break;
                case LOG:
                    cv.drawCircle(4.6f, 6.5f, 1.1f, p);
                    cv.drawCircle(4.6f, 12f, 1.1f, p);
                    cv.drawCircle(4.6f, 17.5f, 1.1f, p);
                    cv.drawLine(8f, 6.5f, 20f, 6.5f, p);
                    cv.drawLine(8f, 12f, 20f, 12f, p);
                    cv.drawLine(8f, 17.5f, 16f, 17.5f, p);
                    break;
                case FOLDER:
                    path.moveTo(3f, 7.5f);
                    path.lineTo(9.2f, 7.5f);
                    path.lineTo(11.2f, 10.2f);
                    path.lineTo(21f, 10.2f);
                    path.lineTo(21f, 19.5f);
                    path.lineTo(3f, 19.5f);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                case PLAY:
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(8.5f, 5.5f);
                    path.lineTo(19f, 12f);
                    path.lineTo(8.5f, 18.5f);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                case STOP:
                    p.setStyle(Paint.Style.FILL);
                    rect.set(7f, 7f, 17f, 17f);
                    cv.drawRoundRect(rect, 2.4f, 2.4f, p);
                    break;
                case CHECK:
                    path.moveTo(5f, 12.5f);
                    path.lineTo(10f, 17.5f);
                    path.lineTo(19f, 6.5f);
                    cv.drawPath(path, p);
                    break;
                case PLUS:
                    cv.drawLine(12f, 5.5f, 12f, 18.5f, p);
                    cv.drawLine(5.5f, 12f, 18.5f, 12f, p);
                    break;
                case TRASH:
                    cv.drawLine(4f, 7f, 20f, 7f, p);
                    cv.drawLine(9.5f, 7f, 9.5f, 4.5f, p);
                    cv.drawLine(14.5f, 7f, 14.5f, 4.5f, p);
                    cv.drawLine(9.5f, 4.5f, 14.5f, 4.5f, p);
                    path.moveTo(6f, 7f);
                    path.lineTo(7f, 20.5f);
                    path.lineTo(17f, 20.5f);
                    path.lineTo(18f, 7f);
                    cv.drawPath(path, p);
                    break;
                case CHEVRON:
                    path.moveTo(9.5f, 5f);
                    path.lineTo(16.5f, 12f);
                    path.lineTo(9.5f, 19f);
                    cv.drawPath(path, p);
                    break;
                case CPU:
                    rect.set(6f, 6f, 18f, 18f);
                    cv.drawRoundRect(rect, 2f, 2f, p);
                    rect.set(9.5f, 9.5f, 14.5f, 14.5f);
                    cv.drawRoundRect(rect, 1f, 1f, p);
                    for (int i = 0; i < 3; i++) {
                        float o = 8f + i * 4f;
                        cv.drawLine(o, 3f, o, 6f, p);
                        cv.drawLine(o, 18f, o, 21f, p);
                        cv.drawLine(3f, o, 6f, o, p);
                        cv.drawLine(18f, o, 21f, o, p);
                    }
                    break;
                case CHIP:
                    rect.set(4f, 4f, 20f, 20f);
                    cv.drawRoundRect(rect, 4f, 4f, p);
                    cv.drawCircle(12f, 12f, 3.4f, p);
                    cv.drawLine(12f, 4f, 12f, 7f, p);
                    cv.drawLine(12f, 17f, 12f, 20f, p);
                    cv.drawLine(4f, 12f, 7f, 12f, p);
                    cv.drawLine(17f, 12f, 20f, 12f, p);
                    break;
                case DOT:
                default:
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12f, 12f, 4f, p);
                    break;
            }
            cv.restore();
        }

        private void bar(Canvas cv, float x, float y, float w, float h) {
            rect.set(x, y, x + w, y + h);
            cv.drawRoundRect(rect, 1.6f, 1.6f, p);
        }
    }

    /** Static monochrome background. Compatibility setter intentionally does nothing. */
    public static class AuraView extends View {
        public AuraView(Context c) { super(c); setBackgroundColor(bg); }
        public void setAnimating(boolean on) { }
        @Override protected void onDraw(Canvas cv) { cv.drawColor(bg); }
    }
}
