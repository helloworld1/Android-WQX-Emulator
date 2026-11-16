package org.liberty.android.nc1020emu

object NC1020JNI {
    @JvmStatic external fun initialize(romFilePath: String?, norFilePath: String?, stateFilePath: String?)
    @JvmStatic external fun reset()
    @JvmStatic external fun load()
    @JvmStatic external fun save()
    @JvmStatic external fun setKey(keyId: Int, downOrUp: Boolean)
    @JvmStatic external fun runTimeSlice(timeSlice: Int, speedUp: Boolean)
    @JvmStatic external fun copyLcdBufferEx(pixelBuffer: ByteArray?, segmentBuffer: ByteArray?): Boolean
    @JvmStatic external fun readAudioSamples(buffer: ShortArray?): Int
    @JvmStatic external fun startAudio()
    @JvmStatic external fun stopAudio()
    @JvmStatic external fun setAudioEnabled(enabled: Boolean)
    @JvmStatic external fun syncTime(
        year: Int,
        month: Int,
        day: Int,
        wday: Int,
        hour: Int,
        minute: Int,
        second: Int,
        tzOffsetSec: Int
    )
    @JvmStatic val cycles: Long external get
    @JvmStatic external fun norGetInfo(outStats: IntArray): Int
    @JvmStatic external fun norListDir(dirInode: Int): ByteArray?
    @JvmStatic external fun norReadFile(inode: Int, size: Int): ByteArray?
    @JvmStatic external fun norMkdir(
        parentInode: Int,
        nameGbk: ByteArray,
        saveStates: Boolean,
        outInode: IntArray?
    ): Int
    @JvmStatic external fun norWriteFile(
        dirInode: Int,
        nameGbk: ByteArray,
        data: ByteArray,
        overwrite: Boolean,
        saveStates: Boolean,
        outInode: IntArray?
    ): Int
    @JvmStatic external fun norDelete(dirInode: Int, targetInode: Int, saveStates: Boolean): Int
    @JvmStatic external fun norRename(
        dirInode: Int,
        targetInode: Int,
        newNameGbk: ByteArray,
        saveStates: Boolean
    ): Int
    @JvmStatic external fun norExportImage(): ByteArray?
    @JvmStatic external fun norImportImage(flsData: ByteArray, saveStates: Boolean): Boolean

    init {
        System.loadLibrary("nc1020")
    }
}