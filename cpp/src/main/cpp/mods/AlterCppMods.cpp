#include "mods/AlterCppMods.h"

#include <android/android_log.h>
#include <dlfcn.h>
#include <pl/Mod.hpp>

#include <exception>
#include <memory>

#define LOG_TAG "AlterPreloader"
#define AC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using ResolveRegistration = pl::mod::ModRegistration *(*)();

/**
 * One attached mod. The context outlives every call because the mod keeps the
 * reference it was handed in `load` — upstream does the same.
 */
struct Session {
    pl::mod::ModRegistration *registration = nullptr;
    std::unique_ptr<pl::mod::ModContext> context;
};

pl::mod::ModInfo toModInfo(const AlterCppMods::Info &in) {
    pl::mod::ModInfo out;
    out.id = in.id;
    out.displayName = in.displayName;
    out.author = in.author;
    out.version = in.version;
    out.entryPath = in.entryPath;
    out.entryFileName = in.entryFileName;
    out.libraryPath = in.libraryPath;
    out.iconPath = in.iconPath;
    out.manifestPath = in.manifestPath;
    out.modRootPath = in.modRootPath;
    return out;
}

Session *asSession(void *session) {
    return static_cast<Session *>(session);
}

/**
 * A phase the mod left unimplemented is a success: the SDK's own dispatch
 * defaults `enable` / `disable` / `unload` to `true` for mods that only
 * implement part of the lifecycle.
 */
bool run(void *session, pl::mod::LifecycleFunction phase, const char *what) {
    Session *s = asSession(session);
    if (s == nullptr) return false;
    if (phase == nullptr) return true;
    try {
        return phase(s->registration->instance, *s->context);
    } catch (const std::exception &e) {
        AC_LOGE("exception while %s %s: %s", what, s->context->name().c_str(), e.what());
    } catch (...) {
        AC_LOGE("unknown exception while %s %s", what, s->context->name().c_str());
    }
    return false;
}

} // namespace

namespace AlterCppMods {

void *attach(void *handle, JavaVM *vm, const Info &info) {
    auto resolve = (ResolveRegistration) dlsym(handle, pl::mod::ModRegistrationSymbol);
    if (resolve == nullptr) return nullptr;

    pl::mod::ModRegistration *registration = nullptr;
    try {
        registration = resolve();
    } catch (const std::exception &e) {
        AC_LOGE("%s threw from %s: %s", info.id.c_str(),
                pl::mod::ModRegistrationSymbol, e.what());
        return nullptr;
    } catch (...) {
        AC_LOGE("%s threw from %s", info.id.c_str(), pl::mod::ModRegistrationSymbol);
        return nullptr;
    }
    if (registration == nullptr || registration->instance == nullptr
            || registration->load == nullptr) {
        AC_LOGE("%s returned no usable registration", info.id.c_str());
        return nullptr;
    }

    std::unique_ptr<Session> session = std::make_unique<Session>();
    session->registration = registration;
    try {
        session->context = std::make_unique<pl::mod::ModContext>(vm, toModInfo(info));
    } catch (const std::exception &e) {
        AC_LOGE("cannot build a mod context for %s: %s", info.id.c_str(), e.what());
        return nullptr;
    }

    if (!run(session.get(), registration->load, "loading")) {
        AC_LOGE("load failed for %s", info.id.c_str());
        return nullptr;
    }
    return session.release();
}

bool enable(void *session) {
    Session *s = asSession(session);
    return s != nullptr && run(session, s->registration->enable, "enabling");
}

bool disable(void *session) {
    Session *s = asSession(session);
    return s != nullptr && run(session, s->registration->disable, "disabling");
}

bool unload(void *session) {
    Session *s = asSession(session);
    return s != nullptr && run(session, s->registration->unload, "unloading");
}

void release(void *session) {
    delete asSession(session);
}

} // namespace AlterCppMods
