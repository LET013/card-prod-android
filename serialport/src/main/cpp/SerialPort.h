#include <jni.h>

#ifndef _Included_com_xingyao_serialport_SerialPort
#define _Included_com_xingyao_serialport_SerialPort
#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jobject JNICALL Java_com_xingyao_serialport_SerialPort_open
  (JNIEnv *, jobject, jstring, jint, jint);

JNIEXPORT void JNICALL Java_com_xingyao_serialport_SerialPort_close
  (JNIEnv *, jobject);

JNIEXPORT void JNICALL Java_com_xingyao_serialport_SerialPort_tcflush
  (JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif
