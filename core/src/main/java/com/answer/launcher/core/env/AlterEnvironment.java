package com.answer.launcher.core.env;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.ArrayMap;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import com.answer.launcher.core.LauncherConstants;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 15:00
 */
public class AlterEnvironment {

    private static final String TAG = "AlterEnvironment";

    /*
     @GuardedBy("mSync")
     private File mDatabasesDir;
     @GuardedBy("mSync")
     @UnsupportedAppUsage
     private File mPreferencesDir;
     @GuardedBy("mSync")
     private File mFilesDir;
     @GuardedBy("mSync")
     private File mCratesDir;
     @GuardedBy("mSync")
     private File mNoBackupFilesDir;
     @GuardedBy("mSync")
     private File mCacheDir;
     @GuardedBy("mSync")
     private File mCodeCacheDir;
     */

    /** The platform context whose instance fields we hand over to the guest. */
    private static final String CONTEXT_IMPL = "android.app.ContextImpl";

    /**
     * Copies the host's data-directory fields onto the guest context so the game
     * reads and writes the launcher's own files instead of a path built from its
     * own package name.
     */
    public static void establishEnv(Context app, Context plugin) {
        /*
         * Every ContextImpl instance field must be read from a ContextImpl.
         * The caller used to pass the launcher's Application
         * (com.answer.launcher.AlterApplication) as the source, so the very first
         * read threw
         *   IllegalArgumentException: Expected receiver of type android.app.ContextImpl,
         *                             but got com.answer.launcher.AlterApplication
         * and, because the whole loop sat in a single try/catch, that one throw
         * aborted every remaining field: the guest context never received the
         * host's databases/files/cache dirs. Unwrap to the backing ContextImpl and
         * guard each field on its own.
         */
        Context source = unwrapToContextImpl(app);
        Context target = unwrapToContextImpl(plugin);
        if (source == null || target == null) {
            Log.w(TAG, "environment copy skipped: source=" + describe(source, app)
                    + " target=" + describe(target, plugin));
            return;
        }

        int copied = 0;
        int failed = 0;
        // Context
        List<String> dirFields = Arrays.asList("mDatabasesDir", "mPreferencesDir",
                "mFilesDir", "mCratesDir", "mNoBackupFilesDir", "mCacheDir", "mCodeCacheDir");
        try {
            for (Field f : Class.forName(CONTEXT_IMPL).getDeclaredFields()) {
                try {
                    replaceSharedPrefsPackageName(target, f,
                            LauncherConstants.MINECRAFTPACKAGE, LauncherConstants.PACKAGE);
                } catch (Throwable t) {
                    failed++;
                    Log.w(TAG, "shared-prefs cache rewrite failed for "
                            + f.getName() + ": " + t);
                }
                if (dirFields.contains(f.getName())) {
                    try {
                        f.setAccessible(true);
                        f.set(target, f.get(source));
                        copied++;
                    } catch (Throwable t) {
                        failed++;
                        Log.w(TAG, "cannot copy " + f.getName() + ": " + t);
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "cannot enumerate ContextImpl fields: " + e);
        }
        Log.i(TAG, "guest context directories copied from host: "
                + copied + " fields, " + failed + " skipped");
    }

    private static String describe(Context unwrapped, Context original) {
        return unwrapped != null ? "ContextImpl"
                : String.valueOf(original != null ? original.getClass().getName() : null);
    }

    /**
     * Walks {@code ContextWrapper.getBaseContext()} until the platform
     * {@code ContextImpl} behind a context appears. Returns {@code null} if the
     * chain ends without one (a custom Context, or a context from another loader
     * whose class we must not resolve by casting).
     */
    private static Context unwrapToContextImpl(Context context) {
        Context current = context;
        while (current != null) {
            if (CONTEXT_IMPL.equals(current.getClass().getName())) {
                return current;
            }
            if (!(current instanceof ContextWrapper)) {
                return null;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                return null;
            }
            current = base;
        }
        return null;
    }

    /**
     * Rewrites the paths inside the platform's static shared-preferences cache so
     * entries recorded under the guest package name point at the launcher's own
     * files. Only {@code sSharedPrefsCache} is touched; the method is called for
     * every field so the caller does not have to filter.
     */
    public static void replaceSharedPrefsPackageName(Context context, Field f,
            final String target, final String str)
            throws IllegalAccessException, IllegalArgumentException {
        if (!"sSharedPrefsCache".equals(f.getName())) {
            return;
        }
        f.setAccessible(true);

        // The cached prefs, keyed by package name, each mapping the prefs file to its instance.
        @SuppressWarnings("unchecked")
        ArrayMap<String, ArrayMap<File, Object>> sharedPrefsCache =
                (ArrayMap<String, ArrayMap<File, Object>>) f.get(context);
        if (sharedPrefsCache == null) return;

        // Rewrite every file path in one bucket, guest package -> launcher package.
        BiFunction<String, ArrayMap<File, Object>, ArrayMap<File, Object>> replaceFunction =
                new BiFunction<String, ArrayMap<File, Object>, ArrayMap<File, Object>>() {
                    @Override
                    public ArrayMap<File, Object> apply(String key, ArrayMap<File, Object> oldMap) {
                        ArrayMap<File, Object> newMap = new ArrayMap<>();
                        for (File file : oldMap.keySet()) {
                            String newPath = file.getAbsolutePath().replace(target, str);
                            newMap.put(new File(newPath), oldMap.get(file));
                        }
                        return newMap;
                    }
                };

        for (Map.Entry<String, ArrayMap<File, Object>> entry : sharedPrefsCache.entrySet()) {
            if (entry == null) continue;
            String key = entry.getKey();
            ArrayMap<File, Object> oldMap = entry.getValue();
            sharedPrefsCache.put(key, replaceFunction.apply(key, oldMap));
        }

        f.set(context, sharedPrefsCache);
    }

}
