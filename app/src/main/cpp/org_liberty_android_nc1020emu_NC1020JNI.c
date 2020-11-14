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

/**
 * Copy the LCD buffer and convert to Android bitmap format
 *
 * @param env JNIEnv
 * @param type java class
 * @param buffer The buffer is size of byte[1600 * 8]
 * @return True if buffer is copied. False if buffer is not copied
 */
JNIEXPORT jboolean JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_copyLcdBufferEx
        (JNIEnv *env, jclass type, jbyteArray buffer) {
    jbyte* buffer_ex= (*env)->GetByteArrayElements(env, buffer, NULL);

    uint8_t* lcd_buffer = get_lcd_buffer();

    if (lcd_buffer == NULL)
        return false;

    for (int y = 0; y < 80; y++) {
        for (int j = 0; j < 20; j++) {
            uint8_t p = lcd_buffer[20 * y + j];
            for (int k = 0; k < 8; k++) {
                buffer_ex[y * 160 + j * 8 + k] = ((p & (1u << (7u - k))) != 0 ? 0xFFu : 0x00);
            }
        }
    }

    for (int y = 0; y < 80; y++) {
        buffer_ex[y * 160] = 0;
    }


    (*env)->ReleaseByteArrayElements(env, buffer, buffer_ex, 0);
    return true;
}

JNIEXPORT jlong JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_getCycles(JNIEnv *env, jclass type) {
    return get_cycles();
}
