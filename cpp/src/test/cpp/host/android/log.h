#ifndef ALTER_LAUNCHER_HOST_ANDROID_LOG_SHIM_H
#define ALTER_LAUNCHER_HOST_ANDROID_LOG_SHIM_H

// Host stand-in for <android/log.h> so util/HookRules.cpp can be compiled and
// tested on a desktop toolchain: the rules engine is pure data logic and its
// only Android dependency is a debug line in select(). Found through the include
// path in build/host_tests.ps1, which puts cpp/src/test/cpp/host ahead of
// cpp/src/main/cpp; nothing in the production sources references this file.

#include <stdarg.h>
#include <stdio.h>

enum {
    ANDROID_LOG_DEFAULT = 0,
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_WARN = 5,
    ANDROID_LOG_ERROR = 6,
    ANDROID_LOG_DEBUG = 3
};

// Writes to stderr so a passing test stays silent on stdout.
static inline void __android_log_print(int priority, const char *tag, const char *fmt, ...) {
    (void)priority;
    (void)tag;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

// The TAG/LOGD/... macros come from the real android/android_log.h, which is
// what the production sources include; this shim only stands in for the NDK's
// <android/log.h> that it pulls in, so defining them here would collide.

#endif
