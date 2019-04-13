#include "org_liberty_android_nc1020emu_NC1020JNI.h"
#include "./wqx/nc1020.h"
#include <string.h>

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    Initialize
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_Initialize
        (JNIEnv *env, jobject obj, jstring path) {
    const char* path1 = (*env)->GetStringUTFChars(env, path, NULL);
    Initialize(path1);
    (*env)->ReleaseStringUTFChars(env, path, path1);
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    Reset
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_Reset
        (JNIEnv *env, jobject obj) {
    Reset();
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    Load
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_Load
        (JNIEnv *env, jobject obj) {
    LoadNC1020();
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    Save
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_Save
        (JNIEnv *env, jobject obj) {
    SaveNC1020();
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    SetKey
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_SetKey
        (JNIEnv *env, jobject obj, jint keyId, jboolean downOrUp) {
    SetKey((uint8_t)(keyId & 0x3F), downOrUp);
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    RunTimeSlice
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_RunTimeSlice
        (JNIEnv *env, jobject obj, jint timeSlice, jboolean speedUp) {
    RunTimeSlice((size_t)timeSlice, speedUp);
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    CopyLcdBuffer
 * Signature: ([C)V
 */
JNIEXPORT jboolean JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_CopyLcdBuffer
        (JNIEnv *env, jobject obj, jbyteArray buffer) {
    jbyte* pBuffer = (*env)->GetByteArrayElements(env, buffer, NULL);
    jboolean result = CopyLcdBuffer((uint8_t*)pBuffer);
    (*env)->ReleaseByteArrayElements(env, buffer, pBuffer, 0);
    return result;
}

JNIEXPORT jlong JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_GetCycles(JNIEnv *env, jclass type) {
    return GetCycles();
}