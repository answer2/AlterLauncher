
cmake_minimum_required(VERSION 3.18.1)

# 设置项目名称和版本
project(alternative_core C CXX)

# 添加 OpenGL ES 2.0 和 EGL 的链接
find_library(EGL_LIB EGL)
find_library(GLESv2_LIB GLESv2)



# 指定 C++ 标准
set(CMAKE_CXX_STANDARD 11)
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
    main.cpp
        OpenGLRenderer.cpp
    util/fake_dlfcn.cpp
    util/stringUtil.cpp
        util/tranceUtils.cpp
    hook/manager_minecraft.cpp
    hook/fake_asset.cpp
    hook/fake_file.cpp
    util/Utils.cpp
    util/FindSignature.cpp
    util/RttiLookup.cpp
    util/HookRules.cpp
    util/LongCall.cpp
)

# 添加头文件目录
include_directories(
    .
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
