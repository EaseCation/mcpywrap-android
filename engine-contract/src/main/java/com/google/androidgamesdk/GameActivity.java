package com.google.androidgamesdk;
/** Compile-only ABI declaration. The implementation comes from the user-downloaded APK. */
public class GameActivity extends android.app.Activity {
    protected native void onPauseNative(long handle);
    protected native void onContentRectChangedNative(long handle,int x,int y,int width,int height);
    protected native void onConfigurationChangedNative(long handle,android.content.res.Configuration configuration);
}
