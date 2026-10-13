#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <sys/stat.h>
#include <dirent.h>
#include <jni.h>
#include <memory>
#include <string>
#include <vector>
#include <iostream>
#include <android/android_log.h>
#include <util/HookEngine.h>

// The per-fopen JNI round-trip into IOManager.redirectPath is gone: every
// open would marshal the path through the JVM and call back into Java, which
// RenderThread asset I/O made both slow and, once, fatal (a cached main-thread
// JNIEnv used off-thread aborts ART). The authoritative table is now pushed
// from Java exactly once — at the end of IOManager.installRedirectRules — and
// matched here in plain C++. The semantics are IOManager.redirectPath's to
// the letter: first boundary-matching prefix wins, destination + the rest of
// the path; anything else is opened as asked.

struct RedirectRule {
    std::string from;
    std::string to;
};

struct RedirectTable {
    // The launcher's own roots. A path inside one is never re-redirected
    // blindly (that would undo the safety-net rules below), but it is the
    // only other family of paths a rule can name, so it gates the table scan.
    std::vector<std::string> own;
    // Insertion order == priority: the first matching rule wins.
    std::vector<RedirectRule> rules;
};

// Swapped wholesale from the installer thread, read from any thread. The
// shared_ptr free-function atomics are the C++17 way to do this without a
// lock on the read side. A null table means "identity": the hooks go live in
// JNI_OnLoad, before Java finishes building the rules, and must be
// pass-through until the snapshot arrives.
static std::shared_ptr<const RedirectTable> gTable;

// The returned pointer lives here, so it survives until the next redirect on
// the same thread — which is all the hook needs: the real fopen consumes it
// before anything else can redirect again.
static thread_local std::string gRedirected;

// Prefix match on a path-component boundary: "/data/data/com.a.b" must not
// swallow "/data/data/com.a.bx". Sets *matched to the prefix length.
static bool boundaryMatch(const char* path, const char* prefix, size_t* matched) {
    size_t n = strlen(prefix);
    if (strncmp(path, prefix, n) != 0) {
        return false;
    }
    char c = path[n];
    if (c != '\0' && c != '/') {
        return false;
    }
    *matched = n;
    return true;
}

// Returns the redirected path, or nullptr when the path must be opened as
// given (no table yet, nothing matched, or the match is the identity).
static const char* fastRedirect(const char* path) {
    std::shared_ptr<const RedirectTable> table = std::atomic_load(&gTable);
    if (!table || table->rules.empty()) {
        return nullptr;
    }

    size_t rest = 0;
    bool own = false;
    for (const std::string& prefix : table->own) {
        if (boundaryMatch(path, prefix.c_str(), &rest)) {
            own = true;
            break;
        }
    }
    // Native SDKs may retain the Android shell temporary directory.
    if (!own && strstr(path, "com.mojang") == nullptr
            && !boundaryMatch(path, "/data/local/tmp", &rest)) {
        return nullptr;
    }

    for (const RedirectRule& rule : table->rules) {
        if (boundaryMatch(path, rule.from.c_str(), &rest)) {
            gRedirected = rule.to;
            gRedirected.append(path + rest);
            return gRedirected.c_str();
        }
    }
    return nullptr;
}

// fopen alone never covered the whole surface: the engine also reaches the
// filesystem through open/openat (raw fd I/O), opendir (world listing),
// stat/access (existence probes that must see the same truth the redirect
// gives, or the engine writes somewhere else), and the mutators
// mkdir/unlink/rename. Every one of them answers from the same pushed table,
// so no caller can observe a path in two places at once.
//
// What this cannot catch: a direct syscall()/inline-SVC open that never goes
// through a libc symbol — no libc-level hook can. That is why the data root
// handed to the guest stays the primary containment and these hooks are the
// safety net for the paths the engine spells from memory.

typedef FILE* (*fopen_t)(const char* filename, const char* mode);
typedef int (*open_t)(const char* path, int flags, ...);
typedef int (*openat_t)(int dirfd, const char* path, int flags, ...);
typedef int (*creat_t)(const char* path, mode_t mode);
typedef DIR* (*opendir_t)(const char* name);
typedef int (*stat_t)(const char* path, struct stat* out);
typedef int (*fstatat_t)(int dirfd, const char* path, struct stat* out, int flags);
typedef int (*access_t)(const char* path, int amode);
typedef int (*faccessat_t)(int dirfd, const char* path, int amode, int flags);
typedef int (*mkdir_t)(const char* path, mode_t mode);
typedef int (*single_path_t)(const char* path);
typedef int (*unlink_t)(const char* path);
typedef int (*rename_t)(const char* from, const char* to);

static fopen_t fopen_real;
static open_t open_real;
static openat_t openat_real;
static creat_t creat_real;
static opendir_t opendir_real;
static stat_t stat_real;
static stat_t lstat_real;
static fstatat_t fstatat_real;
static access_t access_real;
static faccessat_t faccessat_real;
static mkdir_t mkdir_real;
static single_path_t rmdir_real;
static unlink_t unlink_real;
static rename_t rename_real;
static single_path_t remove_real;

// A path is only table-material if it is absolute — a relative one resolves
// against a dirfd or the CWD, and an already-redirected dirfd carries it to
// the right place on its own.
static inline const char* redirectAbsolute(const char* path) {
    return (path != nullptr && path[0] == '/') ? fastRedirect(path) : nullptr;
}

// Every redirected open used to print a logcat line — an IPC per call, on the
// path of the game's thousands of startup opens. Off unless the device asks:
// `adb shell setprop log.tag.Alternative_Core D` brings them all back.
// (Own check instead of __android_log_is_loggable, which is API 30+ here.)
static inline bool fileOpLogging() {
    static const bool enabled = [] {
        char value[92] = {0};
        if (__system_property_get("log.tag.Alternative_Core", value) <= 0) {
            return false;
        }
        return value[0] == 'D' || value[0] == 'V' || value[0] == 'd' || value[0] == 'v'
                || atoi(value) <= ANDROID_LOG_DEBUG;
    }();
    return enabled;
}

static void logRedirect(const char* op, const char* from, const char* to) {
    if (fileOpLogging()) {
        LOGD("%s : %s -> %s", op, from, to);
    }
}

static void logOpenResult(const char* op, int dirfd, const char* path,
                          const char* redirected, int flags, int result, int error) {
    if (fileOpLogging() && (result < 0 || redirected != nullptr
            || (path != nullptr && strstr(path, "com.mojang") != nullptr))) {
        LOGD("%s dirfd=%d flags=0x%x path=%s target=%s result=%d errno=%d",
             op, dirfd, flags, path ? path : "(null)",
             redirected ? redirected : (path ? path : "(null)"), result,
             result < 0 ? error : 0);
    }
}

static FILE* fopen_hook(const char* filename, const char* mode) {
    const char* redirected = fastRedirect(filename);
    // Logging every open cost more than the match it was tracing; only the
    // paths anyone cares about get one now, and even those are gated.
    if (fileOpLogging()) {
        if (redirected) {
            logRedirect("OpenFile", filename, redirected);
        } else if (strstr(filename, "com.mojang") != nullptr) {
            LOGD("OpenFile : %s", filename);
        }
    }
    return fopen_real(redirected ? redirected : filename, mode);
}

// open/openat take mode variadically, and only with O_CREAT.
static int open_hook(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    const int incomingErrno = errno;
    const char* redirected = redirectAbsolute(path);
    errno = incomingErrno;
    const int result = open_real(redirected ? redirected : path, flags, mode);
    const int savedErrno = errno;
    logOpenResult("open", AT_FDCWD, path, redirected, flags, result, savedErrno);
    errno = savedErrno;
    return result;
}

static int openat_hook(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    const int incomingErrno = errno;
    const char* redirected = redirectAbsolute(path);
    errno = incomingErrno;
    const int result = openat_real(dirfd, redirected ? redirected : path, flags, mode);
    const int savedErrno = errno;
    logOpenResult("openat", dirfd, path, redirected, flags, result, savedErrno);
    errno = savedErrno;
    return result;
}

static int creat_hook(const char* path, mode_t mode) {
    const char* redirected = fastRedirect(path);
    if (redirected) logRedirect("creat", path, redirected);
    return creat_real(redirected ? redirected : path, mode);
}

static DIR* opendir_hook(const char* name) {
    const char* redirected = fastRedirect(name);
    if (redirected) logRedirect("opendir", name, redirected);
    return opendir_real(redirected ? redirected : name);
}

static int stat_hook(const char* path, struct stat* out) {
    const char* redirected = fastRedirect(path);
    return stat_real(redirected ? redirected : path, out);
}

static int lstat_hook(const char* path, struct stat* out) {
    const char* redirected = fastRedirect(path);
    return lstat_real(redirected ? redirected : path, out);
}

static int fstatat_hook(int dirfd, const char* path, struct stat* out, int flags) {
    const char* redirected = redirectAbsolute(path);
    return fstatat_real(dirfd, redirected ? redirected : path, out, flags);
}

static int access_hook(const char* path, int amode) {
    const char* redirected = fastRedirect(path);
    return access_real(redirected ? redirected : path, amode);
}

static int faccessat_hook(int dirfd, const char* path, int amode, int flags) {
    const char* redirected = redirectAbsolute(path);
    return faccessat_real(dirfd, redirected ? redirected : path, amode, flags);
}

static int mkdir_hook(const char* path, mode_t mode) {
    const char* redirected = fastRedirect(path);
    if (redirected) logRedirect("mkdir", path, redirected);
    return mkdir_real(redirected ? redirected : path, mode);
}

static int rmdir_hook(const char* path) {
    const char* redirected = fastRedirect(path);
    return rmdir_real(redirected ? redirected : path);
}

static int unlink_hook(const char* path) {
    const char* redirected = fastRedirect(path);
    if (redirected) logRedirect("unlink", path, redirected);
    return unlink_real(redirected ? redirected : path);
}

static int rename_hook(const char* from, const char* to) {
    // Both ends redirect, and each on its own copy: fastRedirect hands back
    // one thread-local buffer, so the second call would overwrite the first.
    const char* first = fastRedirect(from);
    std::string fromCopy;
    if (first != nullptr) {
        fromCopy = first;
        first = fromCopy.c_str();
    }
    const char* second = fastRedirect(to);
    if (first || second) {
        logRedirect("rename", from, first ? first : to);
    }
    return rename_real(first ? first : from, second ? second : to);
}

static int remove_hook(const char* path) {
    const char* redirected = fastRedirect(path);
    return remove_real(redirected ? redirected : path);
}

// Names that exist twice for large-file reasons (open64 &co) can alias the
// very symbol already hooked; re-hooking one address in shadowhook's UNIQUE
// mode would fail, so keep the list of what is live and skip collisions.
static void* gHooked[32];
static int gHookedCount = 0;

static void hookLibc(const char* name, void* replacement, void** original) {
    void* addr = dlsym(RTLD_DEFAULT, name);
    if (addr == nullptr) {
        LOGD("hook %s: symbol absent, skipped", name);
        return;
    }
    for (int i = 0; i < gHookedCount; i++) {
        if (gHooked[i] == addr) {
            return;  // an alias of something already hooked
        }
    }
    if (gHookedCount < (int) (sizeof(gHooked) / sizeof(gHooked[0]))) {
        gHooked[gHookedCount++] = addr;
    }
    // By-name shadowhook lookups measured ENOENT inside the guest namespace;
    // resolving through dlsym and hooking the address is the proven path.
    if (HookEngine::hookAddr(addr, replacement, original) == nullptr) {
        LOGE("hook %s failed: %s", name, HookEngine::lastError());
    } else {
        LOGI("hooked %s", name);
    }
}

static void installFileRedirectHooks() {
    hookLibc("fopen", (void*) fopen_hook, (void**) &fopen_real);
    hookLibc("fopen64", (void*) fopen_hook, (void**) &fopen_real);
    hookLibc("open", (void*) open_hook, (void**) &open_real);
    hookLibc("open64", (void*) open_hook, (void**) &open_real);
    hookLibc("openat", (void*) openat_hook, (void**) &openat_real);
    hookLibc("openat64", (void*) openat_hook, (void**) &openat_real);
    hookLibc("creat", (void*) creat_hook, (void**) &creat_real);
    hookLibc("creat64", (void*) creat_hook, (void**) &creat_real);
    hookLibc("opendir", (void*) opendir_hook, (void**) &opendir_real);
    hookLibc("stat", (void*) stat_hook, (void**) &stat_real);
    hookLibc("stat64", (void*) stat_hook, (void**) &stat_real);
    hookLibc("lstat", (void*) lstat_hook, (void**) &lstat_real);
    hookLibc("lstat64", (void*) lstat_hook, (void**) &lstat_real);
    hookLibc("fstatat", (void*) fstatat_hook, (void**) &fstatat_real);
    hookLibc("fstatat64", (void*) fstatat_hook, (void**) &fstatat_real);
    hookLibc("access", (void*) access_hook, (void**) &access_real);
    hookLibc("faccessat", (void*) faccessat_hook, (void**) &faccessat_real);
    hookLibc("mkdir", (void*) mkdir_hook, (void**) &mkdir_real);
    hookLibc("rmdir", (void*) rmdir_hook, (void**) &rmdir_real);
    hookLibc("unlink", (void*) unlink_hook, (void**) &unlink_real);
    hookLibc("rename", (void*) rename_hook, (void**) &rename_real);
    hookLibc("remove", (void*) remove_hook, (void**) &remove_real);
}

void initFakeFile(JNIEnv *env) {
    (void) env;
    LOGI("initFakeFile 加载成功");

    installFileRedirectHooks();
}

static std::string toStdString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// NativeCore.setRedirectRules: the one snapshot install. Runs on the :game
// main thread right after the Java table is complete; a half-built table
// never reaches this side.
void setRedirectRules_java(JNIEnv* env, jobject /*thiz*/, jobjectArray ownPrefixes,
        jobjectArray froms, jobjectArray tos) {
    auto table = std::make_shared<RedirectTable>();
    if (ownPrefixes != nullptr) {
        for (jsize i = 0; i < env->GetArrayLength(ownPrefixes); i++) {
            jstring s = (jstring) env->GetObjectArrayElement(ownPrefixes, i);
            if (s != nullptr) {
                table->own.emplace_back(toStdString(env, s));
                env->DeleteLocalRef(s);
            }
        }
    }
    const jsize count = froms != nullptr ? env->GetArrayLength(froms) : 0;
    if (tos == nullptr || env->GetArrayLength(tos) != count) {
        LOGE("setRedirectRules: froms/tos length mismatch (%d)", count);
        return;
    }
    table->rules.reserve((size_t) count);
    for (jsize i = 0; i < count; i++) {
        jstring from = (jstring) env->GetObjectArrayElement(froms, i);
        jstring to = (jstring) env->GetObjectArrayElement(tos, i);
        if (from != nullptr && to != nullptr) {
            std::string fromStr = toStdString(env, from);
            std::string toStr = toStdString(env, to);
            if (!fromStr.empty() && !toStr.empty()) {
                table->rules.push_back({std::move(fromStr), std::move(toStr)});
            }
        }
        env->DeleteLocalRef(from);
        env->DeleteLocalRef(to);
    }
    std::atomic_store(&gTable, std::shared_ptr<const RedirectTable>(table));
    LOGI("fopen redirect table installed: %zu rule(s), %zu own prefix(es)",
         table->rules.size(), table->own.size());
}
