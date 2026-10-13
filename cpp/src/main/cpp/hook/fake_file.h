#include <jni.h>

#pragma once
#ifndef HELPER_FAKEFILE_H
#define HELPER_FAKEFILE_H


/** Installs the libc file-operation hooks (fopen/open/stat/... see fake_file.cpp). */
void initFakeFile(JNIEnv *env);

/** NativeCore.setRedirectRules — installs the path-redirect table snapshot. */
void setRedirectRules_java(JNIEnv* env, jobject thiz, jobjectArray ownPrefixes,
        jobjectArray froms, jobjectArray tos);

#endif //HELPER_FAKEFILE_H
