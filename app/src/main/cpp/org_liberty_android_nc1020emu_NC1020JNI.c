#include "./wqx/nc1020.h"
#include "android_audio.h"
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
 * Copy the 159x80 dot-matrix LCD pixels (columns 1..159) and 80 status segments (column 0).
 *
 * @param env JNIEnv
 * @param type java class
 * @param pixelBuffer ByteArray of size >= 159 * 80 for ALPHA_8 dot-matrix pixels
 * @param segmentBuffer ByteArray of size >= 80 for left/right status segment states (0 or 1)
 * @return True if copied, false otherwise
 */
JNIEXPORT jboolean JNICALL Java_org_liberty_android_nc1020emu_NC1020JNI_copyLcdBufferEx
        (JNIEnv *env, jclass type, jbyteArray pixelBuffer, jbyteArray segmentBuffer) {
    (void) type;
    if (pixelBuffer == NULL || segmentBuffer == NULL) {
        return JNI_FALSE;
    }
    if ((*env)->GetArrayLength(env, pixelBuffer) < 159 * 80 ||
        (*env)->GetArrayLength(env, segmentBuffer) < 80) {
        return JNI_FALSE;
    }

    uint8_t *lcd_buffer = get_lcd_buffer();
    if (lcd_buffer == NULL) {
        return JNI_FALSE;
    }

    jbyte *pixels_out = (*env)->GetByteArrayElements(env, pixelBuffer, NULL);
    if (pixels_out == NULL) {
        return JNI_FALSE;
    }
    jbyte *segments_out = (*env)->GetByteArrayElements(env, segmentBuffer, NULL);
    if (segments_out == NULL) {
        (*env)->ReleaseByteArrayElements(env, pixelBuffer, pixels_out, JNI_ABORT);
        return JNI_FALSE;
    }

    for (int y = 0; y < 80; y++) {
        const uint8_t *row = lcd_buffer + (20 * y);
        jbyte *dst_row = pixels_out + (159 * y);

        segments_out[y] = (row[0] & 0x80u) != 0 ? (jbyte) 1 : (jbyte) 0;

        uint8_t first = row[0];
        for (int k = 1; k < 8; k++) {
            dst_row[k - 1] = (first & (1u << (7u - k))) != 0 ? (jbyte) 0xFF : (jbyte) 0x00;
        }
        for (int j = 1; j < 20; j++) {
            uint8_t p = row[j];
            int base = j * 8 - 1;
            for (int k = 0; k < 8; k++) {
                dst_row[base + k] = (p & (1u << (7u - k))) != 0 ? (jbyte) 0xFF : (jbyte) 0x00;
            }
        }
    }

    (*env)->ReleaseByteArrayElements(env, segmentBuffer, segments_out, 0);
    (*env)->ReleaseByteArrayElements(env, pixelBuffer, pixels_out, 0);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_getCycles(JNIEnv *env, jclass type) {
    return get_cycles();
}

JNIEXPORT jint JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_readAudioSamples(JNIEnv *env, jclass type,
                                                              jshortArray buffer) {
    if (buffer == NULL) {
        return 0;
    }
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (capacity <= 0) {
        return 0;
    }
    jshort *elements = (*env)->GetShortArrayElements(env, buffer, NULL);
    if (elements == NULL) {
        return 0;
    }
    size_t count = read_nc1020_audio((int16_t *) elements, (size_t) capacity);
    (*env)->ReleaseShortArrayElements(env, buffer, elements, count > 0 ? 0 : JNI_ABORT);
    return (jint) count;
}

JNIEXPORT void JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_startAudio(JNIEnv *env, jclass type) {
    (void) env;
    (void) type;
    android_audio_start();
}

JNIEXPORT void JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_stopAudio(JNIEnv *env, jclass type) {
    (void) env;
    (void) type;
    android_audio_stop();
}

JNIEXPORT void JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_setAudioEnabled(JNIEnv *env, jclass type,
                                                             jboolean enabled) {
    (void) env;
    (void) type;
    android_audio_set_enabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_syncTime(JNIEnv *env, jclass type,
                                                      jint year, jint month, jint day, jint wday,
                                                      jint hour, jint minute, jint second,
                                                      jint tzOffsetSec) {
    (void) env;
    (void) type;
    sync_nc1020_time((int) year, (int) month, (int) day, (int) wday,
                     (int) hour, (int) minute, (int) second, (int32_t) tzOffsetSec);
}
