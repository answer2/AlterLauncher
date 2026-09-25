package com.google.androidgamesdk.gametextinput;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.RequiresApi;

import com.google.androidgamesdk.gametextinput.GameTextInput;


public class InputConnection extends BaseInputConnection implements View.OnKeyListener {
    private static int INVALID_INDEX = -1;
    private static final int MAX_LENGTH_FOR_SINGLE_LINE_EDIT_TEXT = 5000;
    private static final String TAG = "gti.InputConnection";
    private final InputMethodManager imm;
    private Listener listener;
    private final Editable mEditable;
    private boolean mSoftKeyboardActive;
    private final Settings settings;
    private final View targetView;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: 2025080606243090b9e23f-545a-4f04-9627-3c76ef454b21.jar:com/google/androidgamesdk/gametextinput/InputConnection$SingeLineFilter.class */
    public class SingeLineFilter implements InputFilter {
        private final InputConnection this$0;

        public SingeLineFilter(InputConnection inputConnection) {
            this.this$0 = inputConnection;
        }

        @Override // android.text.InputFilter
        public CharSequence filter(CharSequence charSequence, int i, int i2, Spanned spanned, int i3, int i4) {
            StringBuilder sb = new StringBuilder(i2 - i);
            boolean z = true;
            for (int i5 = i; i5 < i2; i5++) {
                char charAt = charSequence.charAt(i5);
                if (charAt == '\n') {
                    z = false;
                } else {
                    sb.append(charAt);
                }
            }
            if (z) {
                return null;
            }
            if (charSequence instanceof Spanned) {
                SpannableString spannableString = new SpannableString(sb);
                TextUtils.copySpansFrom((Spanned) charSequence, i, sb.length(), null, spannableString, 0);
                return spannableString;
            }
            return sb;
        }
    }

    public InputConnection(Context context, View view, Settings settings) {
        super(view, settings.mEditorInfo.inputType != 0);
        Log.d(TAG, "InputConnection created");
        this.targetView = view;
        this.settings = settings;
        @SuppressLint("WrongConstant") Object systemService = context.getSystemService("input_method");
        if (systemService == null) {
            throw new RuntimeException("Can't get IMM");
        }
        this.imm = (InputMethodManager) systemService;
        this.mEditable = new SpannableStringBuilder();
        Context context2 = view.getContext();
        if (context2 instanceof Activity) {
            Window window = ((Activity) context2).getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
            } else {
                View decorView = window.getDecorView();
                decorView.setSystemUiVisibility(decorView.getSystemUiVisibility() | 1792);
            }
        }
        view.setOnKeyListener(this);
        setEditorInfo(settings.mEditorInfo);
    }

    private static int findIndexBackward(CharSequence charSequence, int i, int i2) {
        int length = charSequence.length();
        if (i < 0 || length < i) {
            return INVALID_INDEX;
        }
        if (i2 < 0) {
            return INVALID_INDEX;
        }
        while (true) {
            boolean z = false;
            while (i2 != 0) {
                i--;
                if (i < 0) {
                    if (z) {
                        return INVALID_INDEX;
                    }
                    return 0;
                }
                char charAt = charSequence.charAt(i);
                if (z) {
                    if (!Character.isHighSurrogate(charAt)) {
                        return INVALID_INDEX;
                    }
                    i2--;
                } else if (!Character.isSurrogate(charAt)) {
                    i2--;
                } else if (Character.isHighSurrogate(charAt)) {
                    return INVALID_INDEX;
                } else {
                    z = true;
                }
            }
            return i;
        }
    }

    private static int findIndexForward(CharSequence charSequence, int i, int i2) {
        int length = charSequence.length();
        if (i < 0 || length < i) {
            return INVALID_INDEX;
        }
        if (i2 < 0) {
            return INVALID_INDEX;
        }
        while (true) {
            boolean z = false;
            while (i2 != 0) {
                if (i >= length) {
                    return z ? INVALID_INDEX : length;
                }
                char charAt = charSequence.charAt(i);
                if (z) {
                    if (!Character.isLowSurrogate(charAt)) {
                        return INVALID_INDEX;
                    }
                    i2--;
                    i++;
                } else if (!Character.isSurrogate(charAt)) {
                    i2--;
                    i++;
                } else if (Character.isLowSurrogate(charAt)) {
                    return INVALID_INDEX;
                } else {
                    i++;
                    z = true;
                }
            }
            return i;
        }
    }

    private GameTextInput.Pair getComposingRegion() {
        return new GameTextInput.Pair(BaseInputConnection.getComposingSpanStart(this.mEditable), BaseInputConnection.getComposingSpanEnd(this.mEditable));
    }

    private GameTextInput.Pair getSelection() {
        return new GameTextInput.Pair(Selection.getSelectionStart(this.mEditable), Selection.getSelectionEnd(this.mEditable));
    }

    private void immUpdateSelection() {
        GameTextInput.Pair selection = getSelection();
        GameTextInput.Pair composingRegion = getComposingRegion();
        Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer("immUpdateSelection: ").append(selection.first).toString()).append(",").toString()).append(selection.second).toString()).append(". ").toString()).append(composingRegion.first).toString()).append(",").toString()).append(composingRegion.second).toString());
        this.settings.mEditorInfo.initialSelStart = selection.first;
        this.settings.mEditorInfo.initialSelEnd = selection.second;
        this.imm.updateSelection(this.targetView, selection.first, selection.second, composingRegion.first, composingRegion.second);
    }

    private boolean processKeyEvent(KeyEvent keyEvent) {
        if (keyEvent == null) {
            return false;
        }
        int keyCode = keyEvent.getKeyCode();
        Log.d(TAG, String.format("processKeyEvent(key=%d) text=%s", new Integer(keyCode), this.mEditable.toString()));
        if ((this.settings.mEditorInfo.inputType & 131072) == 0 && ((keyCode == 66 || keyCode == 160) && keyEvent.hasNoModifiers())) {
            sendEditorAction(this.settings.mEditorInfo.actionId);
            return true;
        } else if (keyEvent.getAction() != 0) {
            return false;
        } else {
            GameTextInput.Pair selection = getSelection();
            if (selection.first == -1) {
                selection.first = this.mEditable.length();
                selection.second = this.mEditable.length();
            }
            if (keyCode == 21) {
                if (selection.first != selection.second) {
                    setSelection(selection.first, selection.first);
                    return true;
                }
                int findIndexBackward = findIndexBackward(this.mEditable, selection.first, 1);
                setSelection(findIndexBackward, findIndexBackward);
                return true;
            } else if (keyCode == 22) {
                if (selection.first != selection.second) {
                    setSelection(selection.second, selection.second);
                    return true;
                }
                int findIndexForward = findIndexForward(this.mEditable, selection.second, 1);
                setSelection(findIndexForward, findIndexForward);
                return true;
            } else if (keyCode == 122) {
                setSelection(0, 0);
                return true;
            } else if (keyCode == 123) {
                setSelection(this.mEditable.length(), this.mEditable.length());
                return true;
            } else if (keyCode == 67 || keyCode == 112) {
                if (selection.first != selection.second) {
                    this.mEditable.delete(selection.first, selection.second);
                    return true;
                } else if (keyCode == 67 && selection.first > 0) {
                    finishComposingText();
                    deleteSurroundingTextInCodePoints(1, 0);
                    return true;
                } else if (keyCode != 112 || selection.first >= this.mEditable.length()) {
                    return false;
                } else {
                    finishComposingText();
                    deleteSurroundingTextInCodePoints(0, 1);
                    return true;
                }
            } else if (keyEvent.getUnicodeChar() == 0) {
                return false;
            } else {
                if (selection.first != selection.second) {
                    Log.d(TAG, String.format("processKeyEvent: deleting selection", new Object[0]));
                    this.mEditable.delete(selection.first, selection.second);
                }
                String ch = Character.toString((char) keyEvent.getUnicodeChar());
                this.mEditable.insert(selection.first, ch);
                int length = this.mEditable.length();
                GameTextInput.Pair composingRegion = getComposingRegion();
                GameTextInput.Pair pair = composingRegion;
                if (composingRegion.first == -1) {
                    GameTextInput.Pair selection2 = getSelection();
                    pair = selection2;
                    if (selection2.first == -1) {
                        pair = new GameTextInput.Pair(0, 0);
                    }
                }
                pair.second = pair.first + length;
                setComposingRegion(pair.first, pair.second);
                int length2 = selection.first + ch.length();
                setSelection(length2, length2);
                Log.d(TAG, String.format("processKeyEvent: exit, text=%s", this.mEditable.toString()));
                return true;
            }
        }
    }

    private boolean sendEditorAction(int i) {
        Listener listener = this.listener;
        if (listener != null) {
            listener.onEditorAction(i);
            return true;
        }
        return false;
    }

    private final void stateUpdated() {
        GameTextInput.Pair selection = getSelection();
        GameTextInput.Pair composingRegion = getComposingRegion();
        State state = new State(this.mEditable.toString(), selection.first, selection.second, composingRegion.first, composingRegion.second);
        this.settings.mEditorInfo.initialSelStart = selection.first;
        this.settings.mEditorInfo.initialSelEnd = selection.second;
        Listener listener = this.listener;
        if (listener != null) {
            listener.stateChanged(state, false);
        }
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public void closeConnection() {
        Log.d(TAG, "closeConnection");
        super.closeConnection();
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean commitCompletion(CompletionInfo completionInfo) {
        Log.d(TAG, "commitCompletion");
        return super.commitCompletion(completionInfo);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        Log.d(TAG, "commitCompletion");
        return super.commitCorrection(correctionInfo);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean commitText(CharSequence charSequence, int i) {
        Log.d(TAG, "commitText: " + charSequence + ", new pos = " + i);
        return super.commitText(charSequence, i);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean deleteSurroundingText(int i, int i2) {
        //Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("deleteSurroundingText: ").append(i).toString()).append(TreeNode.NODES_ID_SEPARATOR).toString()).append(i2).toString());
        return super.deleteSurroundingText(i, i2);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean deleteSurroundingTextInCodePoints(int i, int i2) {
        //Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("deleteSurroundingTextInCodePoints: ").append(i).toString()).append(TreeNode.NODES_ID_SEPARATOR).toString()).append(i2).toString());
        return super.deleteSurroundingTextInCodePoints(i, i2);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean endBatchEdit() {
        Log.d(TAG, "endBatchEdit");
        stateUpdated();
        return super.endBatchEdit();
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean finishComposingText() {
        Log.d(TAG, "finishComposingText");
        return super.finishComposingText();
    }

    @Override // android.view.inputmethod.BaseInputConnection
    public Editable getEditable() {
        Log.d(TAG, "getEditable");
        return this.mEditable;
    }

    public final EditorInfo getEditorInfo() {
        return this.settings.mEditorInfo;
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public ExtractedText getExtractedText(ExtractedTextRequest extractedTextRequest, int i) {
        Log.d(TAG, "getExtractedText");
        return super.getExtractedText(extractedTextRequest, i);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public Insets getImeInsets() {
        WindowInsets rootWindowInsets;
        View view = this.targetView;
        if (view != null && (rootWindowInsets = view.getRootWindowInsets()) != null) {
            return rootWindowInsets.getInsets(WindowInsets.Type.ime());
        }
        return Insets.NONE;
    }

    public final Listener getListener() {
        return this.listener;
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public CharSequence getSelectedText(int i) {
        CharSequence selectedText = super.getSelectedText(i);
        CharSequence charSequence = selectedText;
        if (selectedText == null) {
            charSequence = "";
        }
        Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("getSelectedText: ").append(i).toString()).append(", result: ").toString()).append((Object) charSequence).toString());
        return charSequence;
    }

    public final boolean getSoftKeyboardActive() {
        return this.mSoftKeyboardActive;
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public CharSequence getTextAfterCursor(int i, int i2) {
        //Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("getTextAfterCursor: ").append(i).toString()).append(TreeNode.NODES_ID_SEPARATOR).toString()).append(i2).toString());
        if (i < 0) {
            Log.i(TAG, new StringBuffer("getTextAfterCursor: returning null to due to an invalid length=").append(i).toString());
            return null;
        }
        return super.getTextAfterCursor(i, i2);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public CharSequence getTextBeforeCursor(int i, int i2) {
        Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("getTextBeforeCursor: ").append(i).toString()).append(", flags=").toString()).append(i2).toString());
        if (i < 0) {
            Log.i(TAG, new StringBuffer("getTextBeforeCursor: returning null to due to an invalid length=").append(i).toString());
            return null;
        }
        return super.getTextBeforeCursor(i, i2);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public boolean isSoftwareKeyboardVisible() {
        WindowInsets rootWindowInsets;
        View view = this.targetView;
        if (view == null || (rootWindowInsets = view.getRootWindowInsets()) == null) {
            return false;
        }
        return rootWindowInsets.isVisible(WindowInsets.Type.ime());
    }

    @Override // android.view.View.OnKeyListener
    public boolean onKey(View view, int i, KeyEvent keyEvent) {
        Log.d(TAG, new StringBuffer("onKey: ").append(keyEvent).toString());
        if (getSoftKeyboardActive() && processKeyEvent(keyEvent)) {
            stateUpdated();
            immUpdateSelection();
            restartInput();
            return true;
        }
        return false;
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean performEditorAction(int i) {
        Log.d(TAG, new StringBuffer("performEditorAction, action=").append(i).toString());
        return i == 0 ? super.performEditorAction(i) : sendEditorAction(i);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean performPrivateCommand(String str, Bundle bundle) {
        Log.d(TAG, "performPrivateCommand");
        return super.performPrivateCommand(str, bundle);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean requestCursorUpdates(int i) {
        Log.d(TAG, new StringBuffer("Request cursor updates: ").append(i).toString());
        return super.requestCursorUpdates(i);
    }

    public void restartInput() {
        this.imm.restartInput(this.targetView);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean sendKeyEvent(KeyEvent keyEvent) {
        Log.d(TAG, new StringBuffer("sendKeyEvent: ").append(keyEvent).toString());
        return super.sendKeyEvent(keyEvent);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean setComposingRegion(int i, int i2) {
        //Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("setComposingRegion: ").append(i).toString()).append(TreeNode.NODES_ID_SEPARATOR).toString()).append(i2).toString());
        return super.setComposingRegion(i, i2);
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean setComposingText(CharSequence charSequence, int i) {
        Log.d(TAG, String.format("setComposingText='%s' newCursorPosition=%d", charSequence, new Integer(i)));
        if (charSequence == null) {
            return false;
        }
        return super.setComposingText(charSequence, i);
    }

    public final void setEditorInfo(EditorInfo editorInfo) {
        Log.d(TAG, "setEditorInfo");
        this.settings.mEditorInfo = editorInfo;
        if ((this.settings.mEditorInfo.inputType & 131072) == 0) {
            this.mEditable.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_LENGTH_FOR_SINGLE_LINE_EDIT_TEXT), new SingeLineFilter(this)});
        } else {
            this.mEditable.setFilters(new InputFilter[0]);
        }
    }

    @Override // android.view.inputmethod.InputConnection
    public boolean setImeConsumesInput(boolean z) {
        Log.d(TAG, new StringBuffer("setImeConsumesInput: ").append(z).toString());
        return super.setImeConsumesInput(z);
    }

    public final InputConnection setListener(Listener listener) {
        this.listener = listener;
        return this;
    }

    @Override // android.view.inputmethod.BaseInputConnection, android.view.inputmethod.InputConnection
    public boolean setSelection(int i, int i2) {
        //Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer("setSelection: ").append(i).toString()).append(TreeNode.NODES_ID_SEPARATOR).toString()).append(i2).toString());
        return super.setSelection(i, i2);
    }

    public final void setSoftKeyboardActive(boolean z, int i) {
        Log.d(TAG, new StringBuffer("setSoftKeyboardActive, active: ").append(z).toString());
        this.mSoftKeyboardActive = z;
        if (z) {
            this.targetView.setFocusableInTouchMode(true);
            this.targetView.requestFocus();
            this.imm.showSoftInput(this.targetView, i);
        } else {
            this.imm.hideSoftInputFromWindow(this.targetView.getWindowToken(), i);
        }
        restartInput();
    }

    public final void setState(State state) {
        if (state == null) {
            return;
        }
        Log.d(TAG, new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer().append(new StringBuffer("setState: '").append(state.text).toString()).append("', selection=(").toString()).append(state.selectionStart).toString()).append(",").toString()).append(state.selectionEnd).toString()).append("), composing region=(").toString()).append(state.composingRegionStart).toString()).append(",").toString()).append(state.composingRegionEnd).toString()).append(")").toString());
        this.mEditable.clear();
        this.mEditable.clearSpans();
        this.mEditable.insert(0, state.text);
        setSelection(state.selectionStart, state.selectionEnd);
        if (state.composingRegionStart != state.composingRegionEnd) {
            setComposingRegion(state.composingRegionStart, state.composingRegionEnd);
        }
        restartInput();
    }
}