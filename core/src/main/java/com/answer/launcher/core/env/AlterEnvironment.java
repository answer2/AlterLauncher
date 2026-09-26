package com.answer.launcher.core.env;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.util.ArrayMap;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.IOManager;

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
     * Leaves the guest context's data directories to its own derivation and rewrites
     * the one piece of stale state the framework caches globally.
     *
     * <p>Used to copy all seven directory fields from the launcher's ContextImpl,
     * which is how the game was made to read and write our files at all. C15 moved
     * that decision to {@code ApplicationInfo.dataDir}, set before the LoadedApk
     * exists, and API 36 derives {@code mFilesDir}, {@code mPreferencesDir},
     * {@code mDatabasesDir} and the rest from it — so a copy is not merely redundant,
     * it is wrong. The measurement that proved it: this method reported
     * {@code 0 already derived from its data root, 7 filled from the host} while the
     * isolated root demonstrably existed on disk, because the fields are lazy and
     * therefore still null at this point — "fill only the nulls" degenerated into
     * "overwrite everything", and the engine kept writing
     * {@code /data/user/0/com.answer.launcher/games/com.mojang/} with the launcher's
     * worlds instead of the instance's. Splitting the difference by timing the copy
     * after first use is not an option: any read of those getters creates the
     * directories.
     *
     * <p>The loop is kept, and still logs, so that if a future Android stops deriving
     * these fields from {@code dataDir} the regression shows up as a directory the
     * guest derived for itself count of zero rather than as someone's missing worlds.
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
         * aborted every remaining field. Unwrap to the backing ContextImpl and
         * guard each field on its own.
         */
        Context source = unwrapToContextImpl(app);
        Context target = unwrapToContextImpl(plugin);
        if (source == null || target == null) {
            Log.w(TAG, "environment copy skipped: source=" + describe(source, app)
                    + " target=" + describe(target, plugin));
            return;
        }

        int derived = 0;
        int failed = 0;
        // ContextImpl lazy data directories, no longer written by us.
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
                        if (f.get(target) != null) {
                            derived++;
                        }
                    } catch (Throwable t) {
                        failed++;
                        Log.w(TAG, "cannot inspect " + f.getName() + ": " + t);
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "cannot enumerate ContextImpl fields: " + e);
        }
        Log.i(TAG, "guest context directories: " + derived + " of " + dirFields.size()
                + " were already derived at this point, the rest lazily from the guest's own "
                + "data root (host root " + describeRoot(source) + ", guest root "
                + describeRoot(target) + "), " + failed + " fields skipped");
    }

    /**
     * ContextImpl's lazy private directory fields and the child each one derives under
     * the data root, exactly as API 36 spells them: {@code new File(getDataDir(),
     * "<child>")} for six of them, {@code getDataDir().toPath().resolve("crates")} for
     * the seventh ({@code ContextImpl.java:763, 852, 861-867, 880, 923, 933, 1075}).
     */
    private static final String[][] GUEST_DIRS = {
            {"mFilesDir", "files"},
            {"mPreferencesDir", "shared_prefs"},
            {"mDatabasesDir", "databases"},
            {"mNoBackupFilesDir", "no_backup"},
            {"mCacheDir", "cache"},
            {"mCodeCacheDir", "code_cache"},
            {"mCratesDir", "crates"},
    };

    /** True once the adopted activity has been logged; activities are recreated on rotation. */
    private static boolean guestDirsAdopted;

    /**
     * Points one guest activity's private directories at this installation's root.
     *
     * <p>{@code ContextImpl#getDataDir()} is the single accessor every one of them is
     * derived from, and hooking it measured as a dead hook: it is a three-branch
     * accessor, ART inlines it into the getters above, and the replaced entry never
     * ran — {@code installed replace -> android.app.ContextImpl#getDataDir()} with zero
     * invocations across an entire game boot, while
     * {@code ensurePrivateDirExists()}, which has a real body, hooked and fired in the
     * same process. So the fields are written instead.
     *
     * <p>The only safe moment is on the way into {@code callActivityOnCreate}: after
     * that, the first read of any getter both caches and creates. The framework's
     * {@code ensurePrivateDirExists} still makes the directory, with its own mode and
     * owner, which is why nothing here mkdirs. Stock {@code GameActivity} hands the
     * native engine {@code absPath(getFilesDir())} and {@code libminecraftpe.so} builds
     * {@code games/com.mojang} beneath it — that path, not the Application's, is where
     * the worlds live, which is why C15's root reached the guest Application while the
     * engine went on writing the launcher's own tree.
     *
     * <p>Our own launcher activities are left alone: in {@code :game} the proxy host is
     * a launcher class and keeps the state the framework derived for it. In shared mode
     * the root <em>is</em> the launcher's data directory, so the write is the identity.
     */
    public static void adoptGuestDirs(Activity activity) {
        if (activity == null) {
            return;
        }
        String name = activity.getClass().getName();
        if (name.startsWith(LauncherConstants.PACKAGE)) {
            return;
        }
        Alternative manager = Alternative.getManager();
        if (manager == null) {
            return;
        }
        File root = manager.getGuestDataRoot();
        if (root == null) {
            return;
        }
        Context target = unwrapToContextImpl(activity);
        if (target == null) {
            Log.w(TAG, "no ContextImpl behind " + name + ", guest directories not adopted");
            return;
        }
        int written = 0;
        int failed = 0;
        for (Field f : getContextImplFields()) {
            String child = childOf(f.getName());
            if (child == null) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(target, new File(root, child));
                written++;
            } catch (Throwable t) {
                failed++;
                Log.w(TAG, "cannot adopt " + f.getName() + " for " + name + ": " + t);
            }
        }
        /*
         * The String fields too. The device run that first shipped this rewrite
         * proved what they are and are not worth: rewriting
         * ApplicationInfo.dataDir did NOT move Context#getDataDir() — a LoadedApk
         * derived its directory Files once, at setApplicationInfo, and that is
         * what a context answers from, so the field only serves code that reads
         * the String directly (Bedrock's own path building does). The real move
         * happens in Alternative.retargetHostLoadedApk, which repoints the host
         * LoadedApk those contexts are bound to. It does not move app_webview:
         * that is created from the launcher's Application context in
         * MinecraftLoader, before a guest exists, and needs its own fix.
         */
        ApplicationInfo info = activity.getApplicationInfo();
        boolean dataDirMoved = false;
        if (info != null) {
            if (!root.getAbsolutePath().equals(info.dataDir)) {
                info.dataDir = root.getAbsolutePath();
                dataDirMoved = true;
            }
            File deviceRoot = manager.getGuestDeviceProtectedRoot();
            if (deviceRoot != null
                    && !deviceRoot.getAbsolutePath().equals(info.deviceProtectedDataDir)) {
                info.deviceProtectedDataDir = deviceRoot.getAbsolutePath();
            }
        }
        if (!guestDirsAdopted) {
            guestDirsAdopted = true;
            Log.i(TAG, "activity context dirs -> " + root + " (" + written + " of "
                    + GUEST_DIRS.length + " fields, " + failed + " skipped"
                    + (dataDirMoved ? ", dataDir rewritten" : "")
                    + ") for " + name);
        }
    }

    private static String childOf(String fieldName) {
        for (String[] entry : GUEST_DIRS) {
            if (entry[0].equals(fieldName)) {
                return entry[1];
            }
        }
        return null;
    }

    /** ContextImpl's declared fields, cached: looked up once per process, not once per activity. */
    private static Field[] contextImplFields;

    private static Field[] getContextImplFields() {
        if (contextImplFields == null) {
            try {
                contextImplFields = Class.forName(CONTEXT_IMPL).getDeclaredFields();
            } catch (Throwable t) {
                Log.e(TAG, "cannot enumerate ContextImpl fields: " + t);
                contextImplFields = new Field[0];
            }
        }
        return contextImplFields;
    }

    /**
     * The directory a ContextImpl actually derives from. Read through the public getter
     * rather than a field: API 36 has no {@code mDataDir} member on ContextImpl, and
     * reading it by reflection measured as {@code NoSuchFieldException}. Never written
     * by us, so whatever appears here is what the framework chose on its own.
     */
    private static String describeRoot(Context contextImpl) {
        try {
            return String.valueOf(contextImpl.getDataDir());
        } catch (Throwable t) {
            return "unknown (" + t + ")";
        }
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

        // Rewrite every file path in one bucket, guest package -> launcher package —
        // except inside this installation's own data root, where the package name is a
        // piece of the isolation slug and rewriting it invents a directory that was
        // never created (the same mistake EnvironmentHook guards against).
        BiFunction<String, ArrayMap<File, Object>, ArrayMap<File, Object>> replaceFunction =
                new BiFunction<String, ArrayMap<File, Object>, ArrayMap<File, Object>>() {
                    @Override
                    public ArrayMap<File, Object> apply(String key, ArrayMap<File, Object> oldMap) {
                        ArrayMap<File, Object> newMap = new ArrayMap<>();
                        for (File file : oldMap.keySet()) {
                            String path = file.getAbsolutePath();
                            newMap.put(IOManager.isIsolated(path)
                                    ? file
                                    : new File(path.replace(target, str)), oldMap.get(file));
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
