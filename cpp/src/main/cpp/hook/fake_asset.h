#include <jni.h>
#include <android/asset_manager.h>
#include <android/native_activity.h>

#pragma once
#ifndef HELPER_FAKEASSETS_H
#define HELPER_FAKEASSETS_H


void initFakeAssets(JNIEnv *env, AAssetManager* assets);

#endif //HELPER_FAKEASSETS_H
