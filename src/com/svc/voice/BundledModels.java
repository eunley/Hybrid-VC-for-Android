package com.svc.voice;
import android.content.Context;
import java.io.File;
public final class BundledModels {
 private static Context app;
 public static volatile String state="idle";
 public static void init(Context c){app=c.getApplicationContext();}
 public static void request(Context c){init(c);new Thread(()->{
  if(!Busy.acquire("model-install"))return;
  try{ensureReady(new File(new Prefs(c).modelsDir()),s->android.util.Log.i("SVCModels",s));}
  catch(Exception e){state="Model installation failed; retry on next request: "+e.getMessage();android.util.Log.e("SVCModels",state,e);}
  finally{Busy.release("model-install");ModelPreloader.request(c);}
 },"bundled-model-install").start();}
 public static synchronized void ensureReady(File dir,Engine.Log log) throws Exception {
  if(app==null)throw new java.io.IOException("Model assets not initialized");
  state="Preparing hubert.onnx";log.log(state);
  ModelFiles.install(dir,"hubert.onnx",377679877L,"b1e023bf8e06cb375533d4734df20c799a7986d5ac0ffb640c4d3fab5575d84e",name->app.getAssets().open("models/"+name));
  state="Preparing rmvpe.onnx";log.log(state);
  ModelFiles.install(dir,"rmvpe.onnx",361688443L,"5370e71ac80af8b4b7c793d27efd51fd8bf962de3a7ede0766dac0befa3660fd",name->app.getAssets().open("models/"+name));
  state="Preparing mel.bin";log.log(state);
  ModelFiles.install(dir,"mel.bin",266772L,"058c303a554de10fd154bd95e7a53da83b30b6f69141c20501ffb10d26baef36",name->app.getAssets().open("models/"+name));
  state="Models ready";
 }
}
