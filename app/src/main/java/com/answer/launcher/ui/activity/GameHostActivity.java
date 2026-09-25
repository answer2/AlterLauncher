package com.answer.launcher.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.manager.Alternative;

/**
 * Launcher-owned host for the game.
 *
 * <p>This class is a <b>manifest anchor</b>, not the activity that ends up on
 * screen: {@code AlterInstrumentation.newActivity()} replaces every instance of
 * it with the real {@code com.mojang.minecraftpe.MainActivity} loaded from the
 * {@code DexClassLoader} built over the Minecraft APK. Nothing here but
 * {@link #onCreate} would ever run, and only if the swap fails.
 *
 * <p>Why the swap exists. The game used to be launched through
 * {@code LauncherActivity extends MainActivity}, which forces the launcher's
 * own {@code PathClassLoader} to define {@code MainActivity} (the dex merge in
 * {@code HotFix}). Consequence on Android 16: the linker namespace used for the
 * game's {@code System.loadLibrary} calls is the launcher's
 * ({@code clns-9}, search path = launcher lib dirs only), so
 * {@code MainActivity.<clinit>} died with
 * <pre>
 * dlopen failed: library "/system_ext/lib64/libc++_shared.so" needed or
 * dlopened by "/apex/com.android.art/lib64/libnativeloader.so" is not
 * accessible for the namespace "clns-9"
 * </pre>
 * Letting the game's own class loader define the activity gives it a namespace
 * built from the Minecraft APK's {@code nativeLibraryDir}, which resolves its
 * libraries natively — no {@code findLibrary}/{@code loadLibrary} hook needed.
 *
 * <p>Its manifest entry deliberately uses a framework theme
 * ({@code @android:style/Theme.NoTitleBar.Fullscreen}): the resource id comes
 * from the launcher's package, but after the swap the activity's resources are
 * the game's, so only an id that exists in every resource table is safe.
 */
public class GameHostActivity extends Activity {

    private static final String TAG = "GameHostActivity";

    /** The component the launcher starts and then swaps out. */
    public static final String NAME = "com.answer.launcher.ui.activity.GameHostActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /*
         * Only reachable when newActivity() could not substitute the game's
         * activity (assembly failed or the swap was bypassed). Finishing here is
         * what the user sees as "the launcher refused to start the game"; it is
         * far better than a black fullscreen window.
         */
        Log.e(TAG, "proxy survived the swap — game activity was not instantiated");
        if (Alternative.getManager().getContext() == null) {
            MinecraftLoader.load(getApplication().getBaseContext());
        }
        finish();
    }

    /**
     * True when the launcher has assembled the game well enough to launch it:
     * the guest class loader can resolve the game's activity class. Used instead
     * of {@code SystemUtil.checkClass()}, which asks the launcher's own loader
     * and therefore only works while the dex merge is on.
     */
    public static boolean gameReady() {
        ClassLoader guest = Alternative.getManager().getClassLoader();
        if (guest == null) {
            return false;
        }
        try {
            Class.forName(LauncherConstants.MINECRAFTACTIVITY, false, guest);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "game activity class not resolvable: " + t);
            return false;
        }
    }
}
