#include "./gmail_hackwaly_nc1020_NC1020_JNI.h"
#include "./wqx/nc1020.h"
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    Initialize
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_Initialize
  (JNIEnv *env, jobject, jstring path) {
	const char* path1 = env->GetStringUTFChars(path, NULL);
	Initialize(path1);
	env->ReleaseStringUTFChars(path, path1);
}

/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    Reset
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_Reset
  (JNIEnv *, jobject) {
	Reset();
}

/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    Load
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_Load
  (JNIEnv *, jobject) {
	LoadNC1020();
}

/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    Save
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_Save
  (JNIEnv *, jobject) {
	SaveNC1020();
}

/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    SetKey
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_SetKey
  (JNIEnv *, jobject, jint keyId, jboolean downOrUp) {
	SetKey((uint8_t)(keyId & 0x3F), downOrUp);
}

/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    RunTimeSlice
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_RunTimeSlice
  (JNIEnv *, jobject, jint timeSlice, jboolean speedUp) {
	RunTimeSlice((size_t)timeSlice, speedUp);
}

/*
 * Class:     gmail_hackwaly_nc1020_NC1020_JNI
 * Method:    CopyLcdBuffer
 * Signature: ([C)V
 */
JNIEXPORT jboolean JNICALL Java_gmail_hackwaly_nc1020_NC1020_1JNI_CopyLcdBuffer
  (JNIEnv *env, jobject, jbyteArray buffer) {
	jbyte* pBuffer = env->GetByteArrayElements(buffer, NULL);
	jboolean result = CopyLcdBuffer((uint8_t*)pBuffer);
	env->ReleaseByteArrayElements(buffer, pBuffer, 0);
	return result;
}

#ifdef __cplusplus
}
#endif
