package com.svc.voice;
/** Official xiph RNNoise, one owned state per conversion session. */
final class RnNoise implements AutoCloseable {
 static {System.loadLibrary("svc_rnnoise");}
 private long state=create();
 RnNoise(){if(state==0)throw new IllegalStateException("RNNoise allocation failed");}
 private static native long create();private static native void destroy(long p);private static native void process(long p,float[] frame);
 void frame(float[] frame){if(state==0)throw new IllegalStateException("RNNoise closed");process(state,frame);}
 public void close(){if(state!=0){destroy(state);state=0;}}
}
