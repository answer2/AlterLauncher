package com.answer.launcher.core.hook;

import android.content.Context;
import android.util.Log;

import com.answer.launcher.core.manager.MinecraftManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Xbox Live sign-in workarounds.
 *
 * <p>Both targets live in the game's dex (the Microsoft XAL / idp SDKs shipped
 * inside Minecraft), so the classes are resolved by name: referring to them as
 * compile-time literals would tie this launcher build to whichever game version
 * happens to be in {@code compile_only/}.
 *
 * <p>The two hooks are independent. Previously a rename inside a newer XAL SDK
 * made the first lookup throw and silently dropped the second one, which is the
 * "xbox resources" fix from Minecraft 1.14 and not obvious when it goes missing.
 */
public class XboxHook extends BaseHook {

    private static final String TAG = "XboxHook";

    private static final String BROWSER_SELECTOR = "com.microsoft.xal.browser.BrowserSelector";
    private static final String BROWSER_INFO =
            "com.microsoft.xal.browser.BrowserSelectionResult$BrowserInfo";
    private static final String INTEROP = "com.microsoft.xbox.idp.interop.Interop";

    @Override
    public boolean requiresGameDex() {
        return true;
    }

    @Override
    public void init() {
        hookBrowserInfoImpliesNoUserDefault();
        hookReadConfigFile();
    }

    /**
     * Treat every browser as "implies no user default" unless it really looks
     * like a placeholder, so the sign-in flow stops short-circuiting.
     */
    private void hookBrowserInfoImpliesNoUserDefault() {
        Class<?> selector = HookProvider.clazz(BROWSER_SELECTOR);
        Class<?> browserInfo = HookProvider.clazz(BROWSER_INFO);
        if (selector == null || browserInfo == null) {
            Log.i(TAG, "XAL browser selector absent, skipped");
            return;
        }
        Method target = HookProvider.method(
                selector, "browserInfoImpliesNoUserDefault", browserInfo);
        HookProvider.replace(target, params -> {
            Object info = params.args[0];
            if (info == null) {
                return true;
            }
            Field versionCode = findField(info.getClass(), "versionCode");
            Field versionName = findField(info.getClass(), "versionName");
            if (versionCode == null || versionName == null) {
                return true;
            }
            versionCode.setAccessible(true);
            versionName.setAccessible(true);
            int code = versionCode.getInt(info);
            String name = (String) versionName.get(info);
            if (name == null) {
                return true;
            }
            return code == 0 && "none".equals(name);
        });
    }

    /**
     * {@code Interop.ReadConfigFile(Context)} resolves its config through the
     * context it is handed; with the game's own context it looks in the wrong
     * place (Minecraft 1.14 resource bug). Swap in the virtual environment's
     * context instead.
     */
    private void hookReadConfigFile() {
        Class<?> interop = HookProvider.clazz(INTEROP);
        if (interop == null) {
            Log.i(TAG, "Xbox idp Interop absent, skipped");
            return;
        }
        List<Method> overloads = HookProvider.methodsByName(interop, "ReadConfigFile");
        for (Method overload : overloads) {
            HookProvider.before(overload, params ->
                    params.args[0] = MinecraftManager.getManager().getContext());
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        Log.w(TAG, "field not found: " + name + " in " + type.getName());
        return null;
    }
}
