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

    private NativePluginManager(Context context) {
        this.mContext = context;
        rescan();
    }

    /**
     * Re-read the native plugin directory for the active installation. Same reason as
     * {@code PluginManager.rescan()}: the UI process can import into shared storage
     * at any time and {@code :game} has to notice.
     *
     * <p>Matched by suffix, not substring, so the UI's disable-by-rename works: a plugin
     * switched off is {@code libfoo.so.off}, which still <em>contains</em> {@code ".so"} but
     * must not be treated as loadable. {@code PluginManager} already scans {@code plugin/}
     * with {@code endsWith}; this keeps the two loaders consistent.
     */
    public void rescan() {
        nativeFiles.clear();
        File[] files = PluginPaths.activeNativeDir(mContext).listFiles();
        if (files == null) return;
        for(File file : files){
           if(!file.isDirectory()&&file.getName().endsWith(".so")) nativeFiles.add(file.getAbsolutePath());
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
            File dir = PluginPaths.activeNativeDir(mContext);
            if (!dir.exists()) dir.mkdirs();
            String pluginPath = new File(dir, file.getName()).getAbsolutePath();
            FileUtil.copy(file, pluginPath);
            nativeFiles.add(pluginPath);
        }
    }

    public List<String> getNativeFiles() {
        return this.nativeFiles;
    }

    public void clear(){
        File[] files = PluginPaths.activeNativeDir(mContext).listFiles();
        if (files != null) {
            for(File file : files){
                file.delete();
            }
        }
        nativeFiles.clear();
    }


}
