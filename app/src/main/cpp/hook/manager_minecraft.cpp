#include <jni.h>
#include <hook/manager_minecraft.h>
#include <android/android_log.h>
#include <shadowhook/shadowhook.h>
#include <dlfcn.h>
#include <android/native_activity.h>
#include <util/stringUtil.h>
#include <android/native_window.h>
#include <include/android_native_app_glue.h>
#include <android/rect.h>

void (*mOnCreateFunc)(ANativeActivity*,void*,size_t) = 0;
void (*mFinishFunc)(ANativeActivity*) = 0;
void (*mMainFunc)(struct android_app*) = 0;

void (*onNativeWindowCreated_real)(ANativeActivity* activity, ANativeWindow* window);
void onNativeWindowCreated_hook(ANativeActivity* activity, ANativeWindow* window){
    onNativeWindowCreated_real(activity, window);
    LOGD("Minecraft onNativeWindowCreated_hook * : Success");
    LOGD("Minecraft ANativeWindow * : %p", window);
    
}

void (*onContentRectChanged_real)(ANativeActivity* activity, const ARect* rect);
void onContentRectChanged_hook(ANativeActivity* activity, const ARect* rect){
    LOGD("Minecraft onContentRectChanged_hook * : Success");
    // 输出内容矩形的信息
    LOGD("Content Rect: left=%d, top=%d, right=%d, bottom=%d\n",
               rect->left, rect->top,
               rect->right, rect->bottom);
               
    // 创建一个非 const 的副本
    /*
    ARect mutableRect = {
        rect->left,         // 保持原有的 left
        rect->top,          // 保持原有的 top
        500,  // 修改 right
        500  // 修改 bottom
    };*/

   // const ARect* modifiedRect = &mutableRect;
    
               
    onContentRectChanged_real(activity, modifiedRect);
}

void (*mOnCreate_real)(ANativeActivity* activity,void* savedState, size_t savedStateSize);
void mOnCreate_hook(ANativeActivity* activity,void* savedState, size_t savedStateSize){
    
    LOGD("Minecraft OnCreate_hook * : Success");
    mOnCreate_real(activity, savedState, savedStateSize);
    
    onNativeWindowCreated_real = activity->callbacks->onNativeWindowCreated;
    activity->callbacks->onNativeWindowCreated = onNativeWindowCreated_hook;
    
    onContentRectChanged_real = activity->callbacks->onContentRectChanged;
    activity->callbacks->onContentRectChanged = onContentRectChanged_hook;
    
    struct android_app* android_app = (struct android_app*) activity->instance;
    ANativeWindow* window = android_app->pendingWindow;
    
    LOGD("Minecraft ANativeWindow * : %p", window);

}
void (*mMain_real)(struct android_app* state);
void mMain_hook(struct android_app* state) {
    mMain_real(state);
    LOGD("Minecraft Main_hook * : Success");
}





void initManagers(const char * path){
    void* handle = dlopen("libminecraftpe.so", RTLD_LAZY);
    if(handle == 0) return;
    
    
    mOnCreateFunc = (void(*)(ANativeActivity*,void*,size_t)) dlsym(handle,"ANativeActivity_onCreate");
    mFinishFunc = (void(*)(ANativeActivity*)) dlsym(handle,"ANativeActivity_finish");
    mMainFunc =(void(*)(struct android_app*)) dlsym(handle,"android_main");
    
    LOGD("Minecraft Handle * : %p", handle);
    
    shadowhook_hook_sym_addr((void*)mOnCreateFunc, (void*)mOnCreate_hook, (void**)&mOnCreate_real);
    
    shadowhook_hook_sym_addr((void*)mMainFunc, (void*)mMain_hook, (void**)&mMain_real);
    
    
}