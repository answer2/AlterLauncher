package com.answer.launcher.core.tool;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import com.answer.launcher.core.LauncherConstants;

public class ContextFixer {
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
            Class<?> contextImpl_c = Class.forName("android.app.ContextImpl");
            Reflector.on(contextImpl_c).field("mPackageManager").set(context, null);

            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            String packageName = LauncherConstants.PACKAGE;

            Reflector.on(contextImpl_c).field("mBasePackageName").set(context, packageName);
            Reflector.on(Class.forName("android.app.ContextImpl")).field("mOpPackageName").set(context, packageName);
            Reflector.on(ContentResolver.class).field("mPackageName").set(context.getContentResolver(), packageName);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
