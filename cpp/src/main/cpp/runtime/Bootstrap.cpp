#include <runtime/Bootstrap.h>

#include <android/android_log.h>
#include <hook/HookInstaller.h>
#include <hook/manager_minecraft.h>
#include <main.h>
#include <mcpe/core/GameSDK.h>

namespace Bootstrap {

void load() {
    initManagers();

    Main::baseAddress = Main::findLibBaseAddress("libminecraftpe.so");
    if (Main::baseAddress <= 0L) {
        LOGE("Main::baseAddress <= 0L");
    }

    LOGD("Minecraft Handle : %lx", Main::baseAddress);
    GameSdk::init(static_cast<uintptr_t>(Main::baseAddress));

    // RTTI first: it works on any build whose class layout is unchanged, and
    // needs no dlopen of the guest library (which our linker namespace cannot do
    // anyway). The signatures are the fallback for the functions that are not
    // virtual, and for builds where the RTTI chain does not validate.
    HookInstaller::installRttiHooks();
    HookInstaller::installSignatureHooks();
}

}  // namespace Bootstrap
