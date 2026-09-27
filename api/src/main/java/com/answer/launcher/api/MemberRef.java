package com.answer.launcher.api;

/** A hook target by name. The loader resolves the actual Member lazily via the guest classloader. */
public final class MemberRef {

    public final String className;
    public final String methodName;
    public final String[] paramTypes;

    public MemberRef(String className, String methodName, String... paramTypes) {
        this.className = className;
        this.methodName = methodName;
        this.paramTypes = paramTypes == null ? new String[0] : paramTypes;
    }

    public static MemberRef of(String className, String methodName, String... paramTypes) {
        return new MemberRef(className, methodName, paramTypes);
    }

    @Override
    public String toString() {
        return className + "#" + methodName + "(" + String.join(",", paramTypes) + ")";
    }
}
