package com.google.androidgamesdk.gametextinput;

import android.view.inputmethod.EditorInfo;

public final class Settings {
    EditorInfo mEditorInfo;
    boolean mForwardKeyEvents;

    public Settings(EditorInfo editorInfo, boolean z) {
        this.mEditorInfo = editorInfo;
        this.mForwardKeyEvents = z;
    }
}