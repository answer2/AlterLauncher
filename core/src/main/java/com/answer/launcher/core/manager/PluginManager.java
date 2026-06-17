package com.answer.launcher.core.manager;

import android.content.Context;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.api.AlternativeApi;
import com.answer.launcher.api.LauncherInfo;
import com.answer.launcher.api.PluginInfo;
import com.answer.launcher.core.tool.FileUtil;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author AnswerDev
 * @Date 2024/07/10 17:16
 */
public class PluginManager {

    private static volatile PluginManager manager;

    private static List<String> plugins = new ArrayList<>();
    private static Map<String, PluginInfo> infos = new HashMap<>();
    private Context mContext;
    private File pluginPath;

    private PluginManager(Context context) {
        this.mContext = context;
        this.pluginPath = new File(context.getFilesDir().getAbsolutePath() + "/plugin");
        if (!this.pluginPath.exists()) this.pluginPath.mkdir();
        for (File file : pluginPath.listFiles()) {

            if (!file.isDirectory() && (file.getName().contains(".dex") || file.getName().contains(".apk")))plugins.add(file.getAbsolutePath());
        }
    }

    public static PluginManager getManager(Context context) {
        if (manager == null) {
            synchronized (PluginManager.class) {
                if (manager == null) {
                    manager = new PluginManager(context);
                }
            }
        }
        return manager;
    }

    public void addPlugin(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                String pluginPath = this.pluginPath.getAbsoluteFile().getAbsolutePath() + "/" + file.getName();
                FileUtil.copy(file, pluginPath);
                plugins.add(pluginPath);
            }
        } catch (Exception e) {e.printStackTrace();}
    }

    public List<String> getPluginFiles() {
        return this.plugins;
    }

    public void clear() {
        for (File file : pluginPath.listFiles()) {
            file.delete();
            plugins.clear();
            infos.clear();
        }
    }

    public void loadPlugin() {
        if (MinecraftManager.getManager().getActivity() == null) throw new NullPointerException("Minecraft MainActivity is null");
        try {

            for (String path : plugins) {
                File plugin = new File(path);
                String optimizedDirectory = mContext.getDir("plugin", Context.MODE_PRIVATE).getAbsolutePath();
                DexClassLoader classLoader = new DexClassLoader(path, optimizedDirectory, null, getClass().getClassLoader());
                
                Class<?> alternativeApiClass = Class.forName(AlternativeApi.class.getName(), true, classLoader);
                Class<?> launcherInfoClass = Class.forName(LauncherInfo.class.getName(), false, classLoader);

                LauncherInfo launcherInfoInstance = new LauncherInfo(MinecraftManager.getManager().getActivity(), LauncherConstants.LAUNCHER_VERSION, LauncherConstants.MINECRAFT_VERSION, plugin.getName().replace(".dex", "").replace(".apk", ""), classLoader);
                
                Method initMethod = alternativeApiClass.getDeclaredMethod("init", launcherInfoClass);
                initMethod.setAccessible(true);
                
                PluginInfo pluginInfo = (PluginInfo) initMethod.invoke(null, launcherInfoInstance);
                
                infos.put(pluginInfo.getName(), pluginInfo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public Map<String, PluginInfo> getInfos() {
        return infos;
    }
}
