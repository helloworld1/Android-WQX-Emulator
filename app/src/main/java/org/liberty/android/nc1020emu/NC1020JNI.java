package org.liberty.android.nc1020emu;

public class NC1020JNI {
	public static native void initialize(String path);
	public static native void reset();
	public static native void load();
	public static native void save();
	public static native void setKey(int keyId, boolean downOrUp);
	public static native void runTimeSlice(int timeSlice, boolean speedUp);
	public static native boolean copyLcdBuffer(byte[] buffer);
	public static native long getCycles();
	static {
		System.loadLibrary("nc1020");
	}
}
