#include "org_liberty_android_nc1020emu_NC1020JNI.h"
#include "./wqx/nc1020.h"
#include <string.h>

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    initialize
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_initialize
        (JNIEnv *env, jclass type, jstring path) {
    const char* path1 = (*env)->GetStringUTFChars(env, path, NULL);
    initialize(path1);
    (*env)->ReleaseStringUTFChars(env, path, path1);
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    reset
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_reset
        (JNIEnv *env, jclass type) {
    reset();
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    load
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_load
        (JNIEnv *env, jclass type) {
    load_nc1020();
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    save
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_save
        (JNIEnv *env, jclass type) {
    save_nc1020();
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    set_key
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_setKey
        (JNIEnv *env, jclass type, jint keyId, jboolean downOrUp) {
    set_key((uint8_t) (keyId & 0x3F), downOrUp);
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    run_time_slice
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_runTimeSlice
        (JNIEnv *env, jclass type, jint timeSlice, jboolean speedUp) {
    run_time_slice((size_t) timeSlice, speedUp);
}

/*
 * Class:     org_liberty_android_nc1020emu_NC1020_JNI
 * Method:    copy_lcd_buffer
 * Signature: ([C)V
 */
JNIEXPORT jboolean JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_copyLcdBuffer
        (JNIEnv *env, jclass type, jbyteArray buffer) {
    jbyte* pBuffer = (*env)->GetByteArrayElements(env, buffer, NULL);
    jboolean result = copy_lcd_buffer((uint8_t *) pBuffer);
    (*env)->ReleaseByteArrayElements(env, buffer, pBuffer, 0);
    return result;
}

JNIEXPORT jlong JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_getCycles(JNIEnv *env, jclass type) {
    return get_cycles();
}