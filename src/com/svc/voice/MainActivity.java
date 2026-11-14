package com.svc.voice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 11, REQ_FILE = 12, REQ_ROLE = 14,
            REQ_NOTIF = 15, REQ_BATCH_TREE = 16;

    // 页签：0 设置(首页) 1 音频 2 实时 3 角色 4 日志
    private static final int TAB_SET = 0, TAB_AUDIO = 1, TAB_LIVE = 2, TAB_ROLES = 3, TAB_LOGS = 4;

    private Prefs prefs;

    /** 服务/UI 共用的就绪标志（不依赖本地化文案） */
    public static volatile boolean modelReady = false;
    public static volatile String residentState = "";

    private final List<File> roles = new ArrayList<>();
    private final List<File> npuRoles = new ArrayList<>();
    private Map<CheckBox, Integer> npuCbMap = new LinkedHashMap<>();

    private FrameLayout root;
    private RainOverlay rain;
    private SwipePager pagesHost;
    private Ui.AuraView aura;
    private GlassNav nav;
    private final View[] pages = new View[5];
    private final LinearLayout[] navItems = new LinearLayout[5];
    private final Ui.IconView[] navIcons = new Ui.IconView[5];
    private final TextView[] navLabels = new TextView[5];
    private int tabIndex = TAB_SET;

    // 音频页
    private TextView rolePickValue, fileLabel, fileChunkValue, outValue;
    private EditText eFileBuf, eOutPath;
    private TextView batchSummary, batchList, batchStartBtn;
    private DotSeekBar audioProgressBar, homeSpectrum;
    private final List<BatchItem> batchItems = new ArrayList<>();
    private Uri batchTree;
    private File currentFile;

    // 实时页
    private TextView liveState, liveSub, liveRoleValue, rtChunkValue, tvEcho;
    private EditText eRtBuf;
    private Switch swInject, swBypass;
    private SeekBar sbEcho;
    private Button liveBtn;

    // 角色页
    private LinearLayout rolesBox;
    private TextView rolesEmpty, roleHeaderValue;
    private Switch swNpu;
    private TextView npuRoleValue, npuStatus;
    private LinearLayout npuListBox;
    private EditText eNpuMs;
    private int npuRoleIndex = -1;

    // 日志页
    private TextView logView, logState;
    private ScrollView logScroll;

    // 设置页
    private TextView setVersion, setOutput, setThreads, setLang, setRoot;
    private Ui.StatusCard rootStatusCard, liveStatusCard;
    private LinearLayout rootHero;
    private TextView rootHeroState, rootHeroSub;
    private Ui.IconView rootHeroMark;
    private volatile int rootState = 0;   // 0 检测中 1 已授权 2 未授权
    private int threadsShown = 6;
 private SeekBar threadSeek;
 private volatile boolean rootChecking;

    private static final class BatchItem {
        final Uri uri;
        final String name;
        final long size;

        BatchItem(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    // ================= 生命周期 =================

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if(b==null) beginLogSession();
        Tune.init(this);
        prefs.outDir();
        Ui.init(this);
        GlassShader.setEnabled(getSharedPreferences("svc", MODE_PRIVATE)
                .getBoolean("liquid_glass_enabled", true));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (residentState.isEmpty()) residentState = getString(R.string.status_idle);
        setContentView(buildUi());
        if(prefs.httpEnabled())try{LocalHttpService.apply(this);}catch(Exception e){log(getString(R.string.http_restore_failed)+e.getMessage());}
        if (!"cpu".equals(prefs.device()) && !"npu".equals(prefs.device())) prefs.setDevice("cpu");
        Engine.device = prefs.device();
        cleanupTemp();
        ensurePerms();
        scanRoles();
        npuScanRoles();
        refreshAll();
        checkRoot();
        ModelPreloader.request(getApplicationContext());
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if(Intent.ACTION_MAIN.equals(intent.getAction()))beginLogSession();
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent in) {
        if (in == null) return;
        final String tf = in.getStringExtra("svc_file");
        if (tf == null) return;
        currentFile = new File(tf);
        if (fileLabel != null) fileLabel.setText(getString(R.string.file_selected, currentFile.getName()));
        switchTab(TAB_AUDIO);
        if ("1".equals(in.getStringExtra("svc_run"))) startJob(Uri.fromFile(currentFile), currentFile.getName());
    }

    private final android.os.Handler statusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() { refreshStatus(); statusHandler.postDelayed(this, 400); }
    };
    private final android.content.BroadcastReceiver modelStateReceiver = new android.content.BroadcastReceiver(){ public void onReceive(android.content.Context c, android.content.Intent i){refreshAll();refreshChunkValues();} };
    @Override
    protected void onResume() {
        super.onResume();
        ModelPreloader.request(getApplicationContext());
        if(pendingFloatingEnable){
            pendingFloatingEnable=false;
            boolean allowed=FloatingMenuService.canDraw(this);
            if(allowed)FloatingMenuService.start(this);
            if(floatingSwitch!=null)floatingSwitch.setChecked(allowed);
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(modelStateReceiver,new android.content.IntentFilter("com.svc.voice.MODEL_STATE"),android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(modelStateReceiver,new android.content.IntentFilter("com.svc.voice.MODEL_STATE"));
        AppLog.sink = s -> {final long generation=AppLog.generation;runOnUiThread(() -> {if(generation==AppLog.generation)appendLine(s);});};
        loadLogTail();
        statusHandler.removeCallbacks(statusRefresh);
        statusHandler.post(statusRefresh);
        refreshAll();
        if (aura != null) aura.setAnimating(true);
        if (rain != null) rain.setHostResumed(true);
        if (nav != null) { nav.refresh(); nav.setAnimationsEnabled(true); }
        for (Ui.IconView icon : navIcons) if (icon != null) icon.setHostResumed(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (rain != null) rain.setHostResumed(false);
        statusHandler.removeCallbacks(statusRefresh);
        unregisterReceiver(modelStateReceiver);
        if (nav != null) nav.setAnimationsEnabled(false);
        for (Ui.IconView icon : navIcons) if (icon != null) icon.setHostResumed(false);
        if (aura != null) aura.setAnimating(false);

        saveSettings();
    }

    private boolean stoppedForReopen, externalReturn;
    @Override public void startActivityForResult(Intent intent,int requestCode,Bundle options){externalReturn=true;super.startActivityForResult(intent,requestCode,options);}
    @Override public void startActivity(Intent intent){externalReturn=true;super.startActivity(intent);}
    
    @Override protected void onStart() { super.onStart();if(stoppedForReopen&&!externalReturn)beginLogSession();stoppedForReopen=false;externalReturn=false; }
    @Override protected void onStop() { stoppedForReopen=!isChangingConfigurations(); if(rain!=null) rain.setHostResumed(false); super.onStop(); }

    @Override
    protected void onDestroy() {
        stopQuickPlayer();
        AppLog.sink = null;
        super.onDestroy();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void toast(String s) {
        runOnUiThread(() -> Ui.toast(this, s));
    }

    // ================= 根布局 =================

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.bg);
        aura = new Ui.AuraView(this);
        root.addView(aura, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rain = new RainOverlay(this);
        root.addView(rain.backdrop, new FrameLayout.LayoutParams(-1, -1));
        root.addView(rain.lightningView(), new FrameLayout.LayoutParams(-1, -1));

        pagesHost = new SwipePager(this);
        pagesHost.setOnPageChanged(idx -> switchTab(idx));
        root.addView(pagesHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pages[TAB_SET] = buildSettingsPage();
        pages[TAB_AUDIO] = buildAudioPage();
        pages[TAB_LIVE] = buildLivePage();
        pages[TAB_ROLES] = buildRolesPage();
        pages[TAB_LOGS] = buildLogPage();
        for (View p : pages) {
            p.setVisibility(View.GONE);
            pagesHost.addPage(p);
        }

        buildNav();
        rain.setRainIntensity(readRainIntensity());
        rain.setTiltEnabled(getSharedPreferences("svc", MODE_PRIVATE).getBoolean("rain_tilt", true));
        root.addView(rain, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
                left = ins.getSystemWindowInsetLeft(); right = ins.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 28 && ins.getDisplayCutout() != null) {
                    android.view.DisplayCutout cutout = ins.getDisplayCutout();
                    top = Math.max(top, cutout.getSafeInsetTop());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    right = Math.max(right, cutout.getSafeInsetRight());
                }
            }
            pagesHost.setPadding(left, top, right, 0);
            // Page padding already reserves the floating navigation height.
            for (View page : pages) {
                if (page instanceof ScrollView) page.setPadding(0, 0, 0, dp(112) + bottom);
                else page.setPadding(dp(20), dp(10), dp(20), dp(112) + bottom);
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) nav.getLayoutParams();
            lp.leftMargin = dp(12) + left;
            lp.rightMargin = dp(12) + right;
            lp.bottomMargin = dp(10) + bottom;
            nav.setLayoutParams(lp);
            return ins;
        });
        {
            getWindow().setStatusBarColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 29) { getWindow().setStatusBarContrastEnforced(false); getWindow().setNavigationBarContrastEnforced(false); }
            getWindow().setNavigationBarColor(0x00000000);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!Ui.dark && Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!Ui.dark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        root.setSystemUiVisibility(flags);
        root.requestApplyInsets();
        return root;
    }

    private void buildNav() {
        nav = new GlassNav(this);
        nav.setRadiusDp(32);
        nav.setSource(pagesHost, aura);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68));
        nlp.gravity = Gravity.BOTTOM;
        nlp.leftMargin = dp(12);
        nlp.rightMargin = dp(12);
        nlp.bottomMargin = dp(10);
        nav.setLayoutParams(nlp);

        int[] labels = {R.string.nav_set, R.string.nav_audio, R.string.nav_live,
                R.string.nav_roles, R.string.nav_logs};
        int[] icons = {Ui.IconView.HOME, Ui.IconView.WAVE, Ui.IconView.MIC,
                Ui.IconView.GEAR, Ui.IconView.LOG};
        LinearLayout row = Ui.box(this, LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout item = Ui.box(this, LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(5), dp(2), dp(5));
            Ui.IconView ic = Ui.icon(this, icons[i], Ui.navIcon, dp(21));
            TextView lb = Ui.text(this, getString(labels[i]), 9.5f, Ui.navIcon, true);
            lb.setGravity(Gravity.CENTER);
            item.addView(ic);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(4);
            item.addView(lb, llp);
            item.setOnClickListener(v -> switchTab(idx));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            ilp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(item, ilp);
            navItems[i] = item;
            navIcons[i] = ic;
            navLabels[i] = lb;
        }
        nav.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nav.setTabCount(5);
        nav.setListener(new GlassNav.Listener() {
            @Override public void onPick(int idx) {
                if (idx != tabIndex) switchTab(idx);
            }
            @Override public void onHover(int idx) {
                paintNavSelection(idx);
            }
        });
        root.addView(nav, nlp);
        switchTab(TAB_SET);
    }

    private void switchTab(int idx) {
        int previousTab = tabIndex;
        tabIndex = idx;
        for (int i=0;i<navIcons.length;i++) if(navIcons[i]!=null) navIcons[i].setAnimationActive(true);
        if (pagesHost != null) pagesHost.setCurrent(idx, false);
        for (int i = 0; i < navItems.length; i++) {
            if (navItems[i] != null) navItems[i].setBackground(null);
        }
        paintNavSelection(idx);
        if (nav != null) nav.setSelected(idx, true);
        if (idx == TAB_LOGS) refreshLogScroll();
        if (idx == TAB_SET) checkRoot();
        if (nav != null) nav.refresh();
        if (rain != null && previousTab != idx) rain.onPageSettled(idx);
    }

    private void paintNavSelection(int idx) {
        for (int i = 0; i < navItems.length; i++) {
            if (navItems[i] == null) continue;
            boolean on = i == idx;
            navIcons[i].setSelectedIcon(on);
            navIcons[i].setColor(on ? Ui.navIconActive : Ui.navIcon);
            navLabels[i].setTextColor(on ? Ui.navIconActive : Ui.navIcon);
            navLabels[i].setTypeface(on ? Ui.medium() : Ui.regular());
        }
    }

    private ScrollView pageScroll() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setClipToPadding(false);
        sc.setPadding(0, 0, 0, dp(112));
        sc.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            if (nav != null) nav.refresh();

        });
        return sc;
    }

    private LinearLayout pageBody(ScrollView sc) {
        LinearLayout body = Ui.pageBody(this);
        sc.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    // ================= 头部 / 状态主卡 =================

    private void header(LinearLayout body, int titleRes, int subRes) {
        TextView t = Ui.hero(this, getString(titleRes));
        LinearLayout.LayoutParams tlp = Ui.lp();
        tlp.topMargin = dp(18);
        body.addView(t, tlp);
        tlp.bottomMargin = dp(18);
    }

    // ================= 页 0：音频变声 =================

    private View buildAudioPage() {
        ScrollView sc = pageScroll();
        LinearLayout body = pageBody(sc);
        header(body, R.string.title_audio, R.string.sub_audio);

        LinearLayout cfg = Ui.card(this);
        Ui.cardTop(this, cfg, R.string.sec_audio);
        rolePickValue = Ui.value(this, "");
        cfg.addView(Ui.pickerRow(this, getString(R.string.lbl_role), null, rolePickValue,
                v -> pickRoleDialog()));
        cfg.addView(Ui.divider(this));
        fileChunkValue = Ui.value(this, "");
        cfg.addView(Ui.pickerRow(this, getString(R.string.lbl_chunk), null, fileChunkValue,
                v -> showChunkDialog(true)));
        cfg.addView(Ui.divider(this));
        LinearLayout bufRow = Ui.box(this, LinearLayout.HORIZONTAL);
        bufRow.setGravity(Gravity.CENTER_VERTICAL);
        bufRow.setPadding(0, dp(6), 0, dp(6));
        bufRow.addView(Ui.text(this, getString(R.string.lbl_buffer), 14.5f, Ui.text, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        eFileBuf = Ui.field(this, "");
        eFileBuf.setEms(4);
        eFileBuf.setGravity(Gravity.CENTER);
        bufRow.addView(eFileBuf, new LinearLayout.LayoutParams(dp(88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cfg.addView(bufRow);
        cfg.addView(Ui.divider(this));
        android.widget.Switch fileDenoise=Ui.switchView(this,prefs.fileDenoise());
        fileDenoise.setOnCheckedChangeListener((b,on)->prefs.setDenoise(false,on));
        cfg.addView(Ui.row(this,getString(R.string.ui_denoise),null,fileDenoise));
        cfg.addView(Ui.hint(this,getString(R.string.ui_denoise_file_hint)));
        android.widget.Switch fileVolumeLimit=Ui.switchView(this,prefs.fileVolumeLimit());
        fileVolumeLimit.setOnCheckedChangeListener((b,on)->prefs.setVolumeLimit(false,on));
        cfg.addView(Ui.row(this,getString(R.string.ui_volume_limit),null,fileVolumeLimit));
        cfg.addView(Ui.hint(this,getString(R.string.ui_volume_limit_file_hint)));
        TextView pitchLabel=Ui.text(this,getString(R.string.ui_pitch)+prefs.filePitchSemitones()+getString(R.string.ui_semitones),14.5f,Ui.text,false);
        cfg.addView(pitchLabel);
        android.widget.SeekBar pitch=new android.widget.SeekBar(this);
        pitch.setMax(48);pitch.setProgress(prefs.filePitchSemitones()+24);
        pitch.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){int shift=progress-24;prefs.set("file_pitch_semitones",shift);pitchLabel.setText(getString(R.string.ui_pitch)+(shift>0?"+":"")+shift+getString(R.string.ui_semitones));}
            public void onStartTrackingTouch(android.widget.SeekBar bar){}
            public void onStopTrackingTouch(android.widget.SeekBar bar){}
        });
        cfg.addView(pitch);
        cfg.addView(Ui.hint(this,getString(R.string.ui_pitch_hint)));
        body.addView(cfg, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout file = Ui.card(this);
        Ui.cardTop(this, file, R.string.sec_file);
        fileLabel = Ui.text(this, getString(R.string.file_none), 14f, Ui.text, false);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        file.addView(fileLabel);
        Button pick = Ui.button(this, getString(R.string.btn_pick_file), 1);
        pick.setOnClickListener(v -> pickAudio());
        Button run = Ui.button(this, getString(R.string.btn_convert), 0);
        run.setOnClickListener(v -> {
            if (currentFile == null) { toast(getString(R.string.pick_file_first)); return; }
            startJob(Uri.fromFile(currentFile), currentFile.getName());
        });
        Ui.addButtonRow(this, file, pick, run);
        body.addView(file, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout quickCard=Ui.card(this);
        quickRecordButton=Ui.button(this,getString(R.string.ui_quick_record),1);
        quickActionButton=Ui.button(this,getString(R.string.ui_play_current),1);
        quickRecordButton.setOnClickListener(v->{stopQuickPlayer();if(QuickVoiceService.recording)QuickVoiceService.stopAndConvert(this);else{String error=QuickVoiceService.startRecording(this);if(error!=null)toast(error);}refreshQuickControls();});
        quickActionButton.setOnClickListener(v->{
            if(JobService.quickPending(QuickVoiceService.lastToken)){JobService.cancelQuick(QuickVoiceService.lastToken);refreshQuickControls();return;}
            if(quickPlayer!=null){if(quickPlayer.isPlaying())quickPlayer.pause();else quickPlayer.start();refreshQuickControls();return;}
            if(JobService.quickToken!=QuickVoiceService.lastToken||!JobService.quickDone||!JobService.quickOk)return;
            try{android.media.MediaPlayer player=new android.media.MediaPlayer();quickPlayer=player;player.setDataSource(JobService.quickOutPath);player.setOnCompletionListener(x->{stopQuickPlayer();refreshQuickControls();});player.setOnErrorListener((x,w,e)->{stopQuickPlayer();refreshQuickControls();return true;});player.prepare();player.start();}catch(Exception e){stopQuickPlayer();toast(getString(R.string.ui_play_failed)+e.getMessage());}refreshQuickControls();
        });
        Ui.addButtonRow(this,quickCard,quickRecordButton,quickActionButton);body.addView(quickCard,Ui.lp());
        refreshQuickControls();
        LinearLayout progressCard=Ui.card(this);
        audioProgressBar=new DotSeekBar(this);audioProgressBar.setMax(1000);audioProgressBar.setReadOnly(true);audioProgressBar.setThumb(null);audioProgressBar.setEnvelopeMode(OutputEnvelope.FILE);
        progressCard.addView(audioProgressBar,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        body.addView(progressCard,Ui.lp());Ui.sectionGap(this,body);
        LinearLayout batch = Ui.card(this);
        Ui.cardTop(this, batch, R.string.sec_batch);
        batchSummary = Ui.text(this, getString(R.string.batch_none), 14f, Ui.text, false);
        batch.addView(batchSummary);
        batchList = Ui.text(this, "", 12f, Ui.textDim, false);
        batchList.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        batch.addView(batchList, blp);

        Button dir = Ui.button(this, getString(R.string.btn_pick_dir), 1);
        dir.setOnClickListener(v -> pickBatchFolder());
        batchStartBtn = Ui.button(this, getString(R.string.btn_batch_start), 0);
        batchStartBtn.setEnabled(false);
        batchStartBtn.setAlpha(0.45f);
        batchStartBtn.setOnClickListener(v -> startBatch());
        Ui.addButtonRow(this, batch, dir, batchStartBtn);
        body.addView(batch, Ui.lp());
        return sc;
    }

    private void pickRoleDialog() {
        if (roles.isEmpty()) { toast(getString(R.string.roles_empty)); return; }
        String[] names = new String[roles.size()];
        int checked = 0;
        for (int i = 0; i < roles.size(); i++) {
            names[i] = roles.get(i).getName();
            if (roles.get(i).getAbsolutePath().equals(prefs.roleDir())) checked = i;
        }
        Ui.dialogList(this, getString(R.string.lbl_role), names, checked, (d, w) -> {
            if (w < 0 || w >= roles.size()) return;
            File f = roles.get(w);
            if (Busy.holder() != null) { toast(getString(R.string.msg_busy_switch, busyLabel())); return; }
            if (f.getAbsolutePath().equals(prefs.roleDir()) && modelReady) return;
            reloadModels(null, f.getAbsolutePath());
            refreshRolesList();
        });
    }

    // ================= 页 1：实时 =================

    private View buildLivePage() {
        ScrollView sc = pageScroll();
        LinearLayout body = pageBody(sc);
        header(body, R.string.title_live, R.string.sub_live);

        liveStatusCard = new Ui.StatusCard(this);
        liveState = liveStatusCard.title;
        liveSub = liveStatusCard.subtitle;
        body.addView(liveStatusCard, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout cfg = Ui.card(this);
        Ui.cardTop(this, cfg, R.string.sec_realtime);
        rtChunkValue = Ui.value(this, "");
        cfg.addView(Ui.pickerRow(this, getString(R.string.lbl_chunk), null, rtChunkValue,
                v -> showChunkDialog(false)));
        cfg.addView(Ui.divider(this));
        LinearLayout bufRow = Ui.box(this, LinearLayout.HORIZONTAL);
        bufRow.setGravity(Gravity.CENTER_VERTICAL);
        bufRow.setPadding(0, dp(6), 0, dp(6));
        bufRow.addView(Ui.text(this, getString(R.string.ui_crossfade), 14.5f, Ui.text, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        eRtBuf = Ui.field(this, "");
        eRtBuf.setEms(4);
        eRtBuf.setGravity(Gravity.CENTER);
        bufRow.addView(eRtBuf, new LinearLayout.LayoutParams(dp(88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cfg.addView(bufRow);
        android.widget.Switch liveDenoise=Ui.switchView(this,prefs.liveDenoise());
        liveDenoise.setOnCheckedChangeListener((b,on)->prefs.setDenoise(true,on));
        cfg.addView(Ui.row(this,getString(R.string.ui_denoise),null,liveDenoise));
        cfg.addView(Ui.hint(this,getString(R.string.ui_denoise_live_hint)));
        android.widget.Switch liveVolumeLimit=Ui.switchView(this,prefs.liveVolumeLimit());
        liveVolumeLimit.setOnCheckedChangeListener((b,on)->prefs.setVolumeLimit(true,on));
        cfg.addView(Ui.row(this,getString(R.string.ui_volume_limit),null,liveVolumeLimit));
        cfg.addView(Ui.hint(this,getString(R.string.ui_volume_limit_live_hint)));
        TextView pitchText=Ui.text(this,getString(R.string.ui_pitch)+prefs.livePitchSemitones()+getString(R.string.ui_semitones),14.5f,Ui.text,false);
        cfg.addView(pitchText);SeekBar livePitch=new SeekBar(this);livePitch.setMax(48);livePitch.setProgress(prefs.livePitchSemitones()+24);
        livePitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean user){if(user)prefs.set("live_pitch_semitones",v-24);pitchText.setText(getString(R.string.ui_pitch)+(v-24)+getString(R.string.ui_semitones));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});cfg.addView(livePitch);
        android.widget.Switch monitor=Ui.switchView(this,prefs.monitorEnabled());monitor.setOnCheckedChangeListener((b,on)->{prefs.setMonitorEnabled(on);if(!on)RealtimeService.stopMonitor();});cfg.addView(Ui.row(this,getString(R.string.ui_monitor),null,monitor));
        cfg.addView(Ui.hint(this,getString(R.string.ui_monitor_hint)));
        body.addView(cfg, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout sw = Ui.card(this);
        swInject = Ui.switchView(this, prefs.inject());
        swInject.setOnCheckedChangeListener((v, c) -> prefs.setInject(c));
        sw.addView(Ui.row(this, getString(R.string.sw_inject), null, swInject));
        sw.addView(Ui.hint(this, getString(R.string.hint_inject)));
        sw.addView(Ui.divider(this));
        swBypass = Ui.switchView(this, prefs.bypass());
        swBypass.setOnCheckedChangeListener((v, c) -> prefs.setBypass(c));
        sw.addView(Ui.row(this, getString(R.string.sw_bypass), null, swBypass));
        sw.addView(Ui.divider(this));
        tvEcho = Ui.text(this, getString(R.string.lbl_echo, prefs.echoLevel()), 13.5f, Ui.text, false);
        sw.addView(tvEcho);
        sbEcho = new DotSeekBar(this);
        ((DotSeekBar)sbEcho).setEnvelopeMode(2); // Same real-PCM FILE/LIVE auto spectrum as other general sliders.
        sbEcho.setMax(100);
        sbEcho.setProgress(prefs.echoLevel());
        sbEcho.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent));
        sbEcho.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.accent));
        sbEcho.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                tvEcho.setText(getString(R.string.lbl_echo, v));
                if (fromUser) {
                    prefs.setPushGain(v);
                    prefs.setEchoLevel(v);
                    Vmic.setEcho(v);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        sw.addView(sbEcho);
        body.addView(sw, Ui.lp());

        Ui.sectionGap(this, body);

        liveBtn = Ui.button(this, getString(R.string.btn_start_live), 0);
        liveBtn.setOnClickListener(v -> toggleRealtime());
        body.addView(liveBtn, Ui.lp());

        return sc;
    }

    // ================= 页 2：角色（含 NPU 开关 / 编译） =================

    private View buildRolesPage() {
        ScrollView sc = pageScroll();
        LinearLayout body = pageBody(sc);
        header(body, R.string.title_roles, R.string.sub_roles);
        LinearLayout floatingCard=Ui.card(this);
        floatingSwitch=Ui.switchView(this,FloatingMenuService.active);
        floatingSwitch.setOnCheckedChangeListener((button,enabled)->{
            if(enabled){
                saveSettings();
                if(FloatingMenuService.canDraw(this)) {
                    if(!FloatingMenuService.start(this))button.setChecked(false);
                } else {
                    pendingFloatingEnable=true;
                    startActivity(FloatingMenuService.permissionIntent(this));
                }
            } else {pendingFloatingEnable=false;FloatingMenuService.stop(this);}
        });
        floatingCard.addView(Ui.row(this,getString(R.string.ui_overlay),null,floatingSwitch));
        body.addView(floatingCard,Ui.lp());
        Ui.sectionGap(this,body);




        LinearLayout acc = Ui.card(this);
        Ui.cardTop(this, acc, R.string.sec_device);
        swNpu = Ui.switchView(this, "npu".equals(prefs.device()));
        swNpu.setOnCheckedChangeListener((v, checked) -> {
            String want = checked ? "npu" : "cpu";
            if (!want.equals(prefs.device())) reloadModels(want, null);
        });
        acc.addView(Ui.row(this, getString(R.string.switch_npu), null, swNpu));
        acc.addView(Ui.divider(this));
        setThreads = Ui.value(this, getString(R.string.threads_value, prefs.cpuThreads()));
        acc.addView(Ui.row(this, getString(R.string.lbl_threads), null, setThreads));
        threadSeek = new DotSeekBar(this);
        ((DotSeekBar)threadSeek).setEnvelopeMode(2);
        threadSeek.setMax(Tune.THREADS_MAX - Tune.THREADS_MIN);
        threadSeek.setProgress(prefs.cpuThreads() - Tune.THREADS_MIN);
        acc.addView(threadSeek, Ui.lp());
        acc.addView(Ui.hint(this, getString(R.string.threads_recommend)));
        threadSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if(user) setThreads.setText(getString(R.string.threads_value, value + Tune.THREADS_MIN));
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {
                int value = bar.getProgress() + Tune.THREADS_MIN;
                if(Busy.holder()!=null) { toast(getString(R.string.msg_busy,busyLabel())); bar.setProgress(prefs.cpuThreads()-Tune.THREADS_MIN); refreshAll(); return; }
                if(value != prefs.cpuThreads()) {
                    prefs.setCpuThreads(value); threadsShown=value;
                    if(modelReady) reloadModels(prefs.device(),null);
                    else { residentState=getString(R.string.status_config_changed); refreshAll(); }
                }
            }
        });
        body.addView(acc, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout card = Ui.card(this);
        Ui.cardTop(this, card, R.string.role_current);
        rolesBox = Ui.box(this, LinearLayout.VERTICAL);
        card.addView(rolesBox);
        rolesEmpty = Ui.text(this, getString(R.string.roles_empty), 13f, Ui.textFaint, false);
        card.addView(rolesEmpty);
        body.addView(card, Ui.lp());

        Button imp = Ui.button(this, getString(R.string.btn_import_role), 1);
        imp.setOnClickListener(v -> pickRoleFile());
        Button del = Ui.button(this, getString(R.string.btn_del_role), 3);
        del.setOnClickListener(v -> confirmDeleteRole());
        Ui.addButtonRow(this, body, imp, del);
        TextView rh = Ui.hint(this, getString(R.string.role_hint));
        LinearLayout.LayoutParams rhlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rhlp.topMargin = dp(12);
        body.addView(rh, rhlp);

        Ui.sectionGap(this, body);
        LinearLayout discover = Ui.card(this);
        Ui.cardTop(this, discover, R.string.get_roles);
        discover.addView(Ui.hint(this, getString(R.string.get_roles_warning)));
        discover.addView(communityLinkRow(R.string.get_roles_modelscope,
                "modelscope.cn · so-vits-svc", true,
                v -> openCommunityLink("https://modelscope.cn/models?name=so-vits-svc", false)));
        discover.addView(Ui.divider(this));
        discover.addView(communityLinkRow(R.string.get_roles_hf,
                "huggingface.co · so-vits-svc", true,
                v -> openCommunityLink("https://huggingface.co/models?search=so-vits-svc", false)));
        discover.addView(Ui.divider(this));
        discover.addView(communityLinkRow(R.string.get_roles_mirror,
                "hf-mirror.com · so-vits-svc", true,
                v -> openCommunityLink("https://hf-mirror.com/models?search=so-vits-svc", false)));
        body.addView(discover, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout npuCard = Ui.card(this);
        Ui.cardTop(this, npuCard, R.string.sec_npu);
        npuCard.addView(Ui.hint(this, getString(R.string.npu_hint)));
        npuRoleValue = Ui.value(this, "");
        npuCard.addView(Ui.pickerRow(this, getString(R.string.npu_role), null, npuRoleValue,
                v -> pickNpuRole()));
        LinearLayout msRow = Ui.box(this, LinearLayout.HORIZONTAL);
        msRow.setGravity(Gravity.CENTER_VERTICAL);
        msRow.setPadding(0, dp(4), 0, dp(4));
        msRow.addView(Ui.text(this, getString(R.string.npu_ms), 14.5f, Ui.text, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        eNpuMs = Ui.field(this, "");
        eNpuMs.setEms(4);
        eNpuMs.setText(String.valueOf(Prefs.framesToMs(300)));
        eNpuMs.setGravity(Gravity.CENTER);
        msRow.addView(eNpuMs, new LinearLayout.LayoutParams(dp(90),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        npuCard.addView(msRow);

        Button compile = Ui.button(this, getString(R.string.npu_compile), 1);
        compile.setOnClickListener(v -> startNpuCompile());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        npuCard.addView(compile, clp);

        TextView tiersLabel = Ui.label(this, getString(R.string.npu_compiled));
        LinearLayout.LayoutParams tllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tllp.topMargin = dp(16);
        npuCard.addView(tiersLabel, tllp);
        npuListBox = Ui.box(this, LinearLayout.VERTICAL);
        npuCard.addView(npuListBox);
        npuStatus = Ui.hint(this, "");
        LinearLayout.LayoutParams nslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nslp.topMargin = dp(6);
        npuCard.addView(npuStatus, nslp);
        Button delTier = Ui.button(this, getString(R.string.npu_delete_sel), 1);
        delTier.setOnClickListener(v -> confirmDeleteTiers());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(10);
        npuCard.addView(delTier, dlp);
        body.addView(npuCard, Ui.lp());        Ui.sectionGap(this, body);
        LinearLayout weather = Ui.card(this);
        weather.addView(Ui.text(this, getString(R.string.rain_title), 16f, Ui.text, true));
        int intensity = readRainIntensity();
        TextView rainPercent = Ui.value(this, intensity + "%");
        weather.addView(rainPercent);
        SeekBar rainSeek = new DotSeekBar(this);
        ((DotSeekBar)rainSeek).setEnvelopeMode(2);
        rainSeek.setMax(100);
        rainSeek.setProgress(intensity);
        rainSeek.setContentDescription(getString(R.string.rain_title));
        rainSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                getSharedPreferences("svc", MODE_PRIVATE).edit().putInt("rain_intensity", value).apply();
                rainPercent.setText(value + "%");
                if (rain != null) rain.setRainIntensity(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        weather.addView(rainSeek, new LinearLayout.LayoutParams(-1, -2));
        Switch tiltSwitch = Ui.switchView(this, getSharedPreferences("svc", MODE_PRIVATE).getBoolean("rain_tilt", true));
        tiltSwitch.setText(R.string.rain_tilt_title);
        tiltSwitch.setTextColor(Ui.text);
        weather.addView(tiltSwitch, new LinearLayout.LayoutParams(-1, -2));
        tiltSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("svc", MODE_PRIVATE).edit().putBoolean("rain_tilt", checked).apply();
            if (rain != null) rain.setTiltEnabled(checked);
        });
        body.addView(weather, Ui.lp());
        Ui.sectionGap(this, body);

        LinearLayout glass = Ui.card(this);
        Switch glassSwitch = Ui.switchView(this, GlassShader.isEnabled());
        glassSwitch.setText(R.string.liquid_glass_title);
        glassSwitch.setTextColor(Ui.text);
        glass.addView(glassSwitch, new LinearLayout.LayoutParams(-1, -2));
        glass.addView(Ui.hint(this, getString(R.string.liquid_glass_summary)));
        glassSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("svc", MODE_PRIVATE).edit()
                    .putBoolean("liquid_glass_enabled", checked).apply();
            GlassShader.setEnabled(checked);
            if (nav != null) nav.refresh();
        });
        body.addView(glass, Ui.lp());

        Ui.sectionGap(this,body);
        LinearLayout debugCard=Ui.card(this);
        Switch debugSwitch=Ui.switchView(this,prefs.debugMode());
        debugSwitch.setText(R.string.keep_logs_title);debugSwitch.setTextColor(Ui.text);
        debugCard.addView(debugSwitch,new LinearLayout.LayoutParams(-1,-2));
        debugSwitch.setOnCheckedChangeListener((button,checked)->prefs.setDebugMode(checked));
        body.addView(debugCard,Ui.lp());Ui.sectionGap(this,body);
        addHttpSettings(body);
        return sc;
    }

    // ================= 页 3：日志 =================

    private View buildLogPage() {
        LinearLayout box = Ui.box(this, LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(112));
        box.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        header(box, R.string.title_logs, R.string.sub_logs);

        LinearLayout st = Ui.card(this);
        logState = Ui.text(this, residentState, 14f, Ui.text, true);
        st.addView(logState);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(16);
        LinearLayout card = Ui.card(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setClipToPadding(false);
        logView = new TextView(this);
        logView.setTextSize(10.5f);
        logView.setTextColor(Ui.textDim);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setText(getString(R.string.log_empty));
        logScroll.addView(logView);
        card.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        box.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button stop = Ui.button(this, getString(R.string.btn_stop_task), 1);
        stop.setOnClickListener(v -> stopTasks());
        Button clear = Ui.button(this, getString(R.string.btn_clear_log), 2);
        clear.setOnClickListener(v -> clearLog());
        Ui.addButtonRow(this, box, stop, clear);
        return box;
    }

    // ================= 页 4：设置（LSPosed 风格） =================

    private int readRainIntensity() {
        android.content.SharedPreferences settings = getSharedPreferences("svc", MODE_PRIVATE);
        int intensity;
        if (settings.contains("rain_intensity")) {
            intensity = Math.max(0, Math.min(100, settings.getInt("rain_intensity", 81)));
        } else {
            intensity = settings.contains("rain_enabled") && !settings.getBoolean("rain_enabled", true) ? 0 : 81;
        }
        settings.edit().putInt("rain_intensity", intensity).apply();
        return intensity;
    }

    private Button quickRecordButton,quickActionButton;
    private android.media.MediaPlayer quickPlayer;
    private void stopQuickPlayer(){android.media.MediaPlayer p=quickPlayer;quickPlayer=null;if(p!=null){try{p.release();}catch(Exception ignored){}}}
    private void refreshQuickControls(){
        if(quickRecordButton==null)return;
        boolean recording=QuickVoiceService.recording,pending=JobService.quickPending(QuickVoiceService.lastToken);
        quickRecordButton.setText(recording?getString(R.string.ui_record_finish):getString(R.string.ui_quick_record));
        quickRecordButton.setEnabled(recording||(!pending&&!JobService.running));
        boolean output=QuickVoiceService.lastToken>0&&JobService.quickToken==QuickVoiceService.lastToken&&JobService.quickDone&&JobService.quickOk;
        quickActionButton.setText(pending?getString(R.string.ui_convert_stop):quickPlayer!=null?(quickPlayer.isPlaying()?getString(R.string.ui_pause):getString(R.string.ui_resume)):getString(R.string.ui_play_current));
        quickActionButton.setTextColor(pending?0xffff5555:Ui.text);
        quickActionButton.setEnabled(pending||output);
    }
    private boolean pendingFloatingEnable;
    private android.widget.Switch floatingSwitch;

    private View buildSettingsPage() {
        ScrollView sc = pageScroll();
        LinearLayout body = pageBody(sc);
        header(body, R.string.title_set, R.string.sub_set);
        rootStatusCard = new Ui.StatusCard(this);
        rootHero = rootStatusCard;
        rootHeroState = rootStatusCard.title;
        rootHeroSub = rootStatusCard.subtitle;
        rootHeroMark = rootStatusCard.icon;
        rootStatusCard.setRootDecoration();
        rootHeroState.setText(getString(rootState==1 ? R.string.root_granted : R.string.root_denied));
        rootHero.setOnClickListener(v -> checkRoot());
        styleRootHero();
        body.addView(rootHero, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout info = Ui.card(this);
        setVersion = addInfoRow(info, getString(R.string.lbl_version), versionName(), false);
        info.addView(Ui.divider(this));
        addInfoRow(info, getString(R.string.current_device), android.os.Build.MODEL, false);
        info.addView(Ui.divider(this));
        setOutput = Ui.value(this, "");
        info.addView(Ui.pickerRow(this, getString(R.string.lbl_output), null, setOutput,
                v -> showOutDialog()));
        body.addView(info, Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout spectrumCard=Ui.card(this);
        homeSpectrum=new DotSeekBar(this);homeSpectrum.setMax(1000);homeSpectrum.setReadOnly(true);homeSpectrum.setThumb(null);homeSpectrum.setEnvelopeMode(2);
        spectrumCard.addView(homeSpectrum,new LinearLayout.LayoutParams(-1,dp(48)));
        body.addView(spectrumCard,Ui.lp());
        Ui.sectionGap(this,body);
        setLang = Ui.value(this, "");
        body.addView(Ui.linkCard(this, getString(R.string.sec_lang), setLang,
                v -> pickLanguage()), Ui.lp());

        Ui.sectionGap(this, body);

        LinearLayout about = Ui.card(this);
        Ui.cardTop(this, about, R.string.sec_about);
        LinearLayout.LayoutParams a1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        a1lp.topMargin = dp(8);
        TextView projectText = Ui.hint(this, getString(R.string.about_project));
        android.text.SpannableString projectLinks=new android.text.SpannableString(projectText.getText());
        String sourceName="ultimate-rvc-mobile";
        int sourceStart=projectLinks.toString().indexOf(sourceName);
        if(sourceStart>=0)projectLinks.setSpan(new android.text.style.URLSpan("https://github.com/Sharl210/ultimate-rvc-mobile"),sourceStart,sourceStart+sourceName.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        projectText.setText(projectLinks);
        projectText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        about.addView(projectText, a1lp);
        TextView licenses = Ui.text(this, getString(R.string.app_mit_license), 13f, Ui.accent, false);
        licenses.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://opensource.org/license/mit"))));
        about.addView(licenses, a1lp);
        body.addView(about, Ui.lp());
        addCommunityLinks(body);
        Ui.sectionGap(this,body);



        return sc;
    }

    private void addHttpSettings(LinearLayout body){
        LinearLayout card=Ui.card(this);
        TextView title=Ui.text(this,getString(R.string.http_title),18f,Ui.text,true);card.addView(title);
        android.widget.Switch enabled=new android.widget.Switch(this);enabled.setText(getString(R.string.http_enabled));enabled.setChecked(prefs.httpEnabled());card.addView(enabled);
        card.addView(Ui.label(this,getString(R.string.http_port_label)));
        EditText port=new EditText(this);port.setSingleLine(true);port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);port.setHint(getString(R.string.http_port_hint));port.setText(String.valueOf(prefs.httpPort()));card.addView(port);
        android.widget.Switch local=new android.widget.Switch(this);local.setText(getString(R.string.http_local));local.setChecked(prefs.httpLocalOnly());card.addView(local);
        card.addView(Ui.hint(this,getString(R.string.http_warning)));
        card.addView(Ui.label(this,getString(R.string.http_token_label)));
        EditText token=new EditText(this);token.setSingleLine(true);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSaveEnabled(false);token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        token.setHint(getString(R.string.http_token_hint));token.setText(prefs.httpToken());card.addView(token);
        android.widget.CheckBox reveal=new android.widget.CheckBox(this);reveal.setText(getString(R.string.http_token_show));
        reveal.setOnCheckedChangeListener((v,checked)->{token.setTransformationMethod(checked?null:android.text.method.PasswordTransformationMethod.getInstance());token.setSelection(token.length());});card.addView(reveal);
        Button generate=Ui.button(this,getString(R.string.http_token_generate),1);card.addView(generate);
        generate.setOnClickListener(v->{byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);StringBuilder key=new StringBuilder(64);for(byte item:bytes)key.append(String.format(java.util.Locale.ROOT,"%02x",item&255));token.setText(key.toString());});
        card.addView(Ui.hint(this,getString(R.string.http_token_help)));
        card.addView(Ui.label(this,getString(R.string.http_state)));
        TextView state=Ui.hint(this,LocalHttpService.status(this));card.addView(state);
        LinearLayout.LayoutParams actionLp=new LinearLayout.LayoutParams(-1,-2);actionLp.topMargin=dp(12);
        Button save=Ui.button(this,getString(R.string.http_apply),1);card.addView(save,actionLp);
        save.setOnClickListener(v->{
            final int value;
            try{value=Integer.parseInt(port.getText().toString().trim());if(value<1||value>65535)throw new NumberFormatException();}
            catch(Exception e){port.setError(getString(R.string.http_invalid_port));return;}
            String key=token.getText().toString();
            if(!LocalHttpWire.validateToken(key)){token.setError(getString(R.string.http_token_invalid));return;}
            prefs.setHttpToken(key);
            prefs.setHttp(enabled.isChecked(),value,local.isChecked());
            try{LocalHttpService.apply(this);state.setText(getString(R.string.http_saved));state.postDelayed(()->state.setText(LocalHttpService.status(this)),800);}
            catch(Exception e){state.setText(getString(R.string.http_start_failed)+e.getMessage());prefs.setHttp(false,value,local.isChecked());enabled.setChecked(false);}
        });
        card.addView(Ui.hint(this,getString(R.string.http_upload_help)));
        Button docs=Ui.button(this,getString(R.string.http_docs),1);LinearLayout.LayoutParams docsLp=new LinearLayout.LayoutParams(-1,-2);docsLp.topMargin=dp(12);card.addView(docs,docsLp);
        docs.setOnClickListener(v->{
            try{
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                try(java.io.InputStream in=getAssets().open("local-http-api.txt")){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                final String text=out.toString("UTF-8");
                TextView view=Ui.text(this,text,13f,Ui.text,false);view.setTextIsSelectable(true);view.setPadding(dp(16),dp(8),dp(16),dp(8));
                ScrollView scroll=new ScrollView(this);scroll.addView(view);
                new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.http_docs_title)).setView(scroll).setPositiveButton(getString(R.string.http_ok),null)
                    .setNeutralButton(getString(R.string.http_copy),(d,w)->{android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.http_docs_title),text));toast(getString(R.string.http_copied));}).show();
            }catch(Exception e){toast(getString(R.string.http_read_failed)+e.getMessage());}
        });
        body.addView(card,Ui.lp());
    }

    private void showThirdPartyLicenses(String path) {
        try {
            String[] entries = getAssets().list(path);
            if (entries != null && entries.length > 0) {
                new AlertDialog.Builder(this).setTitle(getString(R.string.third_party_licenses))
                        .setItems(entries, (dialog, which) -> showThirdPartyLicenses(path + "/" + entries[which]))
                        .setNegativeButton(android.R.string.cancel, null).show();
                return;
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = getAssets().open(path)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            TextView text = Ui.text(this, new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 12f, Ui.text, false);
            text.setPadding(dp(16), dp(12), dp(16), dp(12));
            text.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(text);
            new AlertDialog.Builder(this).setTitle(path.substring(path.lastIndexOf('/') + 1))
                    .setView(scroll).setPositiveButton(android.R.string.ok, null).show();
        } catch (java.io.IOException e) {
            android.widget.Toast.makeText(this, e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // External community links; keep GitHub disabled until a real URL is supplied.
    static final String GITHUB_URL = "https://github.com/eunley/Hybrid-VC-for-Android";
    static final String QQ_GROUP_NUMBER = "1128560691";
    static final String QQ_GROUP_URL = "mqqapi://card/show_pslcard?card_type=group&uin=";

    static Intent communityViewIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private View communityLinkRow(int titleId, String subtitle, boolean enabled,
                                  View.OnClickListener listener) {
        LinearLayout row = Ui.box(this, LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setMinimumHeight(dp(64));
        LinearLayout texts = Ui.box(this, LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, getString(titleId), 15.5f, Ui.text, true));
        TextView detail = Ui.text(this, subtitle, 12.5f, Ui.textDim, false);
        detail.setSingleLine(false);
        texts.addView(detail);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = Ui.text(this, "↗", 20f, Ui.textDim, false);
        arrow.setPadding(dp(12), 0, 0, 0);
        row.addView(arrow);
        row.setEnabled(enabled);
        row.setFocusable(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
        if (enabled) row.setOnClickListener(listener);
        return row;
    }

    private void addCommunityLinks(LinearLayout body) {
        Ui.sectionGap(this, body);
        LinearLayout links = Ui.card(this);
        boolean githubSet = !GITHUB_URL.trim().isEmpty();
        links.addView(communityLinkRow(R.string.links_github,
                githubSet ? GITHUB_URL : getString(R.string.links_not_set), githubSet,
                v -> openCommunityLink(GITHUB_URL, false)));
        links.addView(Ui.divider(this));
        links.addView(communityLinkRow(R.string.links_qq, QQ_GROUP_NUMBER, true,
                v -> openCommunityLink(QQ_GROUP_URL + QQ_GROUP_NUMBER, true)));
        body.addView(links, Ui.lp());
    }

    private void openCommunityLink(String url, boolean qq) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(communityViewIntent(url));
        } catch (android.content.ActivityNotFoundException e) {
            if (qq) showQqGroupFallback();
            else toast(getString(R.string.links_open_failed));
        } catch (SecurityException e) {
            if (qq) showQqGroupFallback();
            else toast(getString(R.string.links_open_failed));
        }
    }

    private void showQqGroupFallback() {
        DialogSurface.builder(this)
                .setTitle(R.string.links_qq)
                .setMessage(getString(R.string.links_qq_missing, QQ_GROUP_NUMBER))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.links_copy_group, (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                getString(R.string.links_qq), QQ_GROUP_NUMBER));
                        toast(getString(R.string.links_group_copied));
                    }
                }).show();
    }

    private void styleRootHero() {
        if (rootHero == null) return;
        rootStatusCard.setState(rootState == 1 ? Ui.StatusCard.RUNNING : Ui.StatusCard.ERROR);
        rootHeroSub.setVisibility(View.GONE);

    }

    private void showOutDialog() {
        final EditText e = Ui.field(this, getString(R.string.out_hint,
                new File(prefs.baseDir(), "out").getAbsolutePath()));
        e.setText(prefs.outPath());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        DialogSurface.builder(this)
                .setTitle(getString(R.string.lbl_output))
                .setView(e)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> applyOutPathStr(e.getText().toString()))
                .show();
    }

    private void applyOutPathStr(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            prefs.setOutPath("");
            File def = new File(prefs.baseDir(), "out");
            if (!def.isDirectory() && !def.mkdirs()) {
                toast(getString(R.string.out_create_failed, def.getAbsolutePath()));
                return;
            }
            refreshAll();
            toast(getString(R.string.out_default));
            return;
        }
        File d = new File(t);
        if (!d.isDirectory() && !d.mkdirs()) {
            toast(getString(R.string.out_create_failed, t));
            return;
        }
        if (!d.canWrite()) { toast(getString(R.string.out_not_writable, t)); return; }
        prefs.setOutPath(d.getAbsolutePath());
        refreshAll();
        toast(getString(R.string.out_set, d.getAbsolutePath()));
    }

    private TextView addInfoRow(LinearLayout card, String label, String value, boolean divider) {
        if (divider && card.getChildCount() > 0) card.addView(Ui.divider(this));
        LinearLayout col = Ui.box(this, LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(8));
        col.addView(Ui.text(this, label, 15.5f, Ui.text, true));
        TextView v = Ui.text(this, value, 12.5f, Ui.textDim, false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(4);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(v, vlp);
        card.addView(col, Ui.lp());
        return v;
    }

    private void pickThreads() {
        final int[] vals = new int[Tune.THREADS_MAX - Tune.THREADS_MIN + 1];
        String[] labels = new String[vals.length];
        for (int i = 0; i < vals.length; i++) {
            vals[i] = Tune.THREADS_MIN + i;
            labels[i] = getString(R.string.threads_value, vals[i]);
        }
        int checked = Math.max(0, prefs.cpuThreads() - Tune.THREADS_MIN);
        Ui.dialogList(this, getString(R.string.lbl_threads), labels, checked, (d, w) -> {
            if (Busy.holder() != null) { toast(getString(R.string.msg_busy, busyLabel())); return; }
            prefs.setCpuThreads(vals[w]);
            threadsShown = vals[w];
            residentState = getString(R.string.status_config_changed);
            refreshAll();
        });
    }

    private void stepThreads(int delta) {
        int n = Math.max(Tune.THREADS_MIN, Math.min(Tune.THREADS_MAX, prefs.cpuThreads() + delta));
        if (n == prefs.cpuThreads()) return;
        if (Busy.holder() != null) { toast(getString(R.string.msg_busy, busyLabel())); return; }
        prefs.setCpuThreads(n);
        threadsShown = n;
        residentState = getString(R.string.status_config_changed);
        refreshAll();
    }

    // ================= 日志 / 状态刷新 =================

    private void refreshLogScroll() {
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void stopTasks() {
        if (!JobService.running && !RealtimeService.running) {
            toast(getString(R.string.no_task));
            return;
        }
        JobService.requestCancel();
        RealtimeService.requestStop();
        stopService(new Intent(this, RealtimeService.class));
        stopService(new Intent(this, JobService.class));
        toast(getString(R.string.task_stopped));
        log(getString(R.string.task_stopped));
    }

    private void beginLogSession() {
        if(prefs.debugMode() || JobService.running || RealtimeService.running || (Busy.holder()!=null&&!"model-load".equals(Busy.holder())))return;
        try { AppLog.clear(prefs.baseDir());if(logView!=null)logView.setText(""); }
        catch(Exception e){android.util.Log.e("SVC","log clear failed",e);}
    }
    private void clearLog() {
        try{AppLog.clear(prefs.baseDir());if(logView!=null)logView.setText("");toast(getString(R.string.log_cleared));}
        catch(Exception e){toast(String.valueOf(e.getMessage()));}
    }
    private void loadLogTail() {
        new Thread(() -> {
            try {
                final long generation, revision;final String text;
                synchronized(AppLog.class){generation=AppLog.generation;revision=AppLog.revision;text=AppLog.tail(prefs.baseDir());}
                runOnUiThread(() -> {
                    synchronized(AppLog.class){
                        if(generation!=AppLog.generation||revision!=AppLog.revision||logView==null)return;
                        logView.setText(text);refreshLogScroll();
                    }
                });
            }catch(Exception e){android.util.Log.e("SVC","log read failed",e);}
        },"log-tail").start();
    }

    private void appendLine(String s) {
        if (logView == null) return;
        if (logView.getText().toString().equals(getString(R.string.log_empty))) logView.setText("");
        logView.append(s + "\n");
        if(logView.length()>131072)logView.setText(logView.getText().subSequence(logView.length()-65536,logView.length()));
        if (logScroll != null && tabIndex == TAB_LOGS) refreshLogScroll();
    }

    private void log(String s) {
        AppLog.i(prefs.baseDir(), s);
    }

    private void refreshAll() {
        refreshStatus();
        refreshChunkValues();
        refreshBatchPanel();
        refreshRolesList();
        refreshNpuPanel();
        if (setVersion != null) setVersion.setText(versionName());
        if (setOutput != null) setOutput.setText(prefs.outDir());
        if (setThreads != null) setThreads.setText(getString(R.string.threads_value, prefs.cpuThreads()));
        if (setLang != null) setLang.setText(langLabel());
        if (rolePickValue != null) {
            String r = prefs.roleDir();
            rolePickValue.setText(r.isEmpty() ? getString(R.string.no_role) : new File(r).getName());
        }
        if (eRtBuf != null && eRtBuf.getText().length() == 0)
            eRtBuf.setText(String.valueOf(prefs.rtBufferMs()));
        if (eFileBuf != null && eFileBuf.getText().length() == 0)
            eFileBuf.setText(String.valueOf(Prefs.framesToMs(prefs.fileXfade())));

    }

    private String langLabel() {
        return I18n.languageLabel(this);
    }

    private void refreshStatus() {
        refreshQuickControls();
        if(audioProgressBar!=null){AudioJobProgress p=AudioJobProgress.current;audioProgressBar.setProgress(p.unknown>0?0:p.value());if(homeSpectrum!=null)homeSpectrum.setProgress(p.unknown>0?0:p.value());}

        if (logState != null) {
            logState.setText(residentState + "\n" + getString(R.string.state_device) + " · "
                    + deviceLabel() + "   " + getString(R.string.state_out) + " · " + prefs.outDir());
        }
        if (liveStatusCard != null) {
            RealtimeService.UiState state = RealtimeService.uiState;
            int kind = state == RealtimeService.UiState.ERROR ? Ui.StatusCard.ERROR
                    : state == RealtimeService.UiState.LOADING ? Ui.StatusCard.LOADING
                    : state == RealtimeService.UiState.RUNNING ? Ui.StatusCard.RUNNING : Ui.StatusCard.IDLE;
            liveStatusCard.setState(kind);
            liveState.setText(getString(kind == Ui.StatusCard.LOADING ? R.string.status_loading
                    : kind == Ui.StatusCard.RUNNING ? R.string.live_running
                    : kind == Ui.StatusCard.ERROR ? R.string.status_failed : R.string.live_stopped));
        }
        if (liveSub != null) {
            String r = prefs.roleDir();
            String name = r.isEmpty() ? getString(R.string.no_role) : new File(r).getName();
            liveSub.setText(RealtimeService.uiState == RealtimeService.UiState.ERROR
                    ? RealtimeService.status : getString(R.string.role_current) + " · " + name);
        }
        if (liveBtn != null) {
            liveBtn.setText(RealtimeService.running
                    ? getString(R.string.btn_stop_live) : getString(R.string.btn_start_live));
        }
        if (swNpu != null) {
            boolean npu = "npu".equals(prefs.device());
            if (swNpu.isChecked() != npu) swNpu.setChecked(npu);
        }
    }

    private void refreshChunkValues() {
        if (fileChunkValue != null) fileChunkValue.setText(chunkLabel(true));
        if (rtChunkValue != null) rtChunkValue.setText(chunkLabel(false));
    }

    private String chunkLabel(boolean isFile) {
        int t = isFile ? prefs.fileFrames() : prefs.rtFrames();
        boolean npu = false;
        if ("npu".equals(prefs.device()) && !prefs.roleDir().isEmpty()) {
            npu = Engine.listCompiled(new File(prefs.roleDir())).contains(t);
        }
        return getString(npu ? R.string.chunk_npu_tier : R.string.chunk_cpu_tier, Prefs.framesToMs(t));
    }

    private String busyLabel() {
        String h = Busy.holder();
        if (h == null) return "";
        if (h.startsWith("batch")) return getString(R.string.busy_batch);
        if (h.startsWith("file")) return getString(R.string.busy_file);
        if (h.startsWith("rt")) return getString(R.string.busy_rt);
        if (h.startsWith("compile")) return getString(R.string.busy_compile);
        if (h.startsWith("model")) return getString(R.string.busy_model);
        return h;
    }

    // ================= 模型常驻 =================

    private void reloadModels(String mode, String role) {
        if (!Busy.acquire("model-load")) {
            toast(getString(R.string.msg_busy_switch, busyLabel()));
            refreshStatus();
            return;
        }
        if (mode != null) { prefs.setDevice(mode); Engine.device = mode; }
        if (role != null) prefs.setRoleDir(role);
        modelReady = false;
        residentState = getString(R.string.resident_loading, deviceLabel());
        refreshAll();
        new Thread(() -> {
            try {
                if (mode != null) Engine.release();
                Engine engine = Engine.acquire(prefs.modelsDir(), this::log);
                if (prefs.roleDir().isEmpty()) throw new Exception(getString(R.string.need_role));
                engine.loadRole(prefs.roleDir(), prefs.fileFrames());
                residentState = getString("npu".equals(prefs.device())
                        ? R.string.resident_ready_npu : R.string.resident_ready_cpu)
                        + " · " + getString(R.string.state_pid, android.os.Process.myPid());
                modelReady = true;
                log(residentState + " (resident session, no warmup)");
            } catch (Throwable e) {
                if (mode != null) Engine.release();
                modelReady = false;
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                residentState = getString(R.string.resident_failed, m);
                log(residentState);
            } finally {
                Busy.release("model-load");
                runOnUiThread(() -> { refreshAll(); refreshChunkValues(); });
            }
        }, "model-resident-load").start();
    }

    private String deviceLabel() {
        return "npu".equals(prefs.device()) ? getString(R.string.chip_npu) : getString(R.string.chip_cpu);
    }

    // ================= 实时 =================

    private void toggleRealtime() {
        if (RealtimeService.running) {
            RealtimeService.running = false;
            stopService(new Intent(this, RealtimeService.class));
            refreshStatus();
            return;
        }
        if (!modelReady) {
            toast(getString(R.string.model_ready_required, residentState));
            return;
        }
        saveSettings();
        if (prefs.roleDir().isEmpty()) { toast(getString(R.string.need_role)); return; }
        Intent i = new Intent(this, RealtimeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        refreshStatus();
        new Thread(() -> {
            for (int k = 0; k < 36000; k++) {
                sleep(700);
                runOnUiThread(this::refreshStatus);
                if (!RealtimeService.running && k > 4) break;
            }
        }).start();
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    // ================= 单文件 =================

    private void startJob(Uri uri, String name) {
        if (!modelReady) { toast(getString(R.string.model_ready_required, residentState)); return; }
        saveSettings();
        if (prefs.roleDir().isEmpty()) { toast(getString(R.string.need_role)); return; }
        if (JobService.running) { toast(getString(R.string.job_running)); return; }
        Intent svc = new Intent(this, JobService.class);
        svc.putExtra("uri", uri.toString());
        svc.putExtra("name", name);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        toast(getString(R.string.started));
        new Thread(() -> {
            for (int k = 0; k < 36000; k++) {
                sleep(700);
                if (JobService.running) {
                    final String st = JobService.status;
                    runOnUiThread(this::refreshAll);
                } else if (k > 4) {
                    runOnUiThread(this::refreshAll);
                    break;
                }
            }
        }).start();
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "*/*"});
        startActivityForResult(i, REQ_FILE);
    }

    private void pickRoleFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "*/*"});
        try {
            startActivityForResult(i, REQ_ROLE);
        } catch (Throwable e) {
            toast(getString(R.string.role_import_fail, String.valueOf(e.getMessage())));
        }
    }

    private void pickBatchFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_BATCH_TREE);
        } catch (Throwable e) {
            toast(getString(R.string.batch_open_failed));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_BATCH_TREE) onBatchTreePicked(uri);
        else if (req == REQ_ROLE) importRole(uri);
        else if (req == REQ_FILE) {
            String nm = queryName(uri);
            if (nm == null) nm = "input.wav";
            currentFile = new File(getCacheDir(), "in_" + System.currentTimeMillis() + "_" + nm);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(currentFile)) {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (Throwable e) {
                log("read file failed: " + e);
                toast(getString(R.string.role_import_read_fail, String.valueOf(e.getMessage())));
            }
            if (fileLabel != null) fileLabel.setText(getString(R.string.file_selected, nm));
        }
    }

    // ================= 批量 =================

    private void onBatchTreePicked(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignore) {}
        batchTree = uri;
        batchItems.clear();
        if (batchSummary != null) batchSummary.setText(getString(R.string.batch_scanning));
        if (batchList != null) batchList.setText("");
        setBatchStartEnabled(false);

        new Thread(() -> {
            List<BatchItem> found = new ArrayList<>();
            try {
                String docId = DocumentsContract.getTreeDocumentId(uri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId);
                ContentResolver cr = getContentResolver();
                try (Cursor c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            String id = c.getString(0);
                            String name = c.getString(1);
                            String mime = c.getString(2);
                            long size = c.isNull(3) ? 0 : c.getLong(3);
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                            if (!isAudio(mime, name)) continue;
                            found.add(new BatchItem(
                                    DocumentsContract.buildDocumentUriUsingTree(uri, id), name, size));
                        }
                    }
                }
            } catch (Throwable e) {
                log("batch scan failed: " + e);
                runOnUiThread(() -> {
                    batchSummary.setText(getString(R.string.batch_open_failed));
                    batchList.setText("");
                });
                return;
            }
            Collections.sort(found, new Comparator<BatchItem>() {
                @Override public int compare(BatchItem a, BatchItem b) {
                    return naturalCompare(a.name, b.name);
                }
            });
            final List<BatchItem> list = found;
            runOnUiThread(() -> applyBatchScan(list));
        }, "batch-scan").start();
    }

    private void applyBatchScan(List<BatchItem> list) {
        batchItems.clear();
        batchItems.addAll(list);
        if (list.isEmpty()) {
            batchSummary.setText(getString(R.string.batch_empty));
            batchList.setText("");
            setBatchStartEnabled(false);
            return;
        }
        long total = 0;
        for (BatchItem b : list) total += b.size;
        batchSummary.setText(getString(R.string.batch_found, list.size(), fmtSize(total)));
        StringBuilder sb = new StringBuilder();
        int max = Math.min(6, list.size());
        for (int i = 0; i < max; i++) {
            sb.append(i + 1).append(". ").append(list.get(i).name);
            if (list.get(i).size > 0) sb.append("  ").append(fmtSize(list.get(i).size));
            if (i < max - 1) sb.append('\n');
        }
        if (list.size() > max) sb.append('\n').append(getString(R.string.batch_more, list.size() - max));
        batchList.setText(sb.toString());
        setBatchStartEnabled(true);
    }

    private void setBatchStartEnabled(boolean on) {
        if (batchStartBtn == null) return;
        batchStartBtn.setEnabled(on);
        batchStartBtn.setAlpha(on ? 1f : 0.45f);
    }

    private void refreshBatchPanel() {
        if (batchSummary == null) return;
        if (batchItems.isEmpty() && batchTree == null) batchSummary.setText(getString(R.string.batch_none));
    }

    private static boolean isAudio(String mime, String name) {
        if (mime != null && mime.startsWith("audio/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        switch (ext) {
            case "wav": case "mp3": case "flac": case "m4a": case "aac": case "ogg":
            case "opus": case "amr": case "wma": case "aif": case "aiff": case "mka":
                return true;
            default:
                return false;
        }
    }

    private void startBatch() {
        if (batchItems.isEmpty()) {
            toast(batchTree == null ? getString(R.string.batch_none) : getString(R.string.batch_empty));
            return;
        }
        if (!modelReady) { toast(getString(R.string.model_ready_required, residentState)); return; }
        if (JobService.running) { toast(getString(R.string.job_running)); return; }
        if (Busy.holder() != null) { toast(getString(R.string.msg_busy, busyLabel())); return; }
        saveSettings();
        if (prefs.roleDir().isEmpty()) { toast(getString(R.string.need_role)); return; }

        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (BatchItem b : batchItems) {
            uris.add(b.uri.toString());
            names.add(b.name);
        }
        Intent svc = new Intent(this, JobService.class);
        svc.putStringArrayListExtra("uris", uris);
        svc.putStringArrayListExtra("names", names);
        svc.putExtra("batch", true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        toast(getString(R.string.batch_started, uris.size()));
    }

    private void importRole(Uri uri) {
        if(!Busy.acquire("role-import")){toast(getString(R.string.ui_import_busy));return;}
        final String selName = queryName(uri);
        log("=== import role " + selName + " ===");
        File tmp;
        try {
            String nm = selName == null ? "role.bin" : selName;
            tmp = new File(getCacheDir(), "imp_" + System.currentTimeMillis() + "_" + nm);
            long t0 = System.currentTimeMillis();
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("no input stream");
                byte[] buf = new byte[1 << 20];
                int n;
                long tot = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    tot += n;
                    if (tot % (64 << 20) < n) {
                        final long mb = tot >> 20;
                        runOnUiThread(() -> appendLine(getString(R.string.role_read_progress, mb)));
                    }
                }
            }
            appendLine(getString(R.string.role_read_done, (int) (tmp.length() >> 20),
                    (int) (System.currentTimeMillis() - t0)));
        } catch (Throwable e) {
            appendLine(getString(R.string.role_import_read_fail, String.valueOf(e.getMessage())));
            log("read role failed " + android.util.Log.getStackTraceString(e));
            Busy.release("role-import");
            return;
        }

        final File src = tmp;
        final String base = selName == null ? "role" : selName.replaceAll("\\.[^.]+$", "");
        toast(getString(R.string.role_importing, base));
        new Thread(() -> {
            try {
                File tplDir = new File(prefs.baseDir(), "tpl");
                File outDir = new File(prefs.rolesDir(), base);
                RoleImporter.Result r = RoleImporter.importRole(src, outDir, tplDir, this::log);
                log("role imported " + r.roleName + " tensors=" + r.tensorCount
                        + " missing=" + r.missing + " cost=" + r.costMs + "ms");
                runOnUiThread(() -> {
                    scanRoles();
                    refreshAll();
                    toast(getString(R.string.role_import_ok, r.roleName, r.tensorCount));
                });
            } catch (Throwable e) {
                log("role import failed " + android.util.Log.getStackTraceString(e));
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    appendLine(getString(R.string.role_import_fail, m));
                    toast(getString(R.string.role_import_fail, m));
                });
            } finally {
                src.delete();
                Busy.release("role-import");
            }
        }, "role-import").start();
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignore) {}
        String seg = uri.getLastPathSegment();
        if (seg == null) return null;
        int sl = seg.lastIndexOf('/');
        return sl >= 0 ? seg.substring(sl + 1) : seg;
    }

    // ================= 角色 / NPU =================

    private void scanRoles() {
        roles.clear();
        File d = new File(prefs.rolesDir());
        File[] fs = d.listFiles();
        String cur = prefs.roleDir();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) {
                if (!f.isDirectory()) continue;
                String fn = f.getName();
                if (fn.startsWith("_") || fn.startsWith(".")) continue;
                if (!new File(f, "dec.onnx").exists()) continue;
                boolean hasEnc = new File(f, "enc.onnx").exists()
                        || new File(f, "enc_dyn.onnx").exists();
                if (!hasEnc) {
                    File[] es = f.listFiles((dd, nm) -> nm.startsWith("enc_") && nm.endsWith(".onnx"));
                    if (es == null || es.length == 0) continue;
                }
                roles.add(f);
            }
        }
        if (cur.isEmpty() && !roles.isEmpty()) prefs.setRoleDir(roles.get(0).getAbsolutePath());
        else if (!cur.isEmpty()) {
            boolean exists = false;
            for (File f : roles) if (f.getAbsolutePath().equals(cur)) exists = true;
            if (!exists && !roles.isEmpty()) prefs.setRoleDir(roles.get(0).getAbsolutePath());
        }
        refreshRolesList();
        npuScanRoles();
    }

    private void refreshRolesList() {
        if (rolesBox == null) return;
        rolesBox.removeAllViews();
        if (roles.isEmpty()) {
            rolesEmpty.setVisibility(View.VISIBLE);
            return;
        }
        rolesEmpty.setVisibility(View.GONE);
        String cur = prefs.roleDir();
        for (final File f : roles) {
            boolean on = f.getAbsolutePath().equals(cur);
            LinearLayout row = Ui.box(this, LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(12), dp(8), dp(12));
            row.setBackground(Ui.ripple(this, on ? (Ui.dark?0xA0242529:Ui.card) : 0x00000000, dp(14)));
            row.addView(Ui.icon(this, Ui.IconView.USER, on ? Ui.accent : Ui.textDim, dp(20)));
            LinearLayout col = Ui.box(this, LinearLayout.VERTICAL);
            col.addView(Ui.text(this, f.getName(), 15f, Ui.text, on));
            int tiers = Engine.listCompiled(f).size();
            TextView s = Ui.text(this, tiers > 0 ? "NPU × " + tiers : "—", 11f, Ui.textFaint, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            col.addView(s, slp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            clp.leftMargin = dp(12);
            row.addView(col, clp);
            if (on) row.addView(Ui.icon(this, Ui.IconView.CHECK, Ui.accent, dp(18)));
            row.setOnClickListener(v -> {
                if (Busy.holder() != null) {
                    toast(getString(R.string.msg_busy_switch, busyLabel()));
                    return;
                }
                if (f.getAbsolutePath().equals(prefs.roleDir()) && modelReady) return;
                reloadModels(null, f.getAbsolutePath());
                refreshRolesList();
            });
            rolesBox.addView(row, Ui.lp());
            View line = new View(this);
            line.setBackgroundColor(Ui.divider);
            rolesBox.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f))));
        }
    }

    private void confirmDeleteRole() {
        int pos = -1;
        for (int i = 0; i < roles.size(); i++)
            if (roles.get(i).getAbsolutePath().equals(prefs.roleDir())) pos = i;
        if (pos < 0) { toast(getString(R.string.no_role)); return; }
        final File dir = roles.get(pos);
        File parent = dir.getParentFile();
        if (parent == null || !"roles".equals(parent.getName())) {
            toast(getString(R.string.role_only_roles));
            return;
        }
        DialogSurface.builder(this)
                .setTitle(getString(R.string.dlg_del_role))
                .setMessage(getString(R.string.dlg_del_role_msg, dir.getName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dd, ww) -> {
                    final String rm = dir.getAbsolutePath();
                    new Thread(() -> {
                        if(!Busy.acquire("role-delete")){runOnUiThread(()->toast(getString(R.string.ui_delete_busy)));return;}
                        long freed = 0;
                        try { Engine.release(); freed = delTreeSize(dir); } catch (Throwable t) { log("delete failed " + t); }
                        finally{Busy.release("role-delete");}
                        final long fr = freed;
                        runOnUiThread(() -> {
                            if (prefs.roleDir().startsWith(rm)) prefs.setRoleDir("");
                            log("role deleted " + dir.getName() + " freed " + (fr >> 20) + " MB");
                            scanRoles();
                            refreshAll();
                            toast(getString(R.string.role_deleted, dir.getName(), (int) (fr >> 20)));
                        });
                    }, "role-delete").start();
                })
                .show();
    }

    private static long delTreeSize(File f) {
        long n = 0;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) n += delTreeSize(c);
        long len = f.length();
        if (f.delete()) n += len;
        return n;
    }

    private void npuScanRoles() {
        npuRoles.clear();
        File d = new File(prefs.rolesDir());
        File[] fs = d.listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) {
                if (!f.isDirectory()) continue;
                String fn = f.getName();
                if (fn.startsWith("_") || fn.startsWith(".")) continue;
                if (!new File(f, "dec.onnx").exists()) continue;
                npuRoles.add(f);
            }
        }
        if (npuRoleIndex < 0 || npuRoleIndex >= npuRoles.size()) npuRoleIndex = npuRoles.isEmpty() ? -1 : 0;
        if (npuRoleValue != null)
            npuRoleValue.setText(npuRoleIndex < 0 ? getString(R.string.npu_no_role)
                    : npuRoles.get(npuRoleIndex).getName());
        refreshNpuPanel();
    }

    private void pickNpuRole() {
        if (npuRoles.isEmpty()) { toast(getString(R.string.npu_no_role)); return; }
        String[] names = new String[npuRoles.size()];
        for (int i = 0; i < npuRoles.size(); i++) names[i] = npuRoles.get(i).getName();
        Ui.dialogList(this, getString(R.string.npu_role), names, npuRoleIndex, (d, w) -> {
            npuRoleIndex = w;
            if (npuRoleValue != null) npuRoleValue.setText(names[w]);
            refreshNpuPanel();
        });
    }

    private void refreshNpuPanel() {
        if (npuListBox == null) return;
        npuListBox.removeAllViews();
        npuCbMap = new LinkedHashMap<>();
        if (npuRoleIndex < 0 || npuRoleIndex >= npuRoles.size()) {
            if (npuStatus != null) npuStatus.setText(getString(R.string.npu_no_role));
            return;
        }
        File roleDir = npuRoles.get(npuRoleIndex);
        if (Engine.isEpContext(new File(roleDir, "dec.onnx"))) {
            if (npuStatus != null) npuStatus.setText(getString(R.string.npu_builtin));
            return;
        }
        List<Integer> done = Engine.listCompiled(roleDir);
        if (done.isEmpty()) {
            if (npuStatus != null) npuStatus.setText(getString(R.string.npu_none));
            return;
        }
        for (int f : done) {
            CheckBox cb = new CheckBox(this);
            File cf = new File(roleDir, "dec_ctx_" + f + ".onnx");
            cb.setText(getString(R.string.tier_label, Prefs.framesToMs(f), f, (int) (cf.length() >> 20)));
            cb.setTextSize(13f);
            cb.setTextColor(Ui.textDim);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.accent));
            npuCbMap.put(cb, f);
            npuListBox.addView(cb);
        }
        if (npuStatus != null) npuStatus.setText(getString(R.string.npu_count, done.size()));
    }

    private void startNpuCompile() {
        if (npuRoleIndex < 0 || npuRoleIndex >= npuRoles.size()) {
            toast(getString(R.string.npu_no_role));
            return;
        }
        final File roleDir = npuRoles.get(npuRoleIndex);
        int ms = readMs(eNpuMs, 50, 30000, 300);
        final int t = Prefs.msToFrames(ms);
        if (t < 4) { toast(getString(R.string.npu_too_small)); return; }
        if (!Busy.acquire("compile")) { toast(getString(R.string.msg_busy, busyLabel())); return; }
        Engine.release();
        modelReady = false;
        residentState = getString(R.string.npu_compiling);
        refreshAll();
        log("=== NPU compile: " + roleDir.getName() + " T=" + t + " (" + ms + "ms) ===");
        new Thread(() -> {
            try {
                long cost = Engine.compileNpuContext(roleDir, t, this::log);
                final String msg = cost == 0
                        ? getString(R.string.npu_exists, t)
                        : getString(R.string.npu_done, t, (int) (cost / 1000));
                log(msg);
                runOnUiThread(() -> {
                    refreshNpuPanel();
                    toast(msg);
                });
            } catch (Throwable ex) {
                final String m = getString(R.string.npu_failed, t, String.valueOf(ex.getMessage()));
                log(m);
                runOnUiThread(() -> {
                    refreshNpuPanel();
                    toast(m);
                });
            } finally {
                Busy.release("compile");
            }
        }, "npu-compile").start();
    }

    private void confirmDeleteTiers() {
        if (npuRoleIndex < 0 || npuRoleIndex >= npuRoles.size()) {
            toast(getString(R.string.npu_no_role));
            return;
        }
        final File roleDir = npuRoles.get(npuRoleIndex);
        if (Engine.listCompiled(roleDir).isEmpty()) {
            toast(getString(R.string.npu_none));
            return;
        }
        final List<Integer> want = new ArrayList<>();
        final List<Long> sizes = new ArrayList<>();
        long total = 0;
        for (Map.Entry<CheckBox, Integer> en : npuCbMap.entrySet()) {
            if (!en.getKey().isChecked()) continue;
            File cf = new File(roleDir, "dec_ctx_" + en.getValue() + ".onnx");
            if (!cf.isFile()) continue;
            want.add(en.getValue());
            sizes.add(cf.length());
            total += cf.length();
        }
        if (want.isEmpty()) { toast(getString(R.string.npu_del_none)); return; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < want.size(); i++) {
            sb.append("\n  ").append(Prefs.framesToMs(want.get(i))).append("ms  (")
                    .append(sizes.get(i) >> 20).append(" MB)");
        }
        final long tt = total;
        DialogSurface.builder(this)
                .setTitle(getString(R.string.npu_del_title, want.size()))
                .setMessage(getString(R.string.npu_del_msg, sb.toString(), (int) (total >> 20)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dd, ww) -> {
                    if(!Busy.acquire("compile-delete")){toast(getString(R.string.ui_compile_delete_busy));return;}
                    long freed = 0;
                    int ok = 0;
                    try { Engine.release();
                    for (int i = 0; i < want.size(); i++) {
                        File cf = new File(roleDir, "dec_ctx_" + want.get(i) + ".onnx");
                        if (cf.delete()) { freed += sizes.get(i); ok++; }
                    }
                    } finally { Busy.release("compile-delete"); }
                    log("tiers deleted " + ok + " freed " + (freed >> 20) + " MB");
                    refreshNpuPanel();
                    toast(getString(R.string.npu_del_ok, ok, (int) (freed >> 20)));
                })
                .show();
    }

    // ================= 分片 =================

    private void showChunkDialog(final boolean isFile) {
        int cur = isFile ? prefs.fileFrames() : prefs.rtFrames();
        List<Integer> done = Engine.listCompiled(new File(prefs.roleDir()));
        final List<Integer> tiers = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int checked = -1;
        if (!done.contains(cur)) {
            checked = 0;
            tiers.add(cur);
            labels.add(getString(R.string.chunk_uncompiled, Prefs.framesToMs(cur)));
        }
        for (int t : done) {
            if (t == cur && checked < 0) checked = tiers.size();
            tiers.add(t);
            labels.add(getString("npu".equals(prefs.device())
                    ? R.string.chunk_npu_tier : R.string.chunk_cpu_tier, Prefs.framesToMs(t)));
        }
        final int customIdx = labels.size();
        labels.add(getString(R.string.chunk_custom_full,
                getString(R.string.chunk_custom), getString(R.string.chunk_custom_note)));
        Ui.dialogList(this, getString(R.string.lbl_chunk), labels.toArray(new String[0]), checked, (d, w) -> {
            if (w == customIdx) { askCustomChunk(isFile); return; }
            if (w < 0 || w >= tiers.size()) return;
            int next = tiers.get(w);
            if (next == (isFile ? prefs.fileFrames() : prefs.rtFrames())) return;
            if (Busy.holder() != null) { toast(getString(R.string.msg_busy_switch, busyLabel())); return; }
            prefs.set(isFile ? "file_frames" : "rt_frames", next);
            reloadModels(null, null);
            refreshChunkValues();
        });
    }

    private void askCustomChunk(final boolean isFile) {
        final EditText e = Ui.field(this, "");
        e.setText(String.valueOf(Prefs.framesToMs(isFile ? prefs.fileFrames() : prefs.rtFrames())));
        DialogSurface.builder(this)
                .setTitle(getString(R.string.dlg_chunk_title))
                .setView(e)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    int ms = readMs(e, 50, 30000, 2972);
                    if (Busy.holder() != null) { toast(getString(R.string.msg_busy, busyLabel())); return; }
                    prefs.set(isFile ? "file_frames" : "rt_frames", Prefs.msToFrames(ms));
                    reloadModels(null, null);
                    refreshChunkValues();
                })
                .show();
    }

    // ================= Root / 语言 / 输出 =================

    private void checkRoot() {
        if (rootHeroState == null) return;
        if(rootChecking || rootState==1) return;
        rootChecking=true;
        rootHeroState.setText(getString(rootState==1 ? R.string.root_granted : R.string.root_denied));
        styleRootHero();
        new Thread(() -> {
            int state=3; Process process=null;
            try {
                process=new ProcessBuilder("su","-c","id").redirectErrorStream(true).start();
                if(process.waitFor(60,TimeUnit.SECONDS)) {
                    BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()));
                    StringBuilder output=new StringBuilder(); String line;
                    while((line=reader.readLine())!=null) output.append(line).append(' ');
                    String result=output.toString();
                    if(process.exitValue()==0 && result.contains("uid=0")) state=1;
                    else if(result.toLowerCase(Locale.ROOT).contains("denied") || result.contains("not allowed") || process.exitValue()!=0) state=2;
                }
            } catch(Throwable e) {} finally { if(process!=null) process.destroy(); }
            final int st=state;
            runOnUiThread(() -> {
                rootChecking=false; rootState=st;
                if(rootHeroState!=null) {
                    rootHeroState.setText(getString(st==1 ? R.string.root_granted : R.string.root_denied));
                }
                styleRootHero();
            });
        },"root-check").start();
    }

    private void pickLanguage() {
        String[] items=I18n.languageLabels(this);
        Ui.dialogList(this,getString(R.string.sec_lang),items,I18n.languageIndex(this),(d,w)-> {
            getSharedPreferences("svc",MODE_PRIVATE).edit().putString("lang",I18n.languageCodes()[w]).apply();
            recreate();
        });
    }

    private int readMs(EditText e, int lo, int hi, int def) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private void saveSettings() {
        try {
            int chunk = prefs.fileFrames();
            int bufMs = readMs(eFileBuf, 0, 1500, 186);
            prefs.set("file_frames", chunk);
            prefs.set("file_xfade", Math.min(Prefs.msToFrames(bufMs), Math.max(1, chunk / 2)));
        } catch (Exception ignore) {}
        try {
            int chunk = prefs.rtFrames();
            int bufMs = readMs(eRtBuf, 0, 1500, 23);
            prefs.set("rt_frames", chunk);
            prefs.set("rt_xfade", Math.min(Prefs.msToFrames(bufMs), chunk/2));
        } catch (Exception ignore) {}
        try { prefs.setInject(swInject.isChecked()); } catch (Exception ignore) {}
        try { prefs.setBypass(swBypass.isChecked()); } catch (Exception ignore) {}
    }

    private void ensurePerms() {
        List<String> req = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), REQ_AUDIO);
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignore) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(perms[i])
                    && res[i] != PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.perm_notif_msg));
            }
        }
    }

    private void cleanupTemp() {
        new Thread(() -> {
            try {
                File dir = getCacheDir();
                File[] fs = dir.listFiles();
                if (fs == null) return;
                long now = System.currentTimeMillis();
                for (File f : fs) {
                    String n = f.getName();
                    if (!n.startsWith("in_") && !n.startsWith("imp_")) continue;
                    if (now - f.lastModified() > 6L * 3600 * 1000) f.delete();
                }
            } catch (Throwable ignore) {}
        }, "cache-clean").start();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "1.0";
        }
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    private static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i), nb = b.substring(sj, j);
                int cmp;
                try {
                    cmp = Long.compare(Long.parseLong(na), Long.parseLong(nb));
                } catch (Throwable e) {
                    cmp = na.length() != nb.length() ? na.length() - nb.length() : na.compareTo(nb);
                }
                if (cmp != 0) return cmp;
            } else {
                char la = Character.toLowerCase(ca), lb = Character.toLowerCase(cb);
                if (la != lb) return la - lb;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
