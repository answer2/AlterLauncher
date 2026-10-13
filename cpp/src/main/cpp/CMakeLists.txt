
cmake_minimum_required(VERSION 3.18.1)

# 设置项目名称和版本
project(alternative_core C CXX)

# 添加 OpenGL ES 2.0 和 EGL 的链接
find_library(EGL_LIB EGL)
find_library(GLESv2_LIB GLESv2)



# 指定 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# 16 KB 页面对齐。NDK 27 默认仍按 4 KB 生成 PT_LOAD p_align，
# 在 16 KB 页的设备上（Android 16 起部分机型）这个 .so 会直接加载失败。
# 已验证：未加此选项时 libalternative_core.so 的 p_align 是 0x1000，
# 而随包附带的 libshadowhook.so 已经是 0x4000。
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_link_options("-Wl,-z,max-page-size=16384")
endif()

# 添加源文件
set(SRC_FILES
    Main.cpp
    jni/NativeCoreJni.cpp
    runtime/Bootstrap.cpp
    runtime/ProcessName.cpp
    hook/GameHooks.cpp
    hook/HookInstaller.cpp
    hook/RuleState.cpp
        OpenGLRenderer.cpp
    util/fake_dlfcn.cpp
    util/stringUtil.cpp
        util/tranceUtils.cpp
    hook/manager_minecraft.cpp
    hook/fake_asset.cpp
    hook/fake_file.cpp
    hook/statfs_hook.cpp
    util/Utils.cpp
    memory/FindSignature.cpp
    memory/RttiLookup.cpp
    memory/HookRules.cpp
    util/LongCall.cpp
    memory/SigScanner.cpp
    util/HookEngine.cpp
    mods/AlterPreloader.cpp
    mods/AlterCppMods.cpp
    mcpe/core/GameSDK.cpp
    mcpe/client/ClientInstance.cpp
    mcpe/client/MinecraftGame.cpp
    mcpe/client/GuiData.cpp
    mcpe/client/LevelRenderer.cpp
    mcpe/network/Network.cpp
    mcpe/network/Packet.cpp
    mcpe/actor/Actor.cpp
    mcpe/core/Types.cpp
    mcpe/api/ModApi.cpp
    mcpe/api/SdkHook.cpp
    mcpe/core/Reflection.cpp
    mcpe/core/ClassTree.cpp
    mcpe/world/Level.cpp
    mcpe/world/Dimension.cpp
    mcpe/world/BlockSource.cpp
    mcpe/world/Block.cpp
    mcpe/actor/GameMode.cpp
    mcpe/client/Options.cpp
    mcpe/client/Camera.cpp
    mcpe/inventory/Item.cpp
    mcpe/inventory/ItemStack.cpp
    mcpe/inventory/CompoundTag.cpp
    mcpe/actor/ContainerScreenController.cpp
    mcpe/actor/MoveInputHandler.cpp
    mcpe/client/ClientInstanceScreenModel.cpp
    mcpe/actor/MobEffectInstance.cpp
    mcpe/client/LevelRendererPlayer.cpp
)

# 添加头文件目录
include_directories(
    .
    memory
    jni
    runtime
    util
    hook
    shadowhook
    include
)

# 添加库
add_library(alternative_core SHARED ${SRC_FILES})


# 链接必要的系统库
target_link_libraries(alternative_core
    dl
    log
    android
       # native_app_glue
        ${EGL_LIB}
        ${GLESv2_LIB}
    shadowhook
)

# 根据 ABI 定义预编译库路径
if("${ANDROID_ABI}" STREQUAL "armeabi-v7a")
    set(SHADOWHOOK_LIB_PATH ${CMAKE_SOURCE_DIR}/shadowhook/armeabi-v7a/libshadowhook.so)
elseif("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    set(SHADOWHOOK_LIB_PATH ${CMAKE_SOURCE_DIR}/shadowhook/arm64-v8a/libshadowhook.so)
endif()

# 添加预编译库
add_library(shadowhook SHARED IMPORTED)
set_target_properties(shadowhook PROPERTIES IMPORTED_LOCATION ${SHADOWHOOK_LIB_PATH})

# 链接预编译库
target_link_libraries(alternative_core shadowhook)
# ---------------------------------------------------------------------------
# Levi 兼容 native mod 加载器：以 git submodule 直接链接上游项目
# （external/preloader-android，与 LeviLaunchroid 同款做法），构建
# libpreloader.so 随包发布。Java 侧桥接类见
# core/.../org/levimc/launcher/core/mods/ModManager。
set(PRELOADER_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/preloader-android)
if(EXISTS ${PRELOADER_SRC}/CMakeLists.txt)
    add_subdirectory(${PRELOADER_SRC} ${CMAKE_BINARY_DIR}/_preloader)

    # 自研 preloader 自己驱动 C++ 注册型模组（PLGetModRegistration），需要上游
    # SDK 头；那些头是 C++20 且依赖 fmt，本库其余部分是 C++17，所以只把这一个
    # 编译单元升级，上游类型不出现在它的接口上（见 mods/AlterCppMods.h）。
    set_source_files_properties(mods/AlterCppMods.cpp PROPERTIES COMPILE_FLAGS
            "-std=gnu++20 -fvisibility=hidden -fvisibility-inlines-hidden")
    target_include_directories(alternative_core PRIVATE ${PRELOADER_SRC}/include)
    # 无关键字写法：本文件其余 target_link_libraries 都是 plain 签名，混用会被 CMake 拒绝。
    target_link_libraries(alternative_core fmt::fmt)
endif()
