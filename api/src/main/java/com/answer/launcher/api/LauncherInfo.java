package com.answer.launcher.api;

import android.app.Activity;

/**
 * @Author AnswerDev
 * @Date 2024/07/11 21:17
 */
public class LauncherInfo {
    
    private Activity mActivity;
    private int version;
    private String gameVersion;
    private String init;
    private ClassLoader classLoader;

    public LauncherInfo(Activity mActivity, int version, String gameVersion, String init, ClassLoader classLoader) {
        this.mActivity = mActivity;
        this.version = version;
        this.gameVersion = gameVersion;
        this.init = init;
        this.classLoader = classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setActivity(Activity mActivity) {
        this.mActivity = mActivity;
    }

    public Activity getActivity() {
        return mActivity;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getVersion() {
        return version;
    }

    public void setGameVersion(String gameVersion) {
        this.gameVersion = gameVersion;
    }

    public String getGameVersion() {
        return gameVersion;
    }

    public void setInit(String init) {
        this.init = init;
    }

    public String getInit() {
        return init;
    }
    
    
}
