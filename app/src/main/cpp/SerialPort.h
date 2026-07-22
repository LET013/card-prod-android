#include <jni.h>

#ifndef _Included_com_xingyao_card_serial_SerialPort
#define _Included_com_xingyao_card_serial_SerialPort
#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jobject JNICALL Java_com_xingyao_card_serial_SerialPort_open
  (JNIEnv *, jobject, jstring, jint, jint);

JNIEXPORT void JNICALL Java_com_xingyao_card_serial_SerialPort_closeNative
  (JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif
