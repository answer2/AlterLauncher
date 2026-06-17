
cmake_minimum_required(VERSION 3.18.1)

# 设置项目名称和版本
project(alternative_core C CXX)

# 添加 OpenGL ES 2.0 和 EGL 的链接
find_library(EGL_LIB EGL)
find_library(GLESv2_LIB GLESv2)



# 指定 C++ 标准
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED True)

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
