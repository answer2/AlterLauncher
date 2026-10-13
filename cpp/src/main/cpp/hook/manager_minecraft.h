#include <jni.h>


#pragma once

#ifndef HELPER_MANAGERMINECRAFT_H
#define HELPER_MANAGERMINECRAFT_H

void initManagers();

// Lazily hooks eglSwapBuffers to fire NativeCore.onFirstFrameNative once, when
// the game engine takes over the screen. Only the in-game loading overlay needs
// it, so it is never installed for a launch that shows no cover. JNI entry:
// (JNIEnv*, jclass) — resolves NativeCore on the calling thread's loader.
void installFirstFrameHook(JNIEnv *env, jclass clazz);

#endif //HELPER_MANAGERMINECRAFT_H