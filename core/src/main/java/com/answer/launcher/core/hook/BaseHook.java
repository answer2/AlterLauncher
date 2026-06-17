package com.answer.launcher.core.hook;

import android.content.Context;
import com.answer.launcher.core.tool.Reflector;

public abstract class BaseHook {
    protected static Context context;
    
    public static void setContext(Context mContext) {
    	context = mContext;
    }
    
    public abstract void init();
}
