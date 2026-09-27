package com.answer.launcher.api;

/** Argument bag handed to a hook callback. */
public interface HookParams {

    Object thiz();

    Object[] args();

    Object getResult();

    void setResult(Object value);

    /** Invokes the original implementation; throws for constructors. */
    Object invokeOriginal() throws Throwable;

    MemberRef target();
}
