package org.liberty.android.nc1020emu;

public class NC1020JNI {
	public static native void Initialize(String path);
	public static native void Reset();
	public static native void Load();
	public static native void Save();
	public static native void SetKey(int keyId, boolean downOrUp);
	public static native void RunTimeSlice(int timeSlice, boolean speedUp);
	public static native boolean CopyLcdBuffer(byte[] buffer);
	public static native long GetCycles();
	static {
		System.loadLibrary("nc1020");
	}
}
