package com.answer.launcher.api;

import android.app.Activity;

/** Fired after the guest's main Activity is created. Source: the Activity swap hook. */
public final class ActivityCreatedEvent implements GameEvent {

    private final Activity activity;

    public ActivityCreatedEvent(Activity activity) {
        this.activity = activity;
    }

    public Activity activity() {
        return activity;
    }
}
