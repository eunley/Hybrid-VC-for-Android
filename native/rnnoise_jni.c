#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"
JNIEXPORT jlong JNICALL Java_com_svc_voice_RnNoise_create(JNIEnv* e,jclass c){return (jlong)(intptr_t)rnnoise_create(NULL);}
JNIEXPORT void JNICALL Java_com_svc_voice_RnNoise_destroy(JNIEnv* e,jclass c,jlong p){if(p)rnnoise_destroy((DenoiseState*)(intptr_t)p);}
JNIEXPORT void JNICALL Java_com_svc_voice_RnNoise_process(JNIEnv* e,jclass c,jlong p,jfloatArray a){float x[480];(*e)->GetFloatArrayRegion(e,a,0,480,x);rnnoise_process_frame((DenoiseState*)(intptr_t)p,x,x);(*e)->SetFloatArrayRegion(e,a,0,480,x);}
