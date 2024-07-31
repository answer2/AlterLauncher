package com.answer.launcher;

import android.app.Application;
import android.webkit.WebView;
import com.answer.launcher.core.hook.EnvironmentHook;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.hook.LoadLibraryHook;
import com.answer.launcher.core.hook.UnixFileSystemHook;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;
import me.weishu.reflection.Reflection;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import top.canyie.pine.PineConfig;

public class AlterApplication extends Application{
    
    @Override
    public void onCreate() {
        super.onCreate();
        // TODO: Implement this method
        
        HiddenApiBypass.setHiddenApiExemptions("L");
        
        //initial Hook Framework
        PineConfig.debug = false;
        PineConfig.debuggable = true;
        
        Reflection.unseal(this);
        
        //init Environment
        PluginManager.getManager(getBaseContext());
        NativePluginManager.getManager(getBaseContext());
        
        // load Web
        new WebView(this);

    }
    
}
