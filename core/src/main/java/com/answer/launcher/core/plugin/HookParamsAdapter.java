package com.answer.launcher.core.plugin;

import com.answer.launcher.api.HookParams;
import com.answer.launcher.api.MemberRef;
import java.lang.reflect.Method;

/**
 * Adapts AlterCore's hook params ({@code dev.answer.altercore.core.HookParams})
 * to the published {@link HookParams}. {@code args} and {@code thisObject} are
 * public fields on the AlterCore type (used by EnvironmentHook / LoadLibraryHook
 * / XboxHook); {@code invokeOriginalMethod(Object, Object[])} is its public
 * method. Result get/set is reflected because the after-callback accessor is not
 * part of the stable call sites we depend on.
 */
final class HookParamsAdapter implements HookParams {

    private final dev.answer.altercore.core.HookParams acp;
    private final MemberRef target;

    HookParamsAdapter(dev.answer.altercore.core.HookParams acp, MemberRef target) {
        this.acp = acp;
        this.target = target;
    }

    @Override
    public Object thiz() {
        return acp.thisObject;
    }

    @Override
    public Object[] args() {
        return acp.args;
    }

    @Override
    public Object getResult() {
        try {
            Method g = acp.getClass().getMethod("getResult");
            g.setAccessible(true);
            return g.invoke(acp);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setResult(Object value) {
        try {
            Method s = acp.getClass().getMethod("setResult", Object.class);
            s.setAccessible(true);
            s.invoke(acp, value);
        } catch (Exception e) {
            // AlterCore may not expose setResult; best-effort
        }
    }

    @Override
    public Object invokeOriginal() throws Throwable {
        return acp.invokeOriginalMethod(acp.thisObject, acp.args);
    }

    @Override
    public MemberRef target() {
        return target;
    }
}
