package com.svc.voice;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;

/** Explicitly enabled local API. Notification and lifecycle independent of audio services. */
public final class LocalHttpService extends Service {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(I18n.wrap(base)); }

    private static volatile boolean active;
    private static volatile String status="";
    private static volatile int statusKey=R.string.http_preparing;
    private static volatile int listenPort;
    private static volatile boolean loopback;
    public static String status(Context c){
        if(!active)return I18n.text(c,R.string.http_closed);
        if(statusKey!=0)return I18n.text(c,statusKey);
        return "http://"+(loopback?"127.0.0.1":"0.0.0.0")+":"+listenPort+" · "+I18n.text(c,loopback?R.string.http_loopback:R.string.http_lan);
    }
    private LocalHttpWire wire;
    private LocalHttpApi api;
    private boolean stopping;
    private final Handler main=new Handler(Looper.getMainLooper());
    public static void apply(Context c){
        Intent i=new Intent(c,LocalHttpService.class);
        if(new Prefs(c).httpEnabled()) c.startForegroundService(i);
        else if(active)c.startService(i.setAction("stop"));
    }
    @Override public void onCreate(){
        super.onCreate();
        active=true;statusKey=R.string.http_preparing;
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("svc-local-http",I18n.text(this,R.string.http_title),NotificationManager.IMPORTANCE_LOW));
        show(I18n.text(this,R.string.http_preparing));
        // Initialize inside onStartCommand so storage errors cannot crash Application startup.
    }
    private void show(String text){
        Notification n=new Notification.Builder(this,"svc-local-http").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(I18n.text(this,R.string.http_title)).setContentText(text).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(16180,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(16180,n);
    }
    private void closeWire(){if(wire!=null){try{wire.close();}catch(Exception ignored){}wire=null;}}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        Prefs p=new Prefs(this);
        if(!p.httpEnabled() || (intent!=null&&"stop".equals(intent.getAction()))){
            stopping=true;closeWire();closeApiAsync();statusKey=R.string.http_stopping;status=I18n.text(this,R.string.http_stopping);show(status);awaitStop();return START_NOT_STICKY;
        }
        if(stopping){statusKey=R.string.http_wait_stop;status=I18n.text(this,R.string.http_wait_stop);return START_NOT_STICKY;}
        closeWire();
        try{
            if(api==null)api=new LocalHttpApi(this);
            File temp=new File(getCacheDir().getCanonicalFile(),"http-requests");
            if(!temp.isDirectory()&&!temp.mkdirs())throw new IOException("Cannot create HTTP request directory");
            wire=new LocalHttpWire(p.httpLocalOnly()?"127.0.0.1":"0.0.0.0",p.httpPort(),temp,api,p.httpToken());
            listenPort=p.httpPort();loopback=p.httpLocalOnly();statusKey=0;status=status(this);show(status);
        }catch(Exception e){
            status=I18n.text(this,R.string.http_listen_failed)+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage());
            p.setHttp(false,p.httpPort(),p.httpLocalOnly());
            try{AppLog.i(p.baseDir(),status);}catch(Exception ignored){}
            stopping=true;closeApiAsync();awaitStop();
        }
        return stopping?START_NOT_STICKY:START_STICKY;
    }
    private volatile boolean apiClosed;
    private void closeApiAsync(){new Thread(()->{try{if(api!=null)api.close();}finally{apiClosed=true;}},"http-shutdown").start();}
    private void awaitStop(){if(!apiClosed || (api!=null&&api.busy())){main.postDelayed(this::awaitStop,250);return;}stopForeground(true);stopSelf();}
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);closeWire();if(api!=null&&!apiClosed)closeApiAsync();active=false;status="";super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
