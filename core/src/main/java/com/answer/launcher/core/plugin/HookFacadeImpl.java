package com.answer.launcher.core.plugin;

import com.answer.launcher.api.AfterHook;
import com.answer.launcher.api.BeforeHook;
import com.answer.launcher.api.HookFacade;
import com.answer.launcher.api.MemberRef;
import com.answer.launcher.api.PluginLogger;
import com.answer.launcher.api.ReplaceHook;
import com.answer.launcher.core.hook.HookProvider;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * Wraps {@link HookProvider}: resolves a {@link MemberRef} to a real Member via
 * the guest classloader, then installs before/after/replace/doNothing. Per-hook
 * try/catch + fail-fast + idempotency are HookProvider's; this only adapts the
 * callback types and isolates a plugin callback that throws.
 */
final class HookFacadeImpl implements HookFacade {

    private final PluginLogger log;

    HookFacadeImpl(PluginLogger log) {
        this.log = log;
    }

    @Override
    public boolean before(MemberRef t, BeforeHook cb) {
        Member m = resolve(t);
        if (m == null || cb == null) return false;
        return HookProvider.before(m, acp -> {
            try {
                cb.before(new HookParamsAdapter(acp, t));
            } catch (Throwable th) {
                log.error("before hook threw", th);
            }
        });
    }

    @Override
    public boolean after(MemberRef t, AfterHook cb) {
        Member m = resolve(t);
        if (m == null || cb == null) return false;
        return HookProvider.after(m, acp -> {
            try {
                cb.after(new HookParamsAdapter(acp, t));
            } catch (Throwable th) {
                log.error("after hook threw", th);
            }
        });
    }

    @Override
    public boolean replace(MemberRef t, ReplaceHook cb) {
        Member m = resolve(t);
        if (m == null || cb == null) return false;
        return HookProvider.replace(m, acp -> {
            try {
                return cb.replace(new HookParamsAdapter(acp, t));
            } catch (Throwable th) {
                log.error("replace hook threw", th);
                return null;
            }
        });
    }

    @Override
    public boolean doNothing(MemberRef t) {
        Member m = resolve(t);
        if (m == null) return false;
        return HookProvider.doNothing(m);
    }

    private Member resolve(MemberRef t) {
        if (t == null) return null;
        Class<?> owner = HookProvider.clazz(t.className);
        if (owner == null) {
            log.warn("class not found: " + t.className);
            return null;
        }
        Class<?>[] pts = resolveParamTypes(t.paramTypes);
        Method m = HookProvider.method(owner, t.methodName, pts);
        if (m == null) log.warn("method not found: " + t);
        return m;
    }

    private static Class<?>[] resolveParamTypes(String[] names) {
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) out[i] = resolveType(names[i]);
        return out;
    }

    private static Class<?> resolveType(String n) {
        if (n == null || n.isEmpty()) return null;
        switch (n) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            default: return HookProvider.clazz(n);
        }
    }
}
