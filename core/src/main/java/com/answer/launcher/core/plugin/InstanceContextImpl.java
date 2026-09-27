package com.answer.launcher.core.plugin;

import com.answer.launcher.api.InstanceContext;
import android.app.Activity;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.hook.HookProvider;
import com.answer.launcher.core.manager.MinecraftManager;
import java.io.File;

/** The currently running game instance. Pre-M2 there is one implicit instance; M2 refines id/dirs. */
final class InstanceContextImpl implements InstanceContext {

    private final File pluginDir;

    InstanceContextImpl(File pluginDir) {
        this.pluginDir = pluginDir;
    }

    @Override
    public String id() {
        return LauncherConstants.MINECRAFT_VERSION;
    }

    @Override
    public String gameVersion() {
        return LauncherConstants.MINECRAFT_VERSION;
    }

    @Override
    public ClassLoader guestClassLoader() {
        return HookProvider.getGameClassLoader();
    }

    @Override
    public File dataDir() {
        Activity a = MinecraftManager.getManager().getActivity();
        if (a != null) {
            try {
                return a.getFilesDir();
            } catch (Throwable t) {
                // fall through
            }
        }
        return pluginDir.getParentFile();
    }

    @Override
    public File modsDir() {
        return new File(pluginDir, "mods");
    }

    @Override
    public File logsDir() {
        return new File(pluginDir, "logs");
    }

    @Override
    public Activity activity() {
        return MinecraftManager.getManager().getActivity();
    }
}
