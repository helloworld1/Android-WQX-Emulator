#include "./wqx/nc1020.h"
#include <string.h>
#include <jni.h>

JNIEXPORT void JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_initialize(JNIEnv *env, jclass type,
                                                        jstring romFilePath_, jstring norFilePath_,
                                                        jstring stateFilePath_) {
    const char *romFilePath = (*env)->GetStringUTFChars(env, romFilePath_, 0);
    const char *norFilePath = (*env)->GetStringUTFChars(env, norFilePath_, 0);
    const char *stateFilePath = (*env)->GetStringUTFChars(env, stateFilePath_, 0);

    initialize(romFilePath, norFilePath, stateFilePath);

    (*env)->ReleaseStringUTFChars(env, romFilePath_, romFilePath);
    (*env)->ReleaseStringUTFChars(env, norFilePath_, norFilePath);
    (*env)->ReleaseStringUTFChars(env, stateFilePath_, stateFilePath);
}

JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_reset
        (JNIEnv *env, jclass type) {
    reset();
}

JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_load
        (JNIEnv *env, jclass type) {
    load_nc1020();
}

JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_save
        (JNIEnv *env, jclass type) {
    save_nc1020();
}

JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_setKey
        (JNIEnv *env, jclass type, jint keyId, jboolean downOrUp) {
    set_key((uint8_t) (keyId & 0x3F), downOrUp);
}

JNIEXPORT void JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_runTimeSlice
        (JNIEnv *env, jclass type, jint timeSlice, jboolean speedUp) {
    run_time_slice((size_t) timeSlice, speedUp);
}

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
