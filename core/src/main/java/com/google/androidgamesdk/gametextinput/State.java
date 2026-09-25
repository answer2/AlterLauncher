package com.google.androidgamesdk.gametextinput;

public final class State {
    public int composingRegionEnd;
    public int composingRegionStart;
    public int selectionEnd;
    public int selectionStart;
    public String text;

    public State(String str, int i, int i2, int i3, int i4) {
        this.text = str;
        this.selectionStart = i;
        this.selectionEnd = i2;
        this.composingRegionStart = i3;
        this.composingRegionEnd = i4;
    }
}