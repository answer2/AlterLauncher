package com.answer.launcher.core.hook;

import android.util.Log;

import com.answer.launcher.core.manager.Alternative;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

import dalvik.system.BaseDexClassLoader;

/**
 * Makes {@code System.loadLibrary} see the game's own native library
 * directory, so Minecraft resolves its {@code .so} files instead of the
 * launcher's.
 *
 * <p>The library directory comes from {@link Alternative#nativepath}, already
 * resolved during {@code initPackage()}. Asking {@code PackageManager} here
 * instead used to throw {@code NameNotFoundException} before the single hook
 * statement, which silently left this whole class inert.
 */
public class LoadLibraryHook extends BaseHook {

    private static final String TAG = "LoadLibraryHook";

    @Override
    public void init() {
        final String nativeLibraryDir = Alternative.nativepath;
        if (nativeLibraryDir == null) {
            Log.w(TAG, "nativeLibraryDir unknown, findLibrary hook skipped");
            return;
        }

        // Exact lookup, NOT methodsByName(): walking the superclass chain also
        // finds java.lang.ClassLoader#findLibrary, whose base implementation
        // returns null. Replacing that makes every library lookup fail with
        // "library X not found" (observed with libc++_shared.so during
        // MainActivity's <clinit>).
        Method target = HookProvider.method(
                BaseDexClassLoader.class, "findLibrary", String.class);

        HookProvider.replace(target, params -> {
            String lib = (String) params.args[0];
            String path = nativeLibraryDir + "/lib" + lib + ".so";
            if (!checkExist(path)) {
                return params.invokeOriginalMethod(params.thisObject, params.args);
            }
            System.load(path);
            // libc++_shared is useless on its own: the decoder library
            // expects it to already be resident, so pull it in right after.
            if (lib != null && lib.contains("c++_shared")) {
                String media = nativeLibraryDir + "/libMediaDecoders_Android.so";
                if (checkExist(media)) {
                    System.load(media);
                }
            }
            return path;
        });
    }

    private static boolean checkExist(String path) {
        return new File(path).exists();
    }
}
