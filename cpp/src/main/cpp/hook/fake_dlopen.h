#include <dlfcn.h>
#include <jni.h>
#include <string>
#include <vector>
#include <hook/native_bridge.h>
#include <util/HookEngine.h>
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

// These shims used to be declared `void` and called the real function through a
// `void (*)(...)` pointer, which throws away the handle the loader returns in
// x0: every dlopen routed through them gave its caller a garbage pointer.
// Forward the real prototype and pass the result back.
typedef void *(*android_dlopen_ext_t)(const char *, int,
                                      const android_dlextinfo *);
android_dlopen_ext_t android_dlopen_ext_real = nullptr;
void *android_dlopen_ext_hook(const char *filename, int flags,
                              const android_dlextinfo *info) {
  LOGD("So FileName : %s", filename);
  return android_dlopen_ext_real(filename, flags, info);
};

typedef void *(*NativeBridgeLoadLibraryExt_t)(
    const char *, int, android::native_bridge_namespace_t *);
NativeBridgeLoadLibraryExt_t NativeBridgeLoadLibraryExt_real = nullptr;
void *NativeBridgeLoadLibraryExt_hook(
    const char *libpath, int flag, android::native_bridge_namespace_t *ns) {
  LOGD("So FileName : %s", libpath);
  return NativeBridgeLoadLibraryExt_real(libpath, flag, ns);
};

void initFakeDlopen() {
  handle = dlopen("libnativeloader.so", RTLD_LAZY);
  void *handle_ld = dlopen_ex("libdl_android.so", RTLD_LAZY);
  void *android_dlopen_ext = dlsym(handle, "android_dlopen_ext");
  void *NativeBridgeLoadLibraryExt_ =
      dlsym(handle, "NativeBridgeLoadLibraryExt");

  LOGD("initFakeDlopen");

  real_dlopen = (dlopen_t)dlsym(RTLD_NEXT, "dlopen");

  // The shadow of dlopen itself is intentionally NOT installed: it is a pure
  // log shim, and shadowhook on the global dlopen intercepts every load in the
  // process, including the linker's own re-entrant ones.
  if (android_dlopen_ext != nullptr) {
    HookEngine::hookAddr(android_dlopen_ext,
                         (void *)android_dlopen_ext_hook,
                         (void **)&android_dlopen_ext_real);
  } else {
    LOGD("android_dlopen_ext not resolved, skip its hook");
  }

  if (NativeBridgeLoadLibraryExt_ != nullptr) {
    HookEngine::hookAddr(
        NativeBridgeLoadLibraryExt_, (void *)NativeBridgeLoadLibraryExt_hook,
        (void **)&NativeBridgeLoadLibraryExt_real);
  } else {
    LOGD("NativeBridgeLoadLibraryExt not resolved, skip its hook");
  }
}
