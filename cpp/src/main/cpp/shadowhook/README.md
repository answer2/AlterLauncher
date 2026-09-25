# shadowhook (vendored prebuilt)

ByteDance **shadowhook 2.0.1** — https://github.com/bytedance/android-inline-hook
(`com.bytedance.android:shadowhook:2.0.1`, AAR from Maven Central; the `jni/<abi>`
stripped libraries and `prefab/.../shadowhook.h` were taken from that AAR).

Layout:

- `shadowhook.h` — public header, added to the include path by `../CMakeLists.txt`.
- `<abi>/libshadowhook.so` — imported shared lib that `libalternative_core.so` links
  against. AGP packages imported libraries listed in `target_link_libraries`, so this
  file ends up in the APK by itself.
- `../../jniLibs/<abi>/libshadowhook_nothing.so` — packaged through the standard
  `jniLibs` folder because nothing links against it.

## Why the version matters

`libshadowhook_nothing.so` is new with the 1.1.x/2.x linker module and is **not
optional**: at `shadowhook_init()` time the library hooks
`soinfo::call_constructors()` in the platform linker, then `dlopen`s
`libshadowhook_nothing.so` purely to make that hook fire and locate the fields of
the linker's private `soinfo` struct by memory scan. If the dlopen fails, init
returns `SHADOWHOOK_ERRNO_INIT_LINKER` and no inline hook can be installed.

The previous vendored copy was 1.0.8, whose linker module resolved
`__dl__ZL10g_dl_mutex` from the linker and refused to init when it was missing.
Android 16 (API 36) no longer exports that name at all — the mutex is
`__dl_g_dl_mutex` there — so `shadowhook_init()` returned errno 12 on the test
device (realme RMX8899) and **every** native hook in this project (fopen redirect,
asset redirect, `ANativeActivity_finish`, the Minecraft callbacks) was silently
inert. 2.0.1 handles the rename, and additionally hooks
`soinfo::call_constructors`, which does still exist on API 36.

Do not "fix" 1.0.8 by dropping the mutex check — upgrade the prebuilt instead, and
keep both `<abi>/libshadowhook.so` and `jniLibs/<abi>/libshadowhook_nothing.so` in
step with the header.
