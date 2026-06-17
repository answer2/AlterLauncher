#include <dlfcn.h>
#include <jni.h>
#include <string>
#include <vector>
#include <hook/native_bridge.h>
#include <shadowhook/shadowhook.h>
#include <android/android_log.h>
#include <util/stringUtil.h>
#include <util/fake_dlfcn.h>

typedef void* (*android_update_LD_LIBRARY_PATH)(const char* ld_library_path);

void *handle;
namespace android {

struct NativeLoaderNamespace {
 
};

};

typedef struct {
  uint64_t flags;

  void*   reserved_addr;
  
  size_t  reserved_size;

  int     relro_fd;

  int     library_fd;

  off64_t library_fd_offset;

  struct android_namespace_t* library_namespace;
} android_dlextinfo;

typedef void* (*dlopen_t)(const char*, int);


static dlopen_t real_dlopen = NULL;


void (*dlopen_real)(const char* filename, int flag);
void dlopen_hook(const char* filename, int flag){
    LOGD("filename: %s",filename);
    
    dlopen_real(filename,flag);
};

void (*android_dlopen_ext_real)(const char* filename, int flags, const android_dlextinfo* info);
void android_dlopen_ext_hook(const char* filename, int flags, const android_dlextinfo* info){
    LOGD("So FileName : %s", filename);
    android_dlopen_ext_real(filename, flags, info);
};


void* (*NativeBridgeLoadLibraryExt_real)(const char* libpath, int flag, android::native_bridge_namespace_t* ns);
void NativeBridgeLoadLibraryExt_hook(const char* libpath, int flag, android::native_bridge_namespace_t* ns){
    LOGD("So FileName : %s", libpath);
    NativeBridgeLoadLibraryExt_real(libpath, flag, ns);
};


void initFakeDlopen(){
    handle = dlopen("libnativeloader.so", RTLD_LAZY);
    void* handle_ld = dlopen_ex("libdl_android.so", RTLD_LAZY);
    void* android_dlopen_ext = dlsym(handle, "android_dlopen_ext");
    void* NativeBridgeLoadLibraryExt_ = dlsym(handle, "NativeBridgeLoadLibraryExt");
    
    LOGD("initFakeDlopen");
    
    real_dlopen = (dlopen_t)dlsym(RTLD_NEXT, "dlopen");

    shadowhook_hook_sym_addr((void*)android_dlopen_ext, (void*)android_dlopen_ext_hook, (void**)&android_dlopen_ext_real);
    
    shadowhook_hook_sym_addr((void*)dlopen, (void*)dlopen_hook, (void**)&dlopen_real);
    
    shadowhook_hook_sym_addr((void*)NativeBridgeLoadLibraryExt_, (void*)NativeBridgeLoadLibraryExt_hook, (void**)&NativeBridgeLoadLibraryExt_real);
    
}
