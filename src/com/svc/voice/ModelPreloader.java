package com.svc.voice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
/** Process-owned asynchronous preload; uses the same Busy lock and Engine cache as inference. */
public final class ModelPreloader {
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static boolean pending,running; private static String completed="";
 public static volatile String state="idle"; public static volatile int attempts,successes;
 private ModelPreloader(){}
 public static synchronized void request(Context context){
  final Context app=context.getApplicationContext();
  if(pending||running)return;
  Prefs p=new Prefs(app);String key=key(p);
  if(key.equals(completed)&&(MainActivity.modelReady||"base-ready".equals(state)))return;
  pending=true; MAIN.post(()->begin(app));
 }
 private static String key(Prefs p){return p.modelsDir()+"|"+p.device()+"|"+p.roleDir()+"|"+p.fileFrames()+"|"+Tune.threads()+"|"+Tune.mask();}
 private static synchronized void begin(Context app){
  if(!Busy.acquire("model-load")){state="waiting:"+Busy.holder();MAIN.postDelayed(()->begin(app),500);return;}
  pending=false;running=true;attempts++;state="loading";
  Prefs p=new Prefs(app);final String requested=key(p);
  MainActivity.modelReady=false;MainActivity.residentState=app.getString(R.string.resident_loading,app.getString("npu".equals(p.device())?R.string.chip_npu:R.string.chip_cpu));
  new Thread(()->{
   try{
    Engine.device=p.device();Engine e=Engine.acquire(p.modelsDir(),s->AppLog.i(null,s));
    if(!p.roleDir().isEmpty())e.loadRole(p.roleDir(),p.fileFrames());
    MainActivity.modelReady=!p.roleDir().isEmpty();
    MainActivity.residentState=MainActivity.modelReady?app.getString("npu".equals(p.device())?R.string.resident_ready_npu:R.string.resident_ready_cpu):app.getString(R.string.need_role);
    synchronized(ModelPreloader.class){completed=requested;successes++;state=MainActivity.modelReady?"ready":"base-ready";}
    android.util.Log.i("SVCPreload","complete attempts="+attempts+" state="+state+" config="+requested);
   }catch(Throwable e){MainActivity.modelReady=false;MainActivity.residentState=app.getString(R.string.resident_failed,String.valueOf(e.getMessage()));state="failed:"+e;android.util.Log.e("SVCPreload",state,e);}
   finally{Busy.release("model-load");synchronized(ModelPreloader.class){running=false;} MAIN.post(()->{app.sendBroadcast(new android.content.Intent("com.svc.voice.MODEL_STATE").setPackage(app.getPackageName()));});}
  },"startup-model-preload").start();
 }
 public static String statistics(){return "state="+state+" attempts="+attempts+" successes="+successes+" busy="+Busy.holder();}
}
