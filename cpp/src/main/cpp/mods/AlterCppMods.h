#ifndef ALTER_NATIVE_MODS_ALTERCPPMODS_H
#define ALTER_NATIVE_MODS_ALTERCPPMODS_H

#include <jni.h>
#include <string>

/**
 * Drives the C++ registration entry point (`PLGetModRegistration`) of a native
 * mod, without the upstream preloader.
 *
 * <p>Why this is a separate translation unit: the upstream SDK headers
 * (`external/preloader-android/include/pl`) are C++20 and pull in fmt, while the
 * rest of this library is C++17. Only this file sees them, and the interface
 * here is plain C++17 with an opaque session handle.
 *
 * <p>Everything a registration-style mod receives is header-inline in that SDK —
 * `ModContext`, `ModInfo`, `Logger` — so a session can be built and run here with
 * no symbol resolved out of `libpreloader.so`. A mod that goes further (mod menu,
 * memory hooks, input) links the upstream library and is left to it instead; see
 * {@code AlterPreloader.cpp}.
 */
namespace AlterCppMods {

    /** The manifest/directory facts a mod is told about itself, as plain strings. */
    struct Info {
        std::string id;
        std::string displayName;
        std::string author;
        std::string version;
        std::string entryPath;
        std::string entryFileName;
        std::string libraryPath;
        std::string iconPath;
        std::string manifestPath;
        std::string modRootPath;
    };

    /**
     * Resolves the registration out of an already mapped library and runs its
     * `load` phase.
     *
     * @return an opaque session for the other calls, or null when the library
     *         exports no usable registration or its load phase failed. The
     *         library stays mapped either way — its constructors already ran.
     */
    void *attach(void *handle, JavaVM *vm, const Info &info);

    /** Lifecycle phases. A phase the mod does not implement counts as success. */
    bool enable(void *session);

    bool disable(void *session);

    bool unload(void *session);

    /** Frees the session's context. Does not unmap the library. */
    void release(void *session);
}

#endif //ALTER_NATIVE_MODS_ALTERCPPMODS_H
