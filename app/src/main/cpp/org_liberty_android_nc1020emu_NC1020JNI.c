#include "./wqx/nc1020.h"
#include "./wqx/nc1020_fs.h"
#include "android_audio.h"
#include <stdlib.h>
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

JNIEXPORT jint JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norGetInfo(JNIEnv *env, jclass type,
                                                        jintArray outStats) {
    (void) type;
    if (outStats == NULL || (*env)->GetArrayLength(env, outStats) < 9) {
        return NC1020_FS_ERR_CORRUPT;
    }
    nc1020_fs_info_t info;
    int rc = nc1020_fs_get_info(&info);
    if (rc != NC1020_FS_OK) {
        return (jint) rc;
    }
    jint stats[9];
    stats[0] = (jint) info.bat_block;
    stats[1] = (jint) info.root_block;
    stats[2] = (jint) info.total_blocks;
    stats[3] = (jint) info.reserved_blocks;
    stats[4] = (jint) info.used_blocks;
    stats[5] = (jint) info.free_blocks;
    stats[6] = (jint) info.dirty_blocks;
    stats[7] = (jint) info.wear_count;
    stats[8] = info.has_open_fcb ? 1 : 0;
    (*env)->SetIntArrayRegion(env, outStats, 0, 9, stats);
    return NC1020_FS_OK;
}

JNIEXPORT jbyteArray JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norListDir(JNIEnv *env, jclass type, jint dirInode) {
    (void) type;
    nc1020_fs_entry_t entries[NC1020_FS_MAX_DIR_ENTRIES];
    int count = 0;
    int rc = nc1020_fs_list_dir((uint16_t) dirInode, entries, (int) NC1020_FS_MAX_DIR_ENTRIES, &count);
    if (rc != NC1020_FS_OK) {
        return NULL;
    }
    if (count > (int) NC1020_FS_MAX_DIR_ENTRIES) {
        count = (int) NC1020_FS_MAX_DIR_ENTRIES;
    }
    jsize byteLen = (jsize) (count * 32);
    jbyteArray result = (*env)->NewByteArray(env, byteLen);
    if (result == NULL || byteLen == 0) {
        return result;
    }
    uint8_t packed[NC1020_FS_MAX_DIR_ENTRIES * 32u];
    memset(packed, 0, sizeof(packed));
    for (int i = 0; i < count; i++) {
        uint8_t *p = packed + ((size_t) i * 32u);
        const nc1020_fs_entry_t *e = &entries[i];
        memcpy(p, e->name_gbk, NC1020_FS_MAX_NAME_BYTES);
        p[16] = e->name_len;
        p[17] = e->attr;
        p[18] = e->attr2;
        p[19] = (uint8_t) (e->year & 0xFFu);
        p[20] = (uint8_t) ((e->year >> 8u) & 0xFFu);
        p[21] = e->month;
        p[22] = e->day;
        p[23] = (uint8_t) (e->size & 0xFFu);
        p[24] = (uint8_t) ((e->size >> 8u) & 0xFFu);
        p[25] = (uint8_t) ((e->size >> 16u) & 0xFFu);
        p[26] = (uint8_t) ((e->size >> 24u) & 0xFFu);
        p[27] = (uint8_t) (e->inode & 0xFFu);
        p[28] = (uint8_t) ((e->inode >> 8u) & 0xFFu);
        p[29] = (uint8_t) (e->blocks & 0xFFu);
        p[30] = (uint8_t) ((e->blocks >> 8u) & 0xFFu);
    }
    (*env)->SetByteArrayRegion(env, result, 0, byteLen, (const jbyte *) packed);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norReadFile(JNIEnv *env, jclass type,
                                                         jint inode, jint size) {
    (void) type;
    if (size < 0 || (uint32_t) size > NC1020_FS_NOR_SIZE) {
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result == NULL || size == 0) {
        return result;
    }
    uint8_t *buf = (uint8_t *) malloc((size_t) size);
    if (buf == NULL) {
        return NULL;
    }
    uint32_t readBytes = 0;
    int rc = nc1020_fs_read_file((uint16_t) inode, buf, (uint32_t) size, (uint32_t) size, &readBytes);
    if (rc != NC1020_FS_OK) {
        free(buf);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) readBytes, (const jbyte *) buf);
    free(buf);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norMkdir(JNIEnv *env, jclass type,
                                                      jint parentInode, jbyteArray nameGbk,
                                                      jboolean saveStates, jintArray outInode) {
    (void) type;
    if (nameGbk == NULL) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    jsize len = (*env)->GetArrayLength(env, nameGbk);
    if (len <= 0 || len > (jsize) NC1020_FS_MAX_NAME_BYTES) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    uint8_t nameBuf[NC1020_FS_MAX_NAME_BYTES];
    (*env)->GetByteArrayRegion(env, nameGbk, 0, len, (jbyte *) nameBuf);
    uint16_t createdInode = 0;
    int rc = nc1020_fs_mkdir((uint16_t) parentInode, nameBuf, (size_t) len,
                             saveStates == JNI_TRUE, &createdInode);
    if (outInode != NULL && (*env)->GetArrayLength(env, outInode) >= 1) {
        jint inoVal = (jint) createdInode;
        (*env)->SetIntArrayRegion(env, outInode, 0, 1, &inoVal);
    }
    return (jint) rc;
}

JNIEXPORT jint JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norWriteFile(JNIEnv *env, jclass type,
                                                          jint dirInode, jbyteArray nameGbk,
                                                          jbyteArray data, jboolean overwrite,
                                                          jboolean saveStates, jintArray outInode) {
    (void) type;
    if (nameGbk == NULL || data == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    jsize nameLen = (*env)->GetArrayLength(env, nameGbk);
    if (nameLen <= 0 || nameLen > (jsize) NC1020_FS_MAX_NAME_BYTES) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    uint8_t nameBuf[NC1020_FS_MAX_NAME_BYTES];
    (*env)->GetByteArrayRegion(env, nameGbk, 0, nameLen, (jbyte *) nameBuf);

    jsize dataLen = (*env)->GetArrayLength(env, data);
    if (dataLen < 0 || (uint32_t) dataLen > NC1020_FS_NOR_SIZE) {
        return NC1020_FS_ERR_NO_SPACE;
    }
    uint8_t *dataBuf = NULL;
    if (dataLen > 0) {
        dataBuf = (uint8_t *) malloc((size_t) dataLen);
        if (dataBuf == NULL) {
            return NC1020_FS_ERR_NO_SPACE;
        }
        (*env)->GetByteArrayRegion(env, data, 0, dataLen, (jbyte *) dataBuf);
    }

    uint16_t writtenInode = 0;
    int rc = nc1020_fs_write_file(
        (uint16_t) dirInode,
        nameBuf,
        (size_t) nameLen,
        dataBuf,
        (uint32_t) dataLen,
        overwrite == JNI_TRUE,
        saveStates == JNI_TRUE,
        &writtenInode
    );
    free(dataBuf);
    if (outInode != NULL && (*env)->GetArrayLength(env, outInode) >= 1) {
        jint inoVal = (jint) writtenInode;
        (*env)->SetIntArrayRegion(env, outInode, 0, 1, &inoVal);
    }
    return (jint) rc;
}

JNIEXPORT jint JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norDelete(JNIEnv *env, jclass type,
                                                       jint dirInode, jint targetInode,
                                                       jboolean saveStates) {
    (void) env;
    (void) type;
    return (jint) nc1020_fs_delete((uint16_t) dirInode, (uint16_t) targetInode, saveStates == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norRename(JNIEnv *env, jclass type,
                                                       jint dirInode, jint targetInode,
                                                       jbyteArray newNameGbk, jboolean saveStates) {
    (void) type;
    if (newNameGbk == NULL) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    jsize len = (*env)->GetArrayLength(env, newNameGbk);
    if (len <= 0 || len > (jsize) NC1020_FS_MAX_NAME_BYTES) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    uint8_t nameBuf[NC1020_FS_MAX_NAME_BYTES];
    (*env)->GetByteArrayRegion(env, newNameGbk, 0, len, (jbyte *) nameBuf);
    return (jint) nc1020_fs_rename((uint16_t) dirInode, (uint16_t) targetInode,
                                   nameBuf, (size_t) len, saveStates == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norExportImage(JNIEnv *env, jclass type) {
    (void) type;
    jbyteArray result = (*env)->NewByteArray(env, (jsize) NC1020_FS_NOR_SIZE);
    if (result == NULL) {
        return NULL;
    }
    uint8_t *buf = (uint8_t *) malloc(NC1020_FS_NOR_SIZE);
    if (buf == NULL) {
        return NULL;
    }
    if (!export_nor_raw(buf, NC1020_FS_NOR_SIZE)) {
        free(buf);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) NC1020_FS_NOR_SIZE, (const jbyte *) buf);
    free(buf);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_org_liberty_android_nc1020emu_NC1020JNI_norImportImage(JNIEnv *env, jclass type,
                                                            jbyteArray flsData,
                                                            jboolean saveStates) {
    (void) type;
    if (flsData == NULL || (*env)->GetArrayLength(env, flsData) != (jsize) NC1020_FS_NOR_SIZE) {
        return JNI_FALSE;
    }
    uint8_t *buf = (uint8_t *) malloc(NC1020_FS_NOR_SIZE);
    if (buf == NULL) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, flsData, 0, (jsize) NC1020_FS_NOR_SIZE, (jbyte *) buf);
    bool ok = import_nor_raw(buf, NC1020_FS_NOR_SIZE, saveStates == JNI_TRUE);
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}
