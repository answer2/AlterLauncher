package com.answer.launcher.api;

/**
 * Per-hook try/catch + fail-fast is built into HookProvider; this facade exposes
 * the same operations to plugins without handing them raw framework Members to
 * resolve themselves.
 */
public interface HookFacade {

    boolean before(MemberRef target, BeforeHook callback);

    boolean after(MemberRef target, AfterHook callback);

    boolean replace(MemberRef target, ReplaceHook callback);

    boolean doNothing(MemberRef target);
}
