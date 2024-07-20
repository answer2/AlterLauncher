package com.answer.launcher.core.manager;

import java.util.ArrayList;
import java.util.List;
import android.content.Context;
import com.answer.launcher.core.tool.FileUtil;
import java.io.File;
import java.util.Arrays;

/**
 * @Author AnswerDev
 * @Date 2024/07/10 11:30
 */
public class NativePluginManager {

    private static volatile NativePluginManager manager;

    private Context mContext;
    private List<String> nativeFiles = new ArrayList<>();
    private File nativePath;

    private NativePluginManager(Context context) {
        this.mContext = context;
        this.nativePath = new File(context.getFilesDir().getAbsolutePath() + "/nativePlugin");
        if (!this.nativePath.exists()) this.nativePath.mkdir();
        
        for(File file : nativePath.listFiles()){
           if(!file.isDirectory()&&file.getName().contains(".so")) nativeFiles.add(file.getAbsolutePath());
        }
    }

    public static NativePluginManager getManager(Context context) {
        if (manager == null) {
            synchronized (NativePluginManager.class) {
                if (manager == null) {
                    manager = new NativePluginManager(context);
                }
            }
        }
        return manager;
    }

    public void addNativePlugin(String path) {
        File file = new File(path);
        if (file.exists()) {
            String pluginPath = this.nativePath.getAbsoluteFile().getAbsolutePath() + "/" + file.getName();
            FileUtil.copy(file, pluginPath);
            nativeFiles.add(pluginPath);
        }
    }

    public List<String> getNativeFiles() {
        return this.nativeFiles;
    }
    
    public void clear(){
        for(File file : nativePath.listFiles()){
            file.delete();
        }
    }
    

}
