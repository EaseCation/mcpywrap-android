package dev.mcpy.game;
public final class NativeBridge {
    static { System.loadLibrary("mcpy_bridge"); }
    public static native void setWorkingDirectory(String path);
    public static native void dispatch(Object activity,String json,Object callback);
    public static native void initialize(String library);
}
