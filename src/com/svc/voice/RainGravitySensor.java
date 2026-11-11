package com.svc.voice;
import android.content.Context;
import android.hardware.*;
import android.view.WindowManager;
/** Registered only while the overlay is active. Accelerometer fallback is filtered. */
final class RainGravitySensor implements SensorEventListener {
 interface Sink {void direction(float x,float y);}
 private final SensorManager manager;private final Sensor sensor;private final WindowManager windows;private final Sink sink;private final RainDirection filter=new RainDirection();private final float[] mapped=new float[2];private boolean registered;private long previous;
 RainGravitySensor(Context c,Sink sink){this.sink=sink;manager=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);windows=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);Sensor s=manager==null?null:manager.getDefaultSensor(Sensor.TYPE_GRAVITY);sensor=s!=null?s:manager==null?null:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);}
 void active(boolean on){if(on==registered)return;if(on&&sensor!=null){previous=0;registered=manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);}else if(registered){manager.unregisterListener(this);registered=false;previous=0;}}
 void reset(){filter.reset();sink.direction(0,1);}
 public void onSensorChanged(SensorEvent e){float dt=previous==0?1f/60f:(e.timestamp-previous)*1e-9f;previous=e.timestamp;RainDirection.map(-e.values[0],e.values[1],windows.getDefaultDisplay().getRotation(),mapped);filter.sample(mapped[0],mapped[1],dt);sink.direction(filter.x,filter.y);}
 public void onAccuracyChanged(Sensor s,int a){}
}
