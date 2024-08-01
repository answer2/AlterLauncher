#include <android/asset_manager.h>
#include <android/native_activity.h>
#include <android/native_window.h>
#include <android/android_log.h>
#include <shadowhook/shadowhook.h>
#include <hook/fake_asset.h>


AAssetManager* Application_AssetManager = nullptr;
ANativeActivity* Application_Activity;
JNIEnv* Jnienv;

AAsset* (*AAssetManager_open_real)(AAssetManager* assetManager,const char *filename, int mode);
AAsset* AAssetManager_open_hook(AAssetManager* assetManager,const char *filename, int mode){
    //LOGD( "Directory Name: %s",filename);
    return AAssetManager_open_real(Application_AssetManager,filename,mode);
}

AAssetDir* (*AAssetManager_open_Dir_real)(AAssetManager* assetManager,const char *filename);
AAssetDir* AAssetManager_open_Dir_hook(AAssetManager* assetManager,const char* dirName){
    //LOGD( "Directory Name: %s",dirName);
    return AAssetManager_open_Dir_real(Application_AssetManager,dirName);
}

void (*ANativeActivity_finish_real)(ANativeActivity* activity);
void ANativeActivity_finish_hook(ANativeActivity* activity){
    LOGD("ANativeActivity_finish 结束");
    LOGD("instance :%p", activity->instance);
    LOGD("class :%p", activity->clazz);
    ANativeActivity_finish_real(activity);
}





void initFakeAssets(JNIEnv *env, AAssetManager* assets){
    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != SHADOWHOOK_ERRNO_OK) {
        LOGD("shadowhook init failed.\n");
        return ;
    }
    LOGD("shadowhook init successfully.\n");
    
    Application_AssetManager = assets;
    Jnienv = env;
   
    shadowhook_hook_sym_addr((void*)ANativeActivity_finish, (void*)ANativeActivity_finish_hook, (void**)&ANativeActivity_finish_real);
    shadowhook_hook_sym_addr((void*)AAssetManager_open, (void*)AAssetManager_open_hook, (void**)&AAssetManager_open_real);
    shadowhook_hook_sym_addr((void*)AAssetManager_openDir, (void*)AAssetManager_open_Dir_hook, (void**)&AAssetManager_open_Dir_real);
    
    LOGD("Hook initing.\n");
    
    //void ANativeActivity_onCreate(ANativeActivity* activity, void* savedState, size_t savedStateSize) {
  
    
    
    shadowhook_hook_sym_addr((void*)ANativeActivity_finish, (void*)ANativeActivity_finish_hook, (void**)&ANativeActivity_finish_real);
   }
