package com.answer.launcher.core.manager;

import android.content.res.AssetManager;
import android.util.Log;

import com.answer.launcher.core.tool.AssetOverrideManager;

public class AssestPackageManager {
    private static volatile AssestPackageManager manager;
    private String packagePath = null;
    private String fixPackagePath = null;
    private AssetManager mAsssests;

    private AssestPackageManager() {
        // private constructor to prevent instantiation
    }

    public static AssestPackageManager getManager() {
        if (manager == null) {
            synchronized (AssestPackageManager.class) {
                if (manager == null) {
                    manager = new AssestPackageManager();
                }
            }
        }
        return manager;
    }

    public void load(){
        if (getPackagePath() == null || getFixPackagePath() == null){
            Log.w("AssestPackageManager", "Loading fail, because the path is empty! ");
            return;
        }
        AssetOverrideManager assetOverrideManager = AssetOverrideManager.getInstance();
        assetOverrideManager.addAssetOverride(getPackagePath());
        assetOverrideManager.addAssetOverride(getFixPackagePath());
       setmAsssests(assetOverrideManager.getAssetManager());
    }

    public String getPackagePath() {
        return packagePath;
    }

    public void setPackagePath(String packagePath) {
        this.packagePath = packagePath;
    }

    public String getFixPackagePath() {
        return fixPackagePath;
    }

    public void setFixPackagePath(String fixPackagePath) {
        this.fixPackagePath = fixPackagePath;
    }

    public AssetManager getAsssests() {
        return mAsssests;
    }

    public void setmAsssests(AssetManager mAsssests) {
        this.mAsssests = mAsssests;
    }
}
