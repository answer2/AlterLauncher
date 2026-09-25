package com.google.androidgamesdk;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.androidgamesdk.gametextinput.*;

import dalvik.system.BaseDexClassLoader;

import java.io.File;

public class GameActivity extends Activity implements
        SurfaceHolder.Callback2,
        Listener,
        OnApplyWindowInsetsListener,
        ViewTreeObserver.OnGlobalLayoutListener {

    private static final String DEFAULT_NATIVE_LIB_NAME = "main";
    private static final String KEY_NATIVE_SAVED_STATE = "android:native_state";
    private static final String LOG_TAG = "GameActivity";
    public static final String META_DATA_LIB_NAME = "android.app.lib_name";

    protected int contentViewId;
    private EditorInfo imeEditorInfo;
    private SurfaceHolder mCurSurfaceHolder;
    protected boolean mDestroyed;
    protected int mLastContentWidth, mLastContentHeight, mLastContentX, mLastContentY;
    private long mNativeHandle;
    protected InputEnabledSurfaceView mSurfaceView;
    private boolean softwareKeyboardVisible = false;
    protected final int[] mLocation = new int[2];

    /** SurfaceView with InputConnection support */
    protected class InputEnabledSurfaceView extends SurfaceView {
        InputConnection mInputConnection;

        public InputEnabledSurfaceView(GameActivity owner) {
            super(owner);
            EditorInfo info = owner.getImeEditorInfo();
            this.mInputConnection =
                    new InputConnection(owner, this, new Settings(info, info.inputType == 0))
                            .setListener(owner);
        }

        public EditorInfo getEditorInfo() {
            return mInputConnection.getEditorInfo();
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (mInputConnection.getSoftKeyboardActive()) {
                if (outAttrs != null) {
                    GameTextInput.copyEditorInfo(mInputConnection.getEditorInfo(), outAttrs);
                }
                return mInputConnection;
            }
            return null;
        }

        public void setEditorInfo(EditorInfo editorInfo) {
            mInputConnection.setEditorInfo(editorInfo);
        }
    }

    /* ------------------- Helpers ------------------- */

    private static String absPath(File f) {
        return f != null ? f.getAbsolutePath() : null;
    }

    private boolean isKeyboardVisible(WindowInsetsCompat insets) {
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    protected InputEnabledSurfaceView createSurfaceView() {
        return new InputEnabledSurfaceView(this);
    }

    /* ------------------- Native declarations ------------------- */
    protected native String getDlError();
    protected native long initializeNativeCode(String files, String obb, String ext,
                                               AssetManager am, byte[] state,
                                               Configuration config);
    protected native void terminateNativeCode(long handle);
    protected native void onConfigurationChangedNative(long handle, Configuration config);
    protected native void onContentRectChangedNative(long handle, int x, int y, int w, int h);
    protected native void onEditorActionNative(long handle, int actionId);
    protected native boolean onKeyDownNative(long handle, KeyEvent event);
    protected native boolean onKeyUpNative(long handle, KeyEvent event);
    protected native void onPauseNative(long handle);
    protected native void onResumeNative(long handle);
    protected native byte[] onSaveInstanceStateNative(long handle);
    protected native void onSoftwareKeyboardVisibilityChangedNative(long handle, boolean visible);
    protected native void onStartNative(long handle);
    protected native void onStopNative(long handle);
    protected native void onSurfaceChangedNative(long handle, Surface surface, int fmt, int w, int h);
    protected native void onSurfaceCreatedNative(long handle, Surface surface);
    protected native void onSurfaceDestroyedNative(long handle);
    protected native void onSurfaceRedrawNeededNative(long handle, Surface surface);
    protected native void onTextInputEventNative(long handle, State state);
    protected native boolean onTouchEventNative(long handle, MotionEvent ev,
                                                int pointers, int history,
                                                int deviceId, int source, int action,
                                                long eventTime, long downTime,
                                                int flags, int metaState,
                                                int actionBtn, int btnState,
                                                int classification, int edgeFlags,
                                                float xPrec, float yPrec);
    protected native void onTrimMemoryNative(long handle, int level);
    protected native void onWindowFocusChangedNative(long handle, boolean hasFocus);
    protected native void onWindowInsetsChangedNative(long handle);
    protected native void setInputConnectionNative(long handle, InputConnection conn);

    /* ------------------- Lifecycle ------------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        onCreateSurfaceView();
        if (mSurfaceView != null) {
            mSurfaceView.getViewTreeObserver().addOnGlobalLayoutListener(this);
        }
        onSetUpWindow();

        String libName = DEFAULT_NATIVE_LIB_NAME;
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getIntent().getComponent(), 128);
            if (info.metaData != null) {
                String v = info.metaData.getString(META_DATA_LIB_NAME);
                if (v != null) libName = v;
            }
            String soName =  libName;
            Log.i(LOG_TAG, "Looking for library " + soName);

            BaseDexClassLoader cl = (BaseDexClassLoader) getClassLoader();
            String found = cl.findLibrary(libName);
            System.out.println(found);
            if (found != null) {
                Log.i(LOG_TAG, "Found " + soName + ". Loading...");
                System.loadLibrary(libName);
            } else if (!libName.equals(DEFAULT_NATIVE_LIB_NAME)) {
                throw new IllegalArgumentException("Unable to find native library " + soName +
                        " with classloader: " + cl);
            } else {
                Log.i(LOG_TAG, "Application should have loaded " + soName + " explicitly.");
            }

            byte[] state = savedInstanceState != null
                    ? savedInstanceState.getByteArray(KEY_NATIVE_SAVED_STATE) : null;
            File ext = null;
            File[] extDirs = getExternalFilesDirs(null);
            if (extDirs != null && extDirs.length > 0) ext = extDirs[0];

            mNativeHandle = initializeNativeCode(
                    absPath(getFilesDir()), absPath(getObbDir()), absPath(ext),
                    getAssets(), state, getResources().getConfiguration());

            if (mNativeHandle == 0) {
                throw new UnsatisfiedLinkError("Unable to init native code: " + getDlError());
            }

            if (mSurfaceView != null) {
                setInputConnectionNative(mNativeHandle, mSurfaceView.mInputConnection);
            }

            super.onCreate(savedInstanceState);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Error getting activity info", e);
        }
    }

    protected void onCreateSurfaceView() {
        mSurfaceView = createSurfaceView();
        if (mSurfaceView == null) return;

        FrameLayout frame = new FrameLayout(this);
        contentViewId = ViewCompat.generateViewId();
        frame.setId(contentViewId);
        frame.addView(mSurfaceView);
        setContentView(frame);
        frame.requestFocus();

        mSurfaceView.getHolder().addCallback(this);
        ViewCompat.setOnApplyWindowInsetsListener(mSurfaceView, this);
    }

    protected void onSetUpWindow() {
        getWindow().setFormat(PixelFormat.RGBA_8888);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    @Override protected void onDestroy() {
        mDestroyed = true;
        if (mCurSurfaceHolder != null) {
            onSurfaceDestroyedNative(mNativeHandle);
            mCurSurfaceHolder = null;
        }
        terminateNativeCode(mNativeHandle);
        super.onDestroy();
    }

    /* ------------------- Input / Insets ------------------- */

    @Override public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
        onImeInsetsChanged(insets.getInsets(WindowInsetsCompat.Type.ime()));

        boolean visible = isKeyboardVisible(insets);
        if (visible != softwareKeyboardVisible) {
            softwareKeyboardVisible = visible;
            onSoftwareKeyboardVisibilityChanged(visible);
        }

        onWindowInsetsChangedNative(mNativeHandle);
        v.onApplyWindowInsets(insets.toWindowInsets());
        return insets;
    }

    @Override public void onGlobalLayout() {
        mSurfaceView.getLocationInWindow(mLocation);
        int w = mSurfaceView.getWidth(), h = mSurfaceView.getHeight();
        int x = mLocation[0], y = mLocation[1];

        if (x == mLastContentX && y == mLastContentY &&
                w == mLastContentWidth && h == mLastContentHeight) return;

        mLastContentX = x; mLastContentY = y;
        mLastContentWidth = w; mLastContentHeight = h;

        if (!mDestroyed) onContentRectChangedNative(mNativeHandle, x, y, w, h);
    }

    @Override public void onImeInsetsChanged(android.graphics.Insets insets) {}
    public void onImeInsetsChanged(Insets insets) {
        Log.v(LOG_TAG, "onImeInsetsChanged from Text Listener");
    }

    @Override public void onEditorAction(int id) {
        onEditorActionNative(mNativeHandle, id);
    }

    @Override public void onSoftwareKeyboardVisibilityChanged(boolean v) {
        onSoftwareKeyboardVisibilityChangedNative(mNativeHandle, v);
    }

    @Override public void stateChanged(State state, boolean b) {
        onTextInputEventNative(mNativeHandle, state);
    }

    /* ------------------- Event Forwarding ------------------- */

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        return onKeyDownNative(mNativeHandle, e) || super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        return onKeyUpNative(mNativeHandle, e) || super.onKeyUp(code, e);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        return processMotionEvent(e) || super.onGenericMotionEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        return processMotionEvent(e) || super.onTouchEvent(e);
    }

    protected boolean processMotionEvent(MotionEvent e) {
        return onTouchEventNative(mNativeHandle, e,
                e.getPointerCount(), e.getHistorySize(),
                e.getDeviceId(), e.getSource(), e.getAction(),
                e.getEventTime(), e.getDownTime(),
                e.getFlags(), e.getMetaState(),
                e.getActionButton(), e.getButtonState(),
                Build.VERSION.SDK_INT >= 29 ? e.getClassification() : 0,
                e.getEdgeFlags(),
                e.getXPrecision(), e.getYPrecision());
    }

    /* ------------------- Surface callbacks ------------------- */

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hgt) {
        if (mDestroyed) return;
        mCurSurfaceHolder = h;
        onSurfaceChangedNative(mNativeHandle, h.getSurface(), f, w, hgt);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (mDestroyed) return;
        mCurSurfaceHolder = h;
        onSurfaceCreatedNative(mNativeHandle, h.getSurface());
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        mCurSurfaceHolder = null;
        if (!mDestroyed) onSurfaceDestroyedNative(mNativeHandle);
    }

    @Override public void surfaceRedrawNeeded(SurfaceHolder h) {
        if (mDestroyed) return;
        mCurSurfaceHolder = h;
        onSurfaceRedrawNeededNative(mNativeHandle, h.getSurface());
    }

    /* ------------------- Other lifecycle hooks ------------------- */

    @Override protected void onPause() { super.onPause(); onPauseNative(mNativeHandle); }
    @Override protected void onResume() { super.onResume(); onResumeNative(mNativeHandle); }
    @Override protected void onStart() { super.onStart(); onStartNative(mNativeHandle); }
    @Override protected void onStop() { super.onStop(); onStopNative(mNativeHandle); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        byte[] state = onSaveInstanceStateNative(mNativeHandle);
        if (state != null) out.putByteArray(KEY_NATIVE_SAVED_STATE, state);
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (!mDestroyed) onConfigurationChangedNative(mNativeHandle, cfg);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!mDestroyed) onTrimMemoryNative(mNativeHandle, level);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!mDestroyed) onWindowFocusChangedNative(mNativeHandle, hasFocus);
    }

    /* ------------------- Text Input Helpers ------------------- */

    public EditorInfo getImeEditorInfo() {
        if (imeEditorInfo == null) {
            imeEditorInfo = new EditorInfo();
            imeEditorInfo.inputType = 1;
            imeEditorInfo.actionId = 6;
            imeEditorInfo.imeOptions = 33554438;
        }
        return imeEditorInfo;
    }

    public void setImeEditorInfo(EditorInfo e) {
        imeEditorInfo = e;
        if (mSurfaceView != null) mSurfaceView.mInputConnection.setEditorInfo(e);
    }

    public void setImeEditorInfoFields(int type, int actionId, int opts) {
        EditorInfo e = getImeEditorInfo();
        e.inputType = type;
        e.actionId = actionId;
        e.imeOptions = opts;
        if (mSurfaceView != null) mSurfaceView.mInputConnection.setEditorInfo(e);
    }

    public void setTextInputState(State state) {
        if (mSurfaceView != null && mSurfaceView.mInputConnection != null) {
            mSurfaceView.mInputConnection.setState(state);
        } else {
            Log.w(LOG_TAG, "No input connection has been set yet");
        }
    }

    /* ------------------- Window helpers ------------------- */
    void setWindowFlags(int flags, int mask) { getWindow().setFlags(flags, mask); }
    void setWindowFormat(int format) { getWindow().setFormat(format); }

    public Insets getWindowInsets(int i) {
        Insets insets;
        WindowInsetsCompat rootWindowInsets = ViewCompat.getRootWindowInsets(this.mSurfaceView);
        if (rootWindowInsets == null || (insets = rootWindowInsets.getInsets(i)) == Insets.NONE) {
            return null;
        }
        return insets;
    }

    public Insets getWaterfallInsets() {
        DisplayCutoutCompat displayCutout;
        WindowInsetsCompat rootWindowInsets = ViewCompat.getRootWindowInsets(this.mSurfaceView);
        if (rootWindowInsets == null || (displayCutout = rootWindowInsets.getDisplayCutout()) == null) {
            return null;
        }
        return displayCutout.getWaterfallInsets();
    }
}
