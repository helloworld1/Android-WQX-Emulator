package org.liberty.android.nc1020emu

object NC1020JNI {
    @JvmStatic external fun initialize(romFilePath: String?, norFilePath: String?, stateFilePath: String?)
    @JvmStatic external fun reset()
    @JvmStatic external fun load()
    @JvmStatic external fun save()
    @JvmStatic external fun setKey(keyId: Int, downOrUp: Boolean)
    @JvmStatic external fun runTimeSlice(timeSlice: Int, speedUp: Boolean)
    @JvmStatic external fun copyLcdBufferEx(buffer: ByteArray?): Boolean
    @JvmStatic val cycles: Long external get

    init {
        System.loadLibrary("nc1020")
    }
}