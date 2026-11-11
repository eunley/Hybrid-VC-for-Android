package com.svc.voice;
import android.content.Context;
import android.media.*;
import android.os.*;
import java.util.concurrent.*;
/** Optional lossy monitor branch only. Main injection queues never wait here. */
final class HeadphoneMonitor {
 private final AudioManager manager;
 private final ArrayBlockingQueue<short[]> queue=new ArrayBlockingQueue<>(3);
 private volatile boolean closed,enabled;
 private final Prefs prefs;
 private volatile AudioTrack track;
 private volatile int deviceId=-1;
 private final Thread writer;
 private final AudioDeviceCallback callback=new AudioDeviceCallback(){
  @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] ds){for(AudioDeviceInfo d:ds)if(rank(d.getType())>0){enabled=false;prefs.setMonitorEnabled(false);stopTrack();queue.clear();}}
 };
 HeadphoneMonitor(Context c){prefs=new Prefs(c);manager=c.getSystemService(AudioManager.class);manager.registerAudioDeviceCallback(callback,new Handler(Looper.getMainLooper()));writer=new Thread(this::run,"svc-headphone-monitor");writer.setDaemon(true);writer.start();}
 private AudioDeviceInfo device(){
  AudioDeviceInfo best=null;int bestRank=0;
  for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){int rank=rank(d.getType());if(rank>bestRank){best=d;bestRank=rank;}}
  return best;
 }
 static int rank(int type){switch(type){case AudioDeviceInfo.TYPE_WIRED_HEADSET:case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:case AudioDeviceInfo.TYPE_USB_HEADSET:return 40;case AudioDeviceInfo.TYPE_USB_DEVICE:return 30;case AudioDeviceInfo.TYPE_BLE_HEADSET:return 20;case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:return 10;default:return 0;}}
 void offer(short[] pcm,boolean requested){if(closed)return;if(!requested){enabled=false;queue.clear();stopTrack();return;}if(device()==null){enabled=false;queue.clear();stopTrack();return;}enabled=true;if(!queue.offer(pcm)){queue.poll();queue.offer(pcm);}}
 private synchronized void stopTrack(){AudioTrack t=track;track=null;deviceId=-1;if(t!=null){try{t.pause();t.flush();t.release();}catch(Exception ignored){}}}
 private synchronized AudioTrack output(AudioDeviceInfo d){if(track!=null)return track;stopTrack();AudioTrack t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(Engine.SR_OUT).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(Engine.SR_OUT,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT),Engine.SR_OUT/5)).setTransferMode(AudioTrack.MODE_STREAM).build();track=t;deviceId=d.getId();t.setVolume(1f);t.play();log("普通媒体播放已启动，输出路由由系统管理");return t;}
 private void log(String message){AppLog.i(prefs.baseDir(),"[耳机反听] "+message);}
 private void run(){
  while(!closed){try{
   short[] pcm=queue.poll(100,TimeUnit.MILLISECONDS);if(pcm==null||!enabled)continue;
   AudioDeviceInfo d=device();if(d==null){enabled=false;stopTrack();continue;}
   AudioTrack t=output(d);if(t==null){log("设备拒绝首选路由："+d.getType());continue;}
   int offset=0;long deadline=SystemClock.elapsedRealtime()+Math.max(1000,pcm.length*1000L/Engine.SR_OUT+500);
   while(offset<pcm.length&&!closed&&enabled){
    if(device()==null){enabled=false;stopTrack();queue.clear();break;}
    int n=t.write(pcm,offset,Math.min(2048,pcm.length-offset),AudioTrack.WRITE_NON_BLOCKING);
    if(n<0){log("write失败="+n);stopTrack();break;}offset+=n;
    if(SystemClock.elapsedRealtime()>deadline)break;if(n==0)Thread.sleep(5);
   }
  }catch(InterruptedException e){if(closed)break;}catch(RuntimeException e){log("监控播放异常，后续块重试："+e);stopTrack();}}
  stopTrack();
 }
 private boolean isHeadphone(AudioDeviceInfo d){return rank(d.getType())>0;}
 void disable(){enabled=false;queue.clear();stopTrack();}
 void close(){closed=true;enabled=false;queue.clear();manager.unregisterAudioDeviceCallback(callback);stopTrack();writer.interrupt();}
}
