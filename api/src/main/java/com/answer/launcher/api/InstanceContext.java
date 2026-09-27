package com.answer.launcher.api;

import android.app.Activity;
import java.io.File;

/** The currently running game instance: its dirs, version and the guest classloader. */
public interface InstanceContext {

    String id();

    String gameVersion();

    ClassLoader guestClassLoader();

    File dataDir();

    File modsDir();

    File logsDir();

    /** The live game Activity, or null before onCreate / after onDestroy. */
    Activity activity();
}
