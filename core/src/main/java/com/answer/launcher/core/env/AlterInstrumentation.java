package com.answer.launcher.core.env;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import com.answer.launcher.core.hook.SplashScreenHook;
import android.util.Log;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.bridge.GameRuntime;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.tool.Reflector;
import java.lang.reflect.Field;

import com.answer.launcher.core.manager.MinecraftManager;

/**
 * @Author AnswerDev 
 * @Date 2024/07/06 11:17
 */

public class AlterInstrumentation extends Instrumentation {

  private static AlterInstrumentation instrumentation;
  private Instrumentation mBase;

  public static AlterInstrumentation get() {
    if (instrumentation == null) {
      synchronized (AlterInstrumentation.class) {
        if (instrumentation == null) {
          instrumentation = new AlterInstrumentation();
        }
      }
    }
    return instrumentation;
  }

  public AlterInstrumentation() {
    try {
      Reflector.on(Instrumentation.class)
          .method("basicInit", ActivityThread.class)
          .callByCaller(this, ActivityThread.currentActivityThread());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void startActivity(Intent intent) {
    try {
      Reflector.on(Instrumentation.class)
          .method("execStartActivity",
              Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class, int.class, Bundle.class)
          .callByCaller(this, MinecraftManager.getManager().getApplication().getBaseContext(),
              (IBinder) MinecraftManager.getManager().getActivityThread().getApplicationThread(),
              (IBinder) null,
              (Activity) null,
              intent,
              -1,
              (Bundle) null);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void callApplicationOnCreate(Application app) {
    Log.d("callApplicationOnCreate", app.getPackageName());

    super.callApplicationOnCreate(app);
  }
    
    @Override
    public void callActivityOnCreate(Activity activity, Bundle arg1) {
        if (activity != null) {
            if (!activity.getClass().getName().startsWith(LauncherConstants.PACKAGE)) {
                // ORDER MATTERS. The framework built this activity's Context from the
                // launcher's LoadedApk (the ActivityRecord is the proxy's), so its
                // Resources are the launcher's table until we replace them. Doing it
                // after super would let Activity#onCreate -- and therefore the whole
                // AppCompat subdecor -- resolve the game's compile-time resource ids
                // against the launcher's table, which yields e.g.
                // "Drawable com.answer.launcher:dimen/abc_text_size_subtitle_material_toolbar
                // with resource ID #0x7f07004e is not a Drawable".
                // The exchange that newActivity() performed has already been undone by
                // Activity#attach, so it has to happen again here.
                exchangeResourcesOfActivity(activity);
                // Private directories, same problem one level down: this Context came
                // from the launcher's LoadedApk, so getFilesDir() would hand the native
                // engine the launcher's tree and libminecraftpe.so would build
                // games/com.mojang -- the launcher's worlds -- beneath it. Has to happen
                // before Activity#onCreate, because the first read of any of those
                // getters caches the value and creates the directory at the same time.
                AlterEnvironment.adoptGuestDirs(activity);
                // Same story for a restore: the framework put the launcher's loader on
                // the saved-state Bundle before handing it to us.
                adoptSavedState(arg1);
                // The game's activities are AppCompat activities. Their manifest entry
                // in this APK is only a placeholder: the real theme resource id lives in
                // the GAME's resource space, so it cannot be written here (@android:...
                // ids resolve against the framework, launcher ids against the launcher).
                // Without this the game dies in AppCompatDelegateImpl.createSubDecor with
                // "You need to use a Theme.AppCompat theme (or descendant) with this
                // activity." -- the id must be applied before Activity#onCreate runs, and
                // AFTER the exchange, so that it is layered on the game's own table.
                applyGuestTheme(activity);
            }
            // Framework-level observation of the game's onCreate. This replaces
            // hooking MainActivity#onCreate directly, which forced the game's
            // <clinit> (and therefore a cross-package native library load the
            // launcher's linker namespace rejects on Android 16).
            SplashScreenHook.dispatch(activity);
        }
        super.callActivityOnCreate(activity, arg1);
        if(activity!=null&&(!activity.getClass().getName().contains(LauncherConstants.PACKAGE))){
             exchangeResourcesOfActivity(activity);
        }
    }

    /** Cached resource id of the game's own activity theme, in the game's space. */
    private static int sGuestThemeId = 0;
    private static boolean sGuestThemeResolved = false;

    private void applyGuestTheme(Activity activity) {
        try {
            if (!sGuestThemeResolved) {
                sGuestThemeId = resolveGuestThemeId();
                sGuestThemeResolved = true;
            }
            if (sGuestThemeId == 0) {
                Log.w("AlterInstrumentation",
                        "no guest theme resolved; AppCompat activities will fail in createSubDecor");
                return;
            }
            // Force a rebuild: ContextThemeWrapper caches the Theme created from the
            // launcher's resource table during attach(), and applyStyle() would only
            // layer the guest theme on top of it.
            try {
                Reflector.on(android.view.ContextThemeWrapper.class)
                        .field("mTheme").set(activity, null);
            } catch (Throwable ignored) {
                // Worst case the new theme is layered over the old one.
            }
            activity.setTheme(sGuestThemeId);
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "guest theme not applied: " + t);
        }
    }

    private int resolveGuestThemeId() {
        // The game's own ContextImpl, NOT getContext(): the theme ids and the attr ids
        // AppCompat looks up both live in the guest's resource table.
        Context guest = Alternative.getManager().getPackageContext();
        if (guest == null) {
            guest = Alternative.getManager().getContext();
        }
        if (guest == null) {
            return 0;
        }
        android.content.res.Resources resources = guest.getResources();
        // AppCompat reads its attrs by a compile-time constant id baked into whichever
        // copy of the library actually runs. With the child-first loader it is the
        // GAME's copy, so the id that matters is the one in the game's table. The
        // launcher's id is logged beside it because picking the wrong one makes every
        // candidate verify vacuously -- that is how 0x7f0e0007 was "chosen" in a run
        // where AppCompat still refused the theme.
        int windowActionBar = 0;
        int windowActionBarLauncher = 0;
        try {
            windowActionBar = resources.getIdentifier("windowActionBar", "attr",
                    LauncherConstants.MINECRAFTPACKAGE);
            windowActionBarLauncher = resources.getIdentifier("windowActionBar", "attr",
                    LauncherConstants.PACKAGE);
        } catch (Throwable ignored) {
            // Probed by name; without either id no candidate can be verified.
        }
        Log.i("AlterInstrumentation", "windowActionBar attr: game=0x"
                + Integer.toHexString(windowActionBar) + " launcher=0x"
                + Integer.toHexString(windowActionBarLauncher) + " resolved in " + resources);
        if (windowActionBar == 0) {
            windowActionBar = windowActionBarLauncher;
        }

        java.util.ArrayList<int[]> candidates = new java.util.ArrayList<>();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        // 1. What the game's own manifest declares for its MainActivity, in the game's
        //    resource space (the launcher's copy of that entry is only a placeholder).
        try {
            android.content.pm.PackageInfo gameInfo = guest.getPackageManager()
                    .getPackageInfo(LauncherConstants.MINECRAFTPACKAGE,
                            android.content.pm.PackageManager.GET_ACTIVITIES);
            if (gameInfo != null && gameInfo.activities != null) {
                for (android.content.pm.ActivityInfo ai : gameInfo.activities) {
                    if (ai != null && LauncherConstants.MINECRAFTACTIVITY.equals(ai.name)
                            && ai.getThemeResource() != 0) {
                        candidates.add(new int[] { ai.getThemeResource() });
                        names.add("game manifest");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "game manifest theme unavailable: " + t);
        }
        // 2. The game's theme by name (this is the pair LauncherActivity used to reach
        //    by reflection on com.mojang.minecraftpe.R.style).
        try {
            for (String style : new String[] { "AppTheme_SplashScreen", "AppTheme",
                    "Theme.Main", "AppBaseTheme" }) {
                int id = resources.getIdentifier(style, "style",
                        LauncherConstants.MINECRAFTPACKAGE);
                if (id != 0) {
                    candidates.add(new int[] { id });
                    names.add("name:" + style);
                }
            }
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "game theme name lookup failed: " + t);
        }
        // 3. The game's <application> theme, when it declares one separately.
        try {
            android.content.pm.ApplicationInfo gameAppInfo = guest.getApplicationInfo();
            if (gameAppInfo != null && gameAppInfo.theme != 0) {
                candidates.add(new int[] { gameAppInfo.theme });
                names.add("game application theme");
            }
        } catch (Throwable ignored) {
            // Optional fallback.
        }

        StringBuilder report = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            int id = candidates.get(i)[0];
            if (themeIsAppCompat(resources, id, windowActionBar)) {
                Log.i("AlterInstrumentation", "guest theme chosen (" + names.get(i)
                        + "): 0x" + Integer.toHexString(id));
                return id;
            }
            report.append(names.get(i)).append("=0x").append(Integer.toHexString(id))
                    .append(describe(resources, id)).append(" ");
        }
        Log.w("AlterInstrumentation", "no AppCompat-capable guest theme among [" + report
                + "] (windowActionBar attr 0x" + Integer.toHexString(windowActionBar) + ")");
        diagnoseAppCompat(resources, guest);
        return candidates.isEmpty() ? 0 : candidates.get(0)[0];
    }

    /**
     * AppCompat reads its attrs by a compile-time constant id baked into whichever copy
     * of the library actually runs. If the launcher's copy runs but the theme was built
     * from the game's resource table, no id matches and the check can never pass -- so
     * say which copy is live and whether the game ships its own.
     */
    private static void diagnoseAppCompat(android.content.res.Resources resources, Context guest) {
        int hostAttr = 0;
        try {
            hostAttr = (Integer) Class.forName("androidx.appcompat.R$attr")
                    .getField("windowActionBar").get(null);
        } catch (Throwable ignored) {
            // Launcher without appcompat on this path.
        }
        StringBuilder sb = new StringBuilder("appcompat windowActionBar: host=0x")
                .append(Integer.toHexString(hostAttr))
                .append(" guest=0x")
                .append(Integer.toHexString(resources.getIdentifier("windowActionBar", "attr",
                        LauncherConstants.MINECRAFTPACKAGE)));
        try {
            Class<?> delegate = Class.forName("androidx.appcompat.app.AppCompatDelegateImpl",
                    false, guest.getClassLoader());
            sb.append(" live classes from ").append(delegate.getClassLoader());
        } catch (Throwable t) {
            sb.append(" live classes unresolved: ").append(t);
        }
        try {
            java.lang.reflect.Method findClass = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredMethod("findClass", String.class);
            findClass.setAccessible(true);
            Object inGameDex = findClass.invoke(guest.getClassLoader(),
                    "androidx.appcompat.app.AppCompatDelegateImpl");
            sb.append(" game dex has its own copy: ").append(inGameDex);
        } catch (Throwable t) {
            sb.append(" game dex has no copy (").append(t.getCause() == null ? t
                    : t.getCause()).append(')');
        }
        Log.w("AlterInstrumentation", sb.toString());
    }

    private static String describe(android.content.res.Resources resources, int id) {
        try {
            return "(" + resources.getResourceName(id) + ")";
        } catch (Throwable t) {
            return "(unnamed)";
        }
    }

    /** True when applying {@code themeId} yields AppCompat's windowActionBar attribute. */
    private static boolean themeIsAppCompat(android.content.res.Resources resources,
            int themeId, int windowActionBarAttr) {
        if (themeId == 0) {
            return false;
        }
        if (windowActionBarAttr == 0) {
            // Cannot verify; assume the manifest value is right rather than lose it.
            Log.w("AlterInstrumentation",
                    "windowActionBar attr unavailable, theme accepted without verification");
            return true;
        }
        android.content.res.Resources.Theme theme = null;
        android.content.res.TypedArray array = null;
        try {
            theme = resources.newTheme();
            theme.applyStyle(themeId, true);
            array = theme.obtainStyledAttributes(new int[] { windowActionBarAttr });
            return array.hasValue(0);
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "theme 0x" + Integer.toHexString(themeId)
                    + " not verifiable: " + t);
            return false;
        } finally {
            if (array != null) {
                try {
                    array.recycle();
                } catch (Throwable ignored) {
                    // Best effort.
                }
            }
            if (theme != null) {
                // Nothing to release by hand: Resources.Theme has no mThemePtr field on
                // API 36 and its finalizer already returns the native theme.
            }
        }
    }

    /**
     * Give the guest activity the guest's class loader for view inflation.
     *
     * The ActivityRecord belongs to the launcher (GameHostActivity is the component
     * declared in this APK), so ActivityThread builds the activity's base ContextImpl
     * from the LAUNCHER's LoadedApk and ContextImpl.mClassLoader is the launcher's
     * PathClassLoader. LayoutInflater resolves the class names written in a layout
     * through Context#getClassLoader(), so the game's abc_screen_simple.xml produced
     * the LAUNCHER's copy of androidx.appcompat widgets, while AppCompatActivity --
     * loaded child-first from the game APK by GuestClassLoader -- casts them to its own
     * copies. The result is
     *   java.lang.ClassCastException: androidx.appcompat.widget.ContentFrameLayout
     *       cannot be cast to androidx.appcompat.widget.ContentFrameLayout
     * at AppCompatDelegateImpl.createSubDecor(AppCompatDelegateImpl.java:988).
     * Pointing the base context's loader at the guest loader makes both sides agree.
     */
    private void exchangeClassLoaderOfActivity(Activity activity) {
        try {
            Context base = activity.getBaseContext();
            ClassLoader guest = Alternative.getManager().getClassLoader();
            // Only android.app.ContextImpl carries mClassLoader, and AppCompatActivity
            // wraps the real one inside androidx.appcompat.view.ContextThemeWrapper
            // (onAttachBaseContext), so the chain has to be walked down first.
            while (base instanceof android.content.ContextWrapper
                    && !"android.app.ContextImpl".equals(base.getClass().getName())) {
                base = ((android.content.ContextWrapper) base).getBaseContext();
            }
            if (base == null || guest == null) {
                return;
            }
            if (guest == base.getClassLoader()) {
                return;
            }
            Reflector.on(base.getClass()).field("mClassLoader").set(base, guest);
            Log.i("AlterInstrumentation", "activity class loader exchanged to " + guest);
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "cannot exchange the activity class loader: " + t);
        }
    }
    
    /**
     * Materialise every extra on a launching Intent while the guest class loader is
     * still installed on it, so the framework cannot re-read them later with the
     * launcher's. See the call site in {@link #newActivity}.
     *
     * This goes for the Bundle held inside the Intent, not the one
     * {@link Intent#getExtras()} hands back: that is a defensive copy, and reading
     * through a copy leaves the original still pointing at its Parcel.
     */
    private void consumeExtras(Intent intent, ClassLoader guest) {
        Bundle extras;
        try {
            extras = Reflector.on(Intent.class).field("mExtras").get(intent);
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "cannot reach the intent extras: " + t);
            return;
        }
        if (extras == null) {
            return;
        }
        try {
            extras.setClassLoader(guest);
            // keySet() is BaseBundle.unparcel(); every get() then runs
            // Parcel.readValue over the real Parcel and caches the object in place,
            // which is exactly what makes the framework's later read harmless.
            for (String key : extras.keySet()) {
                try {
                    extras.get(key);
                } catch (Throwable ignored) {
                    // One value the guest loader cannot read must not sink the launch
                    // - the framework would only throw on it anyway.
                }
            }
        } catch (Throwable t) {
            Log.w("AlterInstrumentation", "cannot pre-read the intent extras: " + t);
        }
    }

    /**
     * Saved state is handed to the Activity as a Bundle whose loader ActivityThread
     * overwrote with the launcher's (android16 ActivityThread.java:4180) right
     * before {@code callActivityOnCreate}. Same fix, one step later.
     */
    private void adoptSavedState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        ClassLoader guest = Alternative.getManager().getClassLoader();
        if (guest != null) {
            savedInstanceState.setClassLoader(guest);
        }
    }

    @Override
    public void callActivityOnNewIntent(Activity arg0, Intent arg1) {
        super.callActivityOnNewIntent(arg0, arg1);
        // TODO: Implement this method
    }

    /**
     * Exit is the mirror image of launch. When the last game window is destroyed
     * the runtime is still assembled in this process, but nothing of it is on
     * screen any more, so the state goes back to idle. That is what tells the UI
     * process it may drop its {@code BIND_AUTO_CREATE} and let the system reclaim
     * {@code :game} - the launcher's own process is a different one and is never
     * part of the teardown.
     */
    @Override
    public void callActivityOnDestroy(Activity activity) {
        super.callActivityOnDestroy(activity);
        if (activity != null && SplashScreenHook.isGameActivity(activity.getClass())) {
            Log.i("AlterInstrumentation", "game window destroyed - runtime idle");
            GameRuntime.setIdle();
        }
    }
    
    @Override
    public boolean onException(Object arg0, Throwable arg1) {
        // TODO: Implement this method
        Log.d("onException",  arg0+"");
        arg1.printStackTrace();
        return super.onException(arg0, arg1);
    }
    
    /**
     * Component substitution.
     *
     * <p>The launcher starts {@code .ui.activity.GameHostActivity}, a class it
     * owns, and this method hands the framework a real
     * {@code com.mojang.minecraftpe.MainActivity} instance instead. Two reasons:
     *
     * <ul>
     *   <li>{@code LauncherActivity extends MainActivity} used to be the host,
     *       which forced the launcher's own {@code PathClassLoader} to define the
     *       game's activity. Its linker namespace cannot see the game's lib dir,
     *       so {@code MainActivity.<clinit>} died on
     *       {@code library "/system_ext/lib64/libc++_shared.so" ... is not
     *       accessible for the namespace "clns-9"}. Loading the class from the
     *       DexClassLoader built over the Minecraft APK gives it that APK's
     *       namespace instead, which resolves its own libraries natively.</li>
     *   <li>The game's other components ({@code com.microsoft.xal.*},
     *       {@code com.mojang.*}) are declared in the launcher's manifest but
     *       exist only in the game dex. Once the dex is no longer merged into the
     *       launcher's loader, the framework's loader cannot resolve them at all,
     *       so every such component is resolved through the guest loader.</li>
     * </ul>
     */
    @Override
    public Activity newActivity(ClassLoader arg0, String arg1, Intent arg2) throws
            ClassNotFoundException, IllegalAccessException, InstantiationException {
        // TODO: Implement this method
        /*
         * Extras are unparceled with the class loader the framework puts on the
         * Intent, and ActivityThread takes that from the base context it built for
         * the ActivityRecord - the launcher's PathClassLoader. Since the game dex
         * moved to the guest loader that loader cannot see a single game class, so
         * any guest activity carrying a Serializable or Parcelable extra dies
         * before its onCreate. The XAL login page is exactly that:
         *
         *   android.os.BadParcelableException: Parcelable encountered
         *   ClassNotFoundException reading a Serializable object
         *   (name = com.microsoft.xal.browser.BrowserLaunchActivity$ShowUrlType)
         *     at android.os.Parcel.readSerializableInternal(Parcel.java:5553)
         *     at android.os.Parcel$LazyValue.apply(Parcel.java:4840)
         *   at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:4649)
         *
         * which is what the user sees as "登录页白屏 / 点了没反应".
         *
         * Setting the loader here is NOT enough on its own: android16 reads
         * ActivityThread.java
         *
         *   4173  activity = mInstrumentation.newActivity(cl, component.getClassName(), r.intent);
         *   4176  r.intent.setExtrasClassLoader(cl);          // back to the launcher's
         *   4177  r.intent.prepareToEnterProcess(...);        // reads every value now
         *
         * so the framework overwrites our loader one line later and materialises
         * every lazy value with it. What does hold is consuming the extras *here*,
         * while the guest loader is still installed: BaseBundle.unparcel() drops the
         * Parcel once read and caches the values, so the later prepareToEnterProcess
         * finds nothing left to parse and the wrong loader never gets a turn.
         */
        ClassLoader guest = Alternative.getManager().getClassLoader();
        if (guest != null && arg2 != null) {
            arg2.setExtrasClassLoader(guest);
            consumeExtras(arg2, guest);
        }
        if (LauncherConstants.GAME_HOST_ACTIVITY.equals(arg1)) {
            Activity game = newGuestActivity(LauncherConstants.MINECRAFTACTIVITY);
            if (game != null) {
                return game;
            }
            Log.e("AlterInstrumentation",
                    "game activity substitution failed, the proxy will show an error");
        } else if (!arg1.startsWith(LauncherConstants.PACKAGE)) {
            Activity game = newGuestActivity(arg1);
            if (game != null) {
                return game;
            }
        }
        Activity newActivity = super.newActivity(arg0, arg1, arg2);
        if(newActivity!=null&&!newActivity.getClass().getName().contains(LauncherConstants.PACKAGE)){
             exchangeResourcesOfActivity(newActivity);
        }
        return newActivity;
    }

    /**
     * Instantiates {@code name} through the loader that defines the game, or
     * returns {@code null} when the launcher has not assembled that loader yet
     * or the class is not in it.
     */
    private Activity newGuestActivity(String name) {
        ClassLoader guest = Alternative.getManager().getClassLoader();
        if (guest == null) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(name, false, guest);
            Activity activity = (Activity) clazz.getDeclaredConstructor().newInstance();
            exchangeResourcesOfActivity(activity);
            Log.i("AlterInstrumentation", "instantiated " + name
                    + " from the guest loader " + guest);
            return activity;
        } catch (Throwable t) {
            Log.w("AlterInstrumentation",
                    "cannot instantiate " + name + " from the guest loader: " + t);
            return null;
        }
    }

  public void initHook() {
    try {
      Instrumentation mInstrumentation = getCurrInstrumentation();
      if (mInstrumentation == this || checkInstrumentation(mInstrumentation)) return;
      setField(
          ActivityThread.currentActivityThread(), ActivityThread.class, "mInstrumentation", this);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private Instrumentation getCurrInstrumentation() throws Exception {
    return (Instrumentation)
        getField(ActivityThread.class, "mInstrumentation", ActivityThread.currentActivityThread());
  }

  private static Object getField(Class<?> c, String fieldName, Object o) throws Exception {
        return Reflector.on(c)
        .field(fieldName)
        .get(o);
  }

  private static void setField(Object obj, Class<?> c, String fieldName, Object value)
      throws Exception {
     Reflector.on(c)
        .field(fieldName)
        .set(obj, value);
  }

  private boolean checkInstrumentation(Instrumentation instrumentation) {
    if (instrumentation instanceof AlterInstrumentation) {
      return true;
    }
    Class<?> clazz = instrumentation.getClass();
    if (Instrumentation.class.equals(clazz)) {
      return false;
    }
    do {
      Field[] fields = clazz.getDeclaredFields();
      if (fields != null) {
        for (Field field : fields) {
          if (Instrumentation.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true);
            Object obj;
            try {
              obj = field.get(instrumentation);
            } catch (IllegalAccessException e) {
              return false;
            }
            if ((obj instanceof AlterInstrumentation)) {
              return true;
            }
          }
        }
      }
      clazz = clazz.getSuperclass();
    } while (!Instrumentation.class.equals(clazz));
    return false;
  }
    
    /**
     * 反射 Activity , 并设置 Activity 中 Resources 成员变量
     * @param activity
     */
    private void exchangeResourcesOfActivity(Activity activity) {
        try {
            // getPackageContext() is the GUEST's ContextImpl -- the one built from the
            // game's LoadedApk, whose Resources carry the game's own asset path.
            // getContext() returns the LAUNCHER's application context (Alternative.java
            // :159/:216), so writing its Resources here was a no-op: the activity kept
            // the launcher's table and every game id resolved against it, which is what
            // produced "Resources$NotFoundException: Resource ID #0x7f0b0015 type #0x10
            // is not valid" while inflating the game's abc_screen_simple.
            Context guest = Alternative.getManager().getPackageContext();
            if (guest == null) {
                guest = Alternative.getManager().getContext();
            }
            Class<?> contextThemeWrapperClass = Class.forName("android.view.ContextThemeWrapper");
            Reflector.on(contextThemeWrapperClass)
            .field("mResources").set(activity, guest.getResources());
            // Same reason, different field: the loader must agree with the table, or
            // AppCompat inflates one copy's widgets and casts them to another's.
            exchangeClassLoaderOfActivity(activity);
            
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
