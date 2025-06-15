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
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include "OpenGLRenderer.h"


void (*mOnCreateFunc)(ANativeActivity *, void *, size_t) = 0;

void (*mFinishFunc)(ANativeActivity *) = 0;

void (*mMainFunc)(struct android_app *) = 0;

void (*onNativeWindowCreated_real)(ANativeActivity *activity, ANativeWindow *window);

// var
EGLContext eglContext_ = nullptr;
 static bool isInit = false;

void onNativeWindowCreated_hook(ANativeActivity *activity, ANativeWindow *window) {
    onNativeWindowCreated_real(activity, window);
    LOGD("Minecraft onNativeWindowCreated_hook * : Success");
    LOGD("Minecraft ANativeWindow * : %p", window);

    ANativeWindow *mANativeWindow = window;

}

void (*onContentRectChanged_real)(ANativeActivity *activity, const ARect *rect);

void onContentRectChanged_hook(ANativeActivity *activity, const ARect *rect) {
    LOGD("Minecraft onContentRectChanged * : Success");
    LOGD("Content Rect: left=%d, top=%d, right=%d, bottom=%d\n",
         rect->left, rect->top,
         rect->right, rect->bottom);

    /*
    ARect mutableRect = {
        rect->left,         // 保持原有的 left
        rect->top,          // 保持原有的 top
        500,  // 修改 right
        500  // 修改 bottom
    };*/

    // const ARect* modifiedRect = &mutableRect;


    onContentRectChanged_real(activity, rect);
}

void (*mOnCreate_real)(ANativeActivity *activity, void *savedState, size_t savedStateSize);

void mOnCreate_hook(ANativeActivity *activity, void *savedState, size_t savedStateSize) {

    LOGD("Minecraft OnCreate : Success");
    mOnCreate_real(activity, savedState, savedStateSize);

    onNativeWindowCreated_real = activity->callbacks->onNativeWindowCreated;
    activity->callbacks->onNativeWindowCreated = onNativeWindowCreated_hook;

    onContentRectChanged_real = activity->callbacks->onContentRectChanged;
    activity->callbacks->onContentRectChanged = onContentRectChanged_hook;

    struct android_app *android_app = (struct android_app *) activity->instance;

    LOGD("android_app :%p", (void *)android_app);

}

void (*ANativeActivity_finish_real)(ANativeActivity *activity);

void ANativeActivity_finish_hook(ANativeActivity *activity) {
    LOGD("ANativeActivity_finish Success");
    LOGD("instance :%p", activity->instance);
    LOGD("class :%p", activity->clazz);
    // ANativeActivity_finish_real(activity);
}


void (*mMain_real)(struct android_app *state);

void mMain_hook(struct android_app *state) {
    mMain_real(state);
    LOGD("Minecraft Main_hook * : Success");
}



//eglSwapBuffers (EGLDisplay dpy, EGLSurface surface);
EGLAPI EGLBoolean EGLAPIENTRY (*eglSwapBuffers_real)(EGLDisplay dpy, EGLSurface surface);
EGLAPI EGLBoolean EGLAPIENTRY  eglSwapBuffers_hook(EGLDisplay dpy, EGLSurface surface){
    LOGD("Minecraft eglSwapBuffers : Exchange");

    if (!isInit&& eglContext_ != nullptr) {
        OpenGLRenderer *renderer = new OpenGLRenderer(dpy, surface, eglContext_);

        renderer->DrawRectangle(); // 渲染
        isInit = true;
    }
    EGLBoolean b = eglSwapBuffers_real(dpy, surface);
    return b;
}

EGLAPI EGLContext EGLAPIENTRY (*eglCreateContext_real) (EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list);
EGLAPI EGLContext EGLAPIENTRY eglCreateContext_hook (EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list){
    LOGD("Minecraft eglCreateContext : Created OpenGLES Context");
    eglContext_ = eglCreateContext_real(dpy, config, share_context, attrib_list);
    return eglContext_;
}



void initManagers() {
    void *handle = dlopen("libminecraftpe.so", RTLD_LAZY);
    if (handle == 0) return;


    mOnCreateFunc = (void (*)(ANativeActivity *, void *, size_t)) dlsym(handle,
                                                                        "ANativeActivity_onCreate");
    mFinishFunc = (void (*)(ANativeActivity *)) dlsym(handle, "ANativeActivity_finish");
    mMainFunc = (void (*)(struct android_app *)) dlsym(handle, "android_main");

    LOGD("Minecraft Handle * : %p", handle);
    LOGD("Minecraft Finish * : %p", mMainFunc);

    //shadowhook_hook_sym_addr((void *) ANativeActivity_onCreate, (void *) mOnCreate_hook,
      //                      (void **) &mOnCreate_real);

    //shadowhook_hook_sym_addr((void *) mMainFunc, (void *) mMain_hook, (void **) &mMain_real);

    shadowhook_hook_sym_addr((void *) ANativeActivity_finish, (void *) ANativeActivity_finish_hook,
                             (void **) &ANativeActivity_finish_real);

}