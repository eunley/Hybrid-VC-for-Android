package com.svc.voice;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Overlay lifetime is independent of microphone lifetime. All window operations use main looper. */
public final class FloatingMenuService extends Service {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(I18n.wrap(base)); }

    private static final String CHANNEL="floating_menu";
    private static final int NOTIFICATION=4812;
    public static final String ACTION_START="com.svc.voice.FLOATING_START";
    public static final String ACTION_STOP="com.svc.voice.FLOATING_STOP";
    public static volatile boolean active;
    public static boolean canDraw(Context c){return Settings.canDrawOverlays(c);}
    /** Call from visible Activity after checking canDraw; never starts audio. */
    public static boolean start(Context c){
        if(!canDraw(c))return false;
        Intent i=new Intent(c,FloatingMenuService.class).setAction(ACTION_START);
        try {c.startForegroundService(i);return true;}catch(RuntimeException e){return false;}
    }
    public static void stop(Context c){c.stopService(new Intent(c,FloatingMenuService.class));}
    public static Intent permissionIntent(Context c){return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:"+c.getPackageName()));}

    private WindowManager wm;
    private View root;
    private TextView controlTitle;
    private WindowManager.LayoutParams pos;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean expanded, docked=true, dockRight, syncing;
    private int iconX=24;
    private int iconY=160, menuX=24, menuY=160;
    private Switch live;
    /* ---- 快捷录音 UI（只做交互与结果展示，录音在 QuickVoiceService，转换在 JobService） ---- */
    private TextView quickRec, quickPlay, quickStatus;
    private android.media.MediaPlayer player;   // 外放播放，用完即 release
    private String playPath;                    // 仅本次成功输出，失败/新一轮录音会清空
    private long quickToken;                    // 已认领的提交 token
    private long quickWaitUntil;                // 等待回传的截止时刻，防卡死
    private boolean quickDoneSeen;              // 已消费本次回传结论
    private final Runnable tick=new Runnable(){public void run(){
        if(!canDraw(FloatingMenuService.this)){stopSelf();return;}
        if(live!=null){syncing=true;live.setChecked(RealtimeService.running);syncing=false;}
        refreshQuick();
        refreshRtf();
        handler.postDelayed(this,500);
    }};
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int sw(){return getResources().getDisplayMetrics().widthPixels;}
    private int sh(){return getResources().getDisplayMetrics().heightPixels;}
    private int clamp(int n,int max){return Math.max(0,Math.min(n,Math.max(0,max)));}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    private WindowManager.LayoutParams params(int w,int h){
        WindowManager.LayoutParams v=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        v.gravity=Gravity.TOP|Gravity.LEFT;return v;
    }
    @Override public void onCreate(){super.onCreate();wm=getSystemService(WindowManager.class);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&ACTION_STOP.equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!canDraw(this)){stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,I18n.text(FloatingMenuService.this,R.string.ov_menu_channel),NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close=PendingIntent.getService(this,1,new Intent(this,FloatingMenuService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(I18n.text(FloatingMenuService.this,R.string.ov_menu_enabled)).setContentText(I18n.text(FloatingMenuService.this,R.string.ov_menu_notification))
            .setContentIntent(open).addAction(new Notification.Action.Builder(null,I18n.text(FloatingMenuService.this,R.string.ov_menu_close),close).build()).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION,n);
        if(root==null){active=true;render();handler.post(tick);}
        return START_NOT_STICKY;
    }
    private void removeRoot(){if(root!=null){try{wm.removeView(root);}catch(RuntimeException ignored){}root=null;}live=null;controlTitle=null;quickRec=null;quickPlay=null;quickStatus=null;}
    private void refreshRtf(){
        if(controlTitle==null)return;
        double r=LiveWindowState.snapshot()[7];
        String label=I18n.text(FloatingMenuService.this,R.string.ov_control_rtf)+(Double.isNaN(r)?"--":String.format(java.util.Locale.US,"%.2f",r));
        if(!label.contentEquals(controlTitle.getText()))controlTitle.setText(label);
    }
    private void render(){
        removeRoot();
        if(!expanded){
            ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.floating_menu_icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setContentDescription(I18n.text(FloatingMenuService.this,R.string.ov_menu_open));
            icon.setAlpha(docked?.45f:1f);root=icon;pos=params(dp(56),dp(56));
            pos.x=docked?(dockRight?sw()-dp(28):-dp(28)):clamp(iconX,sw()-dp(56));
            pos.flags|=WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;pos.y=clamp(iconY,sh()-dp(80));
            icon.setOnTouchListener(new View.OnTouchListener(){float x,y;int ox,oy;boolean moved;
                public boolean onTouch(View v,android.view.MotionEvent e){switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();ox=pos.x;oy=pos.y;moved=false;v.setAlpha(1f);return true;
                case MotionEvent.ACTION_MOVE:float dx=e.getRawX()-x,dy=e.getRawY()-y;
                    if(Math.hypot(dx,dy)>dp(6))moved=true;
                    if(moved){docked=false;pos.x=clamp(ox+(int)dx,sw()-dp(56));pos.y=clamp(oy+(int)dy,sh()-dp(80));updateRoot();}return true;
                case MotionEvent.ACTION_UP:
                    if(moved){iconX=pos.x;iconY=pos.y;dockRight=pos.x+dp(28)>sw()/2;docked=pos.x<=dp(18)||pos.x>=sw()-dp(74);render();}
                    else {v.performClick();openMenu();}return true;
                case MotionEvent.ACTION_CANCEL:iconX=pos.x;iconY=pos.y;docked=pos.x<=dp(18)||pos.x>=sw()-dp(74);dockRight=pos.x+dp(28)>sw()/2;render();return true;
                }return false;}});
        }else{
            LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(8),dp(12),dp(12));panel.setBackground(background(0x88202632));
            LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setBackground(background(0xdd202632));
            TextView back=text("‹",28);back.setGravity(Gravity.CENTER);top.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));
            TextView title=text(I18n.text(FloatingMenuService.this,R.string.ov_control_rtf_initial),14);controlTitle=title;refreshRtf();top.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
            back.setContentDescription(I18n.text(FloatingMenuService.this,R.string.ov_menu_collapse));back.setOnClickListener(v->{expanded=false;render();});
            installMenuDrag(top);installMenuDrag(title);panel.addView(top);
            live=new Switch(this);live.setText(I18n.text(FloatingMenuService.this,R.string.ov_realtime));live.setTextColor(Color.WHITE);live.setChecked(RealtimeService.running);panel.addView(live);
            live.setOnCheckedChangeListener((b,on)->{if(!syncing)toggleLive(on);});
            LinearLayout quickRow=new LinearLayout(this);quickRow.setOrientation(LinearLayout.HORIZONTAL);
            quickRow.setPadding(0,dp(6),0,0);
            quickRec=chip();quickPlay=chip();
            LinearLayout.LayoutParams q1=new LinearLayout.LayoutParams(0,WindowManager.LayoutParams.WRAP_CONTENT,1);q1.rightMargin=dp(6);
            quickRow.addView(quickRec,q1);
            quickRow.addView(quickPlay,new LinearLayout.LayoutParams(0,WindowManager.LayoutParams.WRAP_CONTENT,1));
            quickRec.setOnClickListener(v->onQuickRec());
            quickPlay.setOnClickListener(v->onQuickPlay());
            panel.addView(quickRow);
            quickStatus=text("",11);quickStatus.setTextColor(0xffccd6e0);quickStatus.setPadding(0,dp(5),0,0);
            panel.addView(quickStatus);
            refreshQuick();
            root=panel;pos=params(Math.min(dp(292),sw()),WindowManager.LayoutParams.WRAP_CONTENT);pos.x=clamp(menuX,sw()-pos.width);pos.y=clamp(menuY,sh()-dp(270));
        }
        try{wm.addView(root,pos);}catch(RuntimeException e){stopSelf();}
    }
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private void updateRoot(){try{wm.updateViewLayout(root,pos);}catch(RuntimeException e){stopSelf();}}
    private void openMenu(){
        menuX=clamp(docked?(dockRight?sw()-dp(312):dp(20)):iconX,sw()-dp(292));menuY=iconY;
        expanded=true;render();
    }
    private void installMenuDrag(View handle){handle.setOnTouchListener(new View.OnTouchListener(){
        float x,y;int ox,oy;boolean dragging;final Runnable hold=()->dragging=true;
        public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();ox=pos.x;oy=pos.y;dragging=true;return true;
            case MotionEvent.ACTION_MOVE:if(dragging){pos.x=clamp(ox+(int)(e.getRawX()-x),sw()-pos.width);pos.y=clamp(oy+(int)(e.getRawY()-y),sh()-root.getHeight());menuX=pos.x;menuY=pos.y;updateRoot();}
                else if(Math.hypot(e.getRawX()-x,e.getRawY()-y)>dp(8))handler.removeCallbacks(hold);return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:handler.removeCallbacks(hold);dragging=false;return true;
        }return false;}});}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void toggleLive(boolean on){
        if(!on){RealtimeService.requestStop();stopService(new Intent(this,RealtimeService.class));return;}
        String reason=null;
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)reason=I18n.text(FloatingMenuService.this,R.string.ov_need_microphone);
        else if(!MainActivity.modelReady)reason=I18n.text(FloatingMenuService.this,R.string.ov_need_model);
        else if(new Prefs(this).roleDir().isEmpty())reason=I18n.text(FloatingMenuService.this,R.string.ov_need_role);
        else if(QuickVoiceService.recording)reason=I18n.text(FloatingMenuService.this,R.string.ov_recording_busy);
        else if(Busy.holder()!=null)reason=I18n.text(FloatingMenuService.this,R.string.ov_task_busy);
        if(reason!=null){toast(reason);syncing=true;live.setChecked(false);syncing=false;return;}
        try{startForegroundService(new Intent(this,RealtimeService.class));}
        catch(RuntimeException e){toast(I18n.text(FloatingMenuService.this,R.string.ov_realtime_start_failed));syncing=true;live.setChecked(false);syncing=false;}
    }
    private TextView chip(){TextView t=text("",13);t.setGravity(Gravity.CENTER);t.setBackground(background(0xdd2b3442));t.setPadding(dp(8),dp(9),dp(8),dp(9));return t;}
    /** 左键：录音 / 结束（结束即自动送现有 JobService 转换） */
    private void onQuickRec(){
        if(QuickVoiceService.recording){
            stopPlayer();
            // 新一轮开始：旧结果一律作废，避免失败时播到上一次的输出
            playPath=null;quickDoneSeen=false;quickWaitUntil=SystemClock.uptimeMillis()+15000;
            QuickVoiceService.stopAndConvert(this);
        }else{
            String reason=QuickVoiceService.startRecording(this);
            if(reason!=null){toast(reason);return;}
            playPath=null;quickDoneSeen=false;quickWaitUntil=0;
        }
        refreshQuick();
    }
    /** 右键：外放播放本次成功输出（再点停止） */
    private void onQuickPlay(){
        if(JobService.quickPending(QuickVoiceService.lastToken)){JobService.cancelQuick(QuickVoiceService.lastToken);refreshQuick();return;}
        if(player!=null){stopPlayer();refreshQuick();return;}
        if(playPath==null)return;
        java.io.File f=new java.io.File(playPath);
        if(!f.isFile()){playPath=null;toast(I18n.text(FloatingMenuService.this,R.string.ov_output_missing));refreshQuick();return;}
        try{
            android.media.MediaPlayer mp=new android.media.MediaPlayer();
            // 走扬声器外放，不做通话注入
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(playPath);
            mp.setOnCompletionListener(x->{stopPlayer();refreshQuick();});
            mp.setOnErrorListener((x,w,e)->{stopPlayer();toast(I18n.text(FloatingMenuService.this,R.string.ov_play_failed));refreshQuick();return true;});
            mp.prepare();
            mp.start();
            player=mp;
        }catch(Throwable e){stopPlayer();toast(I18n.text(FloatingMenuService.this,R.string.ov_play_failed));}
        refreshQuick();
    }
    private void stopPlayer(){
        android.media.MediaPlayer p=player;player=null;if(p==null)return;
        try{p.stop();}catch(Throwable ignored){}
        try{p.release();}catch(Throwable ignored){}
    }
    /** 每 150ms 由 tick 驱动：按钮文字、结果回传认领、状态行 */
    private void refreshQuick(){
        long now=SystemClock.uptimeMillis();
        // 认领新的提交（QuickVoiceService 在真正送 JobService 前写 lastToken）
        long t=QuickVoiceService.lastToken;
        if(t!=0&&t!=quickToken&&!QuickVoiceService.recording){
            quickToken=t;quickDoneSeen=false;
            if(quickWaitUntil==0)quickWaitUntil=now+15000;
        }
        // 结果回传：token 必须对得上，失败时绝不回退到旧文件
        if(quickToken!=0&&!quickDoneSeen&&JobService.quickToken==quickToken&&JobService.quickDone){
            quickDoneSeen=true;quickWaitUntil=0;
            if(JobService.quickOk&&!JobService.quickOutPath.isEmpty()){
                playPath=JobService.quickOutPath;
                toast(I18n.text(FloatingMenuService.this,R.string.ov_generated)+new java.io.File(playPath).getName());
            }else{
                playPath=null;
                toast(I18n.text(FloatingMenuService.this,R.string.ov_conversion_failed)+(JobService.quickMessage.isEmpty()?I18n.text(FloatingMenuService.this,R.string.ov_unknown_error):JobService.quickMessage));
            }
        }else if(quickToken!=0&&!quickDoneSeen&&quickWaitUntil>0&&now>quickWaitUntil&&!JobService.quickPending(quickToken)){
            quickDoneSeen=true;quickWaitUntil=0;playPath=null;
            toast(I18n.text(FloatingMenuService.this,R.string.ov_conversion_timeout));
        }
        if(quickRec!=null)quickRec.setText(QuickVoiceService.recording?I18n.text(FloatingMenuService.this,R.string.ov_stop_convert):I18n.text(FloatingMenuService.this,R.string.ov_quick_record));
        if(quickPlay!=null){
            boolean pending=JobService.quickPending(QuickVoiceService.lastToken);boolean can=pending||playPath!=null||player!=null;
            quickPlay.setEnabled(can);quickPlay.setAlpha(can?1f:.45f);
            quickPlay.setText(pending?I18n.text(FloatingMenuService.this,R.string.ov_end_conversion):player!=null?I18n.text(FloatingMenuService.this,R.string.ov_stop_play):I18n.text(FloatingMenuService.this,R.string.ov_play_current));quickPlay.setTextColor(pending?0xffff5555:0xffeeeeee);
        }
        if(quickStatus!=null){
            String st;
            if(QuickVoiceService.recording){
                long ms=QuickVoiceService.startedAtMs==0?0:now-QuickVoiceService.startedAtMs;
                st=I18n.text(FloatingMenuService.this,R.string.ov_recording_prefix)+String.format(java.util.Locale.ROOT,"%.1f",ms/1000.0)+I18n.text(FloatingMenuService.this,R.string.ov_recording_suffix);
            }else if(quickToken!=0&&!quickDoneSeen&&QuickVoiceService.lastToken==quickToken){
                st=I18n.text(FloatingMenuService.this,R.string.ov_converting)+(!JobService.status.isEmpty()?" "+JobService.status:"");
            }else if(player!=null){
                st=I18n.text(FloatingMenuService.this,R.string.ov_playing_output);
            }else if(playPath!=null){
                st=I18n.text(FloatingMenuService.this,R.string.ov_current_output)+new java.io.File(playPath).getName();
            }else{
                st=QuickVoiceService.status==null?"":QuickVoiceService.status;
            }
            if(st.length()>34)st=st.substring(0,34)+"…";
            quickStatus.setText(st);
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration cfg){super.onConfigurationChanged(cfg);if(root!=null)render();}
    @Override public void onDestroy(){
        // 关浮窗：不自动转，直接丢弃正在进行的录音；播放器必须释放
        if(QuickVoiceService.recording)QuickVoiceService.abort(this);
        stopPlayer();
        handler.removeCallbacksAndMessages(null);removeRoot();active=false;stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
