package com.answer.launcher.core.tool;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import com.answer.launcher.core.LauncherConstants;

public class ContextFixer {

    /**
     * Re-points the pieces of a guest app context that are still mutable.
     *
     * <p>ContextImpl.mBasePackageName and mOpPackageName are final on API 36,
     * so they can no longer be rewritten here — the value has to be decided
     * when the context is built. Alternative.createAppContext() does that by
     * calling the 3-arg ContextImpl.createAppContext(ActivityThread, LoadedApk,
     * String opPackageName) with the launcher package, which is what
     * ContentResolver and the permission checks read. What is left below only
     * corrects a context built through the older 2-arg overload, and each write
     * is guarded so a locked field cannot take the whole init path down.
     */
    public static void fix(Context context) {
        try {
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }

            String packageName = LauncherConstants.SELF_PACKAGE;

            // Force PackageManager to be re-created against the fixed package.
            try {
                Reflector.on("android.app.ContextImpl")
                        .field("mPackageManager")
                        .set(context, null);
            } catch (Throwable t) {
                Log.d("ContextFixer", "mPackageManager not resettable: " + t);
            }

            try {
                Reflector.on(ContentResolver.class)
                        .field("mPackageName")
                        .set(context.getContentResolver(), packageName);
            } catch (Throwable t) {
                Log.d("ContextFixer", "ContentResolver.mPackageName not resettable: " + t);
            }
        } catch (Throwable t) {
            Log.w("ContextFixer", "context fix skipped", t);
        }
    }

}
