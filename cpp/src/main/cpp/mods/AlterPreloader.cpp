#define _GNU_SOURCE  // memmem

#include "mods/AlterPreloader.h"

#include <main.h>
#include <mods/AlterCppMods.h>
#include <util/Utils.h>
#include <android/android_log.h>

#include <climits>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

#define LOG_TAG "AlterPreloader"
#define AP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define AP_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define AP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kBridgeClass = "com/answer/launcher/core/mods/AlterModManager";
constexpr const char *kManifestName = "manifest.json";
constexpr const char *kPreloadNativeType = "preload-native";

constexpr const char *kSymCppRegistration = "PLGetModRegistration";
constexpr const char *kSymLifecycleLoad = "PLMod_Load";
constexpr const char *kSymLifecycleEnable = "PLMod_Enable";
constexpr const char *kSymLifecycleDisable = "PLMod_Disable";
constexpr const char *kSymLifecycleUnload = "PLMod_Unload";
constexpr const char *kSymLegacyLoad = "LeviMod_Load";

/**
 * The C struct the upstream preloader hands to `PLMod_Load` / `LeviMod_Load`.
 * Reproduced field-for-field: a mod compiled against Levi's `LegacyMod.h` reads
 * it by offset, so this layout is ABI, not a choice.
 */
struct PLModInfo {
    uint32_t size;
    const char *mod_id;
    const char *display_name;
    const char *author;
    const char *version;
    const char *entry_path;
    const char *entry_file_name;
    const char *library_path;
    const char *icon_path;
    const char *manifest_path;
    const char *mod_root_path;
};

using LifecycleLoadFn = bool (*)(JavaVM *, const PLModInfo *);
using LifecycleFn = bool (*)();
using LegacyLoadFn = void (*)(JavaVM *, const PLModInfo *);

enum class Kind { NoEntry, Lifecycle, Legacy, Cpp, Delegated };
enum class State { Loaded, Enabled, Unloaded };

struct Entry {
    std::string key;
    std::string modId;
    std::string displayName;
    std::string author;
    std::string version;
    std::string entryPath;
    std::string entryFileName;
    std::string libraryPath;
    std::string iconPath;
    std::string manifestPath;
    std::string modRootPath;

    void *handle = nullptr;
    Kind kind = Kind::NoEntry;
    State state = State::Loaded;
    LifecycleFn enable = nullptr;
    LifecycleFn disable = nullptr;
    LifecycleFn unload = nullptr;
    /** Opaque {@code AlterCppMods} session, for {@link Kind::Cpp} only. */
    void *cpp = nullptr;
};

std::mutex g_mutex;
std::vector<Entry> g_mods;
bool g_modsEnabled = false;
long g_lastLoadMs = 0;
int g_delegated = 0;
int g_cpp = 0;

// --- upstream delegation ---------------------------------------------------
//
// A registration-style mod that links libpreloader.so uses runtime state that
// only that library can set (its thread-local current-mod, the mod-menu owner
// stack), so it is handed over — mapped on demand, for that mod only. One that
// stays inside the header-inline SDK is driven here instead; see
// AlterCppMods.h.

using JniOnLoadFn = jint (*)(JavaVM *, void *);
using LevLoadModFn = jboolean (*)(JNIEnv *, jclass, jstring, jstring, jobject);
using LevEnableFn = void (*)(JNIEnv *, jclass);

void *g_leviHandle = nullptr;
LevLoadModFn g_leviLoadMod = nullptr;
LevEnableFn g_leviEnable = nullptr;
bool g_leviTried = false;

bool ensureLevi() {
    if (g_leviTried) return g_leviLoadMod != nullptr;
    g_leviTried = true;

    g_leviHandle = dlopen("libpreloader.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_leviHandle == nullptr) {
        AP_LOGE("libpreloader.so unavailable, C++ mods cannot load: %s", dlerror());
        return false;
    }
    // dlopen does not run JNI_OnLoad, and the upstream loader needs the JavaVM
    // it stores there before any of its natives can work.
    if (auto onLoad = (JniOnLoadFn) dlsym(g_leviHandle, "JNI_OnLoad")) {
        onLoad(Main::vm, nullptr);
    }
    g_leviLoadMod = (LevLoadModFn) dlsym(g_leviHandle,
            "Java_org_levimc_launcher_core_mods_ModManager_nativeLoadMod__"
            "Ljava_lang_String_2Ljava_lang_String_2Lorg_levimc_launcher_core_mods_Mod_2");
    g_leviEnable = (LevEnableFn) dlsym(g_leviHandle,
            "Java_org_levimc_launcher_core_mods_ModManager_nativeEnableLoadedMods");
    if (g_leviLoadMod == nullptr || g_leviEnable == nullptr) {
        AP_LOGE("libpreloader.so exports no ModManager natives");
        g_leviLoadMod = nullptr;
        return false;
    }
    return true;
}

// --- manifest --------------------------------------------------------------

bool readFile(const std::string &path, std::string &out) {
    FILE *f = fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        out.append(buf, n);
        if (out.size() > 256 * 1024) break;
    }
    fclose(f);
    return true;
}

/**
 * Pulls one string member out of a flat JSON object. Manifests here are a
 * handful of keys, so a scanner beats pulling a JSON library into the launch
 * path; it understands exactly the subset the format uses (escapes included)
 * and returns false for anything else, which the caller treats as "absent".
 */
bool jsonString(const std::string &json, const char *key, std::string &out) {
    std::string needle = std::string("\"") + key + "\"";
    size_t i = json.find(needle);
    if (i == std::string::npos) return false;
    i += needle.size();
    while (i < json.size() && (json[i] == ' ' || json[i] == '\t'
            || json[i] == '\n' || json[i] == '\r')) i++;
    if (i >= json.size() || json[i] != ':') return false;
    i++;
    while (i < json.size() && (json[i] == ' ' || json[i] == '\t'
            || json[i] == '\n' || json[i] == '\r')) i++;
    if (i >= json.size() || json[i] != '"') return false;
    i++;
    out.clear();
    for (; i < json.size(); i++) {
        char c = json[i];
        if (c == '"') return true;
        if (c != '\\') { out.push_back(c); continue; }
        if (++i >= json.size()) return false;
        switch (json[i]) {
            case 'n': out.push_back('\n'); break;
            case 't': out.push_back('\t'); break;
            case 'r': out.push_back('\r'); break;
            case 'b': out.push_back('\b'); break;
            case 'f': out.push_back('\f'); break;
            default: out.push_back(json[i]); break;
        }
    }
    return false;
}

bool endsWithSo(const std::string &name) {
    return name.size() > 3 && name.compare(name.size() - 3, 3, ".so") == 0;
}

bool isRegularFile(const std::string &path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string dirName(const std::string &path) {
    size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? std::string() : path.substr(0, slash);
}

std::string baseName(const std::string &path) {
    size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

std::string join(const std::string &dir, const std::string &name) {
    if (dir.empty()) return name;
    if (!name.empty() && name.front() == '/') return dir + name;
    return dir + "/" + name;
}

/** Rejects absolute paths and any `..` component, matching the upstream check. */
bool isSafeRelative(const std::string &rel) {
    if (rel.empty() || rel[0] == '/') return false;
    size_t i = 0;
    while (i < rel.size()) {
        size_t next = rel.find('/', i);
        std::string part = rel.substr(i, next == std::string::npos ? std::string::npos : next - i);
        if (part.empty() || part == "." || part == "..") return false;
        if (next == std::string::npos) break;
        i = next + 1;
    }
    return true;
}

std::string canonicalOr(const std::string &path) {
    char resolved[PATH_MAX];
    if (realpath(path.c_str(), resolved) != nullptr) return std::string(resolved);
    return path;
}

/**
 * Fills everything the mod is told about itself. Returns false when the
 * directory is not a `preload-native` mod at all, which is the same verdict the
 * upstream loader reaches — a bare `.so` with no manifest is not a mod.
 */
bool describe(const std::string &libraryPath, const std::string &sourceRoot, Entry &out) {
    std::string root = sourceRoot;
    if (root.empty()) {
        // Walk up until a directory carrying a manifest.json turns up.
        for (std::string cur = dirName(libraryPath); !cur.empty(); cur = dirName(cur)) {
            if (isRegularFile(join(cur, kManifestName))) { root = cur; break; }
            std::string parent = dirName(cur);
            if (parent == cur) break;
        }
    }
    if (root.empty()) {
        AP_LOGE("no mod root for %s", libraryPath.c_str());
        return false;
    }

    std::string manifestPath = join(root, kManifestName);
    std::string manifest;
    if (!readFile(manifestPath, manifest)) {
        AP_LOGE("unreadable %s", manifestPath.c_str());
        return false;
    }

    std::string type;
    jsonString(manifest, "type", type);
    if (type != kPreloadNativeType) {
        AP_LOGE("%s is not a %s mod (type='%s')", manifestPath.c_str(),
                kPreloadNativeType, type.c_str());
        return false;
    }

    std::string entry;
    jsonString(manifest, "entry", entry);
    for (char &c : entry) if (c == '\\') c = '/';
    if (!isSafeRelative(entry)) {
        AP_LOGW("rejected entry '%s' in %s", entry.c_str(), manifestPath.c_str());
        return false;
    }
    std::string library = canonicalOr(join(root, entry));
    if (!isRegularFile(library)) {
        AP_LOGW("entry %s declared in %s does not exist", library.c_str(),
                manifestPath.c_str());
        return false;
    }
    std::string entryFile = baseName(library);
    if (!endsWithSo(entryFile)) {
        AP_LOGW("entry %s is not a .so file", library.c_str());
        return false;
    }

    std::string icon;
    if (jsonString(manifest, "icon", icon)) {
        for (char &c : icon) if (c == '\\') c = '/';
        if (!isSafeRelative(icon) || !isRegularFile(join(root, icon))) icon.clear();
    }

    out.modId = baseName(root);
    jsonString(manifest, "name", out.displayName);
    if (out.displayName.empty()) out.displayName = out.modId;
    jsonString(manifest, "author", out.author);
    jsonString(manifest, "version", out.version);
    out.entryPath = entry;
    out.entryFileName = entryFile;
    out.libraryPath = library;
    out.iconPath = icon;
    out.manifestPath = manifestPath;
    out.modRootPath = root;
    out.key = library;
    return true;
}

PLModInfo toLegacyInfo(const Entry &e) {
    PLModInfo info{};
    info.size = sizeof(PLModInfo);
    info.mod_id = e.modId.c_str();
    info.display_name = e.displayName.c_str();
    info.author = e.author.c_str();
    info.version = e.version.c_str();
    info.entry_path = e.entryPath.c_str();
    info.entry_file_name = e.entryFileName.c_str();
    info.library_path = e.libraryPath.c_str();
    info.icon_path = e.iconPath.c_str();
    info.manifest_path = e.manifestPath.c_str();
    info.mod_root_path = e.modRootPath.c_str();
    return info;
}

void *acquireHandle(const std::string &library) {
    // Already mapped (a mod can be reachable both as a bare file and through its
    // directory): take the existing handle instead of running its ctors twice.
    if (void *existing = dlopen(library.c_str(), RTLD_NOLOAD | RTLD_NOW)) return existing;
    return dlopen(library.c_str(), RTLD_NOW | RTLD_GLOBAL);
}

// --- launch-time scan ------------------------------------------------------

std::mutex g_prepMutex;
std::condition_variable g_prepCv;
bool g_prepDone = false;
void *g_upstreamHandle = nullptr;

/**
 * Whether the file names a string anywhere in its bytes. A mod that links
 * against libpreloader.so carries the soname in its dynamic string table, and
 * one that resolves GlossHook at runtime carries the symbol name as a literal;
 * both mean the library — and with it GlossHook — ends up in the process even
 * though the mod exports none of the upstream entry points.
 */
bool fileMentions(const std::string &path, const char *needle) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return true;
    off_t size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        close(fd);
        return true;
    }
    void *base = mmap(nullptr, (size_t) size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (base == MAP_FAILED) return true;
    const size_t n = strlen(needle);
    const auto *hay = reinterpret_cast<const unsigned char *>(base);
    bool found = memmem(hay, (size_t) size, needle, n) != nullptr;
    munmap(base, (size_t) size);
    return found;
}

/**
 * Whether one mod library drags the upstream runtime — and therefore GlossHook
 * — into the process. Two signals, either of which is enough: a DT_NEEDED on
 * libpreloader.so, or a Gloss entry-point literal (the mod dlsyms it). A file
 * that cannot be read answers yes on purpose: missing the GlossHook pre-claim
 * for a mod that does use it is the stack-overflow crash, while claiming it for
 * a mod that does not only costs a mapping.
 *
 * <p>Exporting {@code PLGetModRegistration} is not a signal on its own any
 * more — those mods are driven here unless they also link the upstream library.
 */
bool needsUpstreamRuntime(const std::string &path) {
    return fileMentions(path, "libpreloader.so") || fileMentions(path, "GlossInit");
}

/**
 * Whether a registration-style mod has to be handed to libpreloader.so rather
 * than driven here. The upstream state such a mod can reach — the thread-local
 * behind {@code NativeMod::current()}, the mod-menu owner stack — lives inside
 * that library and is only set by code it dispatched itself, so a mod that
 * links it is a mod that expects it to be the driver.
 */
bool linksUpstream(const std::string &path) {
    return fileMentions(path, "libpreloader.so");
}

bool scanDirForUpstream(const std::string &dir) {
    DIR *root = opendir(dir.c_str());
    if (root == nullptr) return false;
    bool found = false;
    while (!found) {
        struct dirent *entry = readdir(root);
        if (entry == nullptr) break;
        std::string name = entry->d_name;
        if (name == "." || name == "..") continue;
        std::string full = join(dir, name);
        struct stat st{};
        if (stat(full.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            // A mod directory is one that carries a manifest; anything else is
            // the launcher's own storage and is left alone.
            if (!isRegularFile(join(full, kManifestName))) continue;
            DIR *sub = opendir(full.c_str());
            if (sub == nullptr) continue;
            while (!found) {
                struct dirent *kid = readdir(sub);
                if (kid == nullptr) break;
                std::string kidName = kid->d_name;
                if (!endsWithSo(kidName)) continue;
                found = needsUpstreamRuntime(join(full, kidName));
            }
            closedir(sub);
            continue;
        }
        // A bare plugins/native/libFoo.so is wrapped into its mod directory only
        // when the game launches, so it has no manifest yet — the symbols are
        // already the answer.
        if (endsWithSo(name)) found = needsUpstreamRuntime(full);
    }
    closedir(root);
    return found;
}

/** One mod, no locking. Returns false when it must not be registered. */
bool loadOne(JNIEnv *env, const std::string &libraryPath, const std::string &sourceRoot) {
    // dlopen runs the mod's ELF constructors, and those patch the game. With no game
    // mapping their targets are garbage, and an mprotect() landing inside ART's moving
    // space splits the heap VMA so the next compaction's mremap aborts the process.
    if (Main::baseAddress <= 0) {
        AP_LOGE("libminecraftpe.so is not mapped: refusing %s, its constructors "
                "would patch garbage addresses", libraryPath.c_str());
        return false;
    }
    Entry entry;
    if (!describe(libraryPath, sourceRoot, entry)) return false;

    for (const auto &known : g_mods) {
        if (known.key == entry.key) return true;
    }

    // Mapping happens before the entry-point probe, which needs a handle. A mod
    // that turns out to want the upstream C++ runtime keeps this mapping: the
    // upstream loader dlopens with RTLD_NOLOAD first and reuses it, so its
    // constructors never run twice.
    //
    // A registration-style mod that references upstream symbols *without* a
    // DT_NEEDED on libpreloader.so fails here on purpose. Mapping that library
    // this late is what the GlossHook pre-claim in prepareAsync exists to avoid:
    // GlossInit would patch the linker after shadowhook already did.
    void *handle = acquireHandle(entry.libraryPath);
    if (handle == nullptr) {
        AP_LOGE("dlopen(%s) failed: %s", entry.libraryPath.c_str(), dlerror());
        return false;
    }

    if (dlsym(handle, kSymCppRegistration) != nullptr) {
        if (!linksUpstream(entry.libraryPath)) {
            AlterCppMods::Info info{entry.modId, entry.displayName, entry.author,
                                    entry.version, entry.entryPath, entry.entryFileName,
                                    entry.libraryPath, entry.iconPath, entry.manifestPath,
                                    entry.modRootPath};
            void *session = AlterCppMods::attach(handle, Main::vm, info);
            if (session == nullptr) {
                AP_LOGE("%s exports %s but its load phase did not run",
                        entry.modId.c_str(), kSymCppRegistration);
                // Left mapped on purpose: its constructors already ran.
                return false;
            }
            entry.handle = handle;
            entry.kind = Kind::Cpp;
            entry.cpp = session;
            g_cpp++;
            AP_LOGI("%s -> C++ registration, driven here", entry.modId.c_str());
            g_mods.push_back(std::move(entry));
            return true;
        }
        if (!ensureLevi()) {
            AP_LOGE("%s needs the C++ mod runtime and libpreloader.so is not usable",
                    entry.modId.c_str());
            // Left mapped on purpose: its constructors already ran, and unmapping
            // a library that patched the game would leave those patches pointing
            // into freed trampolines.
            return false;
        }
        jstring jlib = env->NewStringUTF(entry.libraryPath.c_str());
        jstring jroot = env->NewStringUTF(entry.modRootPath.c_str());
        jboolean ok = g_leviLoadMod(env, nullptr, jlib, jroot, nullptr);
        env->DeleteLocalRef(jroot);
        env->DeleteLocalRef(jlib);
        if (ok != JNI_TRUE) {
            AP_LOGE("libpreloader refused %s", entry.modId.c_str());
            return false;
        }
        entry.kind = Kind::Delegated;
        entry.handle = nullptr;
        g_delegated++;
        AP_LOGI("%s -> upstream C++ runtime", entry.modId.c_str());
        g_mods.push_back(std::move(entry));
        return true;
    }

    entry.handle = handle;
    if (auto load = (LifecycleLoadFn) dlsym(handle, kSymLifecycleLoad)) {
        entry.enable = (LifecycleFn) dlsym(handle, kSymLifecycleEnable);
        entry.disable = (LifecycleFn) dlsym(handle, kSymLifecycleDisable);
        entry.unload = (LifecycleFn) dlsym(handle, kSymLifecycleUnload);
        PLModInfo info = toLegacyInfo(entry);
        if (!load(Main::vm, &info)) {
            AP_LOGE("PLMod_Load failed for %s", entry.modId.c_str());
            return false;
        }
        entry.kind = Kind::Lifecycle;
        AP_LOGI("%s -> PLMod_Load", entry.modId.c_str());
    } else if (auto legacy = (LegacyLoadFn) dlsym(handle, kSymLegacyLoad)) {
        PLModInfo info = toLegacyInfo(entry);
        legacy(Main::vm, &info);
        entry.kind = Kind::Legacy;
        AP_LOGI("%s -> LeviMod_Load", entry.modId.c_str());
    } else {
        entry.kind = Kind::NoEntry;
        AP_LOGI("%s -> no entry point, constructors did the work", entry.modId.c_str());
    }

    g_mods.push_back(std::move(entry));
    return true;
}

jboolean nativeLoadMod(JNIEnv *env, jobject, jstring libPath, jstring modRoot) {
    if (!g_modsEnabled) {
        AP_LOGW("native mods are switched off, refusing to load");
        return JNI_FALSE;
    }
    if (libPath == nullptr) return JNI_FALSE;
    const char *lib = env->GetStringUTFChars(libPath, nullptr);
    const char *root = modRoot == nullptr ? nullptr : env->GetStringUTFChars(modRoot, nullptr);
    timespec t0{}, t1{};
    clock_gettime(CLOCK_MONOTONIC, &t0);
    bool ok;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        ok = loadOne(env, lib ? lib : "", root ? root : "");
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    g_lastLoadMs += (t1.tv_sec - t0.tv_sec) * 1000L + (t1.tv_nsec - t0.tv_nsec) / 1000000L;
    if (root != nullptr) env->ReleaseStringUTFChars(modRoot, root);
    if (lib != nullptr) env->ReleaseStringUTFChars(libPath, lib);
    return ok ? JNI_TRUE : JNI_FALSE;
}

void nativeEnableLoadedMods(JNIEnv *, jobject) {
    if (!g_modsEnabled) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    int enabled = 0;
    for (auto &entry : g_mods) {
        if (entry.state != State::Loaded) continue;
        if (entry.kind == Kind::Lifecycle) {
            if (entry.enable == nullptr) {
                AP_LOGW("%s exports no PLMod_Enable", entry.modId.c_str());
                continue;
            }
            if (!entry.enable()) {
                AP_LOGE("PLMod_Enable failed for %s", entry.modId.c_str());
                continue;
            }
        } else if (entry.kind == Kind::Cpp) {
            if (!AlterCppMods::enable(entry.cpp)) {
                AP_LOGE("enable failed for %s", entry.modId.c_str());
                continue;
            }
        } else if (entry.kind == Kind::Delegated) {
            // The upstream enable pass below is what turns these on.
            continue;
        }
        // NoEntry and Legacy mods did their work in PLMod_Load / their
        // constructors, so reaching this pass is them being enabled.
        entry.state = State::Enabled;
        enabled++;
    }
    // The upstream half runs its own enable pass, which also initialises its
    // game hooks; with no rules file configured that call resolves nothing and
    // returns, so only the delegated mods' enable functions run.
    if (g_delegated > 0 && g_leviEnable != nullptr) {
        g_leviEnable(nullptr, nullptr);
    }
    AP_LOGI("enabled %d mods", enabled);
}

void nativeDisableAndUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto it = g_mods.rbegin(); it != g_mods.rend(); ++it) {
        if (it->state == State::Unloaded) continue;
        if (it->kind == Kind::Cpp) {
            if (it->state == State::Enabled && !AlterCppMods::disable(it->cpp)) {
                AP_LOGE("disable failed for %s", it->modId.c_str());
            } else if (!AlterCppMods::unload(it->cpp)) {
                AP_LOGE("unload failed for %s", it->modId.c_str());
            }
            AlterCppMods::release(it->cpp);
            it->cpp = nullptr;
            it->state = State::Unloaded;
            // The library stays mapped, same reason as below.
            continue;
        }
        if (it->kind != Kind::Lifecycle) continue;
        if (it->state == State::Enabled && it->disable != nullptr && !it->disable()) {
            AP_LOGE("PLMod_Disable failed for %s", it->modId.c_str());
            continue;
        }
        if (it->unload != nullptr && !it->unload()) {
            AP_LOGE("PLMod_Unload failed for %s", it->modId.c_str());
            continue;
        }
        it->state = State::Unloaded;
        // The library itself is left mapped: its hooks are inline patches with
        // no undo, so dlclose would leave the game jumping into freed trampolines.
    }
    g_mods.clear();
    g_delegated = 0;
    g_cpp = 0;
}

jstring nativeStats(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    int enabled = 0;
    std::string names;
    for (const auto &entry : g_mods) {
        if (entry.state == State::Enabled) enabled++;
        if (!names.empty()) names += ",";
        names += entry.modId;
    }
    char buf[1024];
    snprintf(buf, sizeof(buf),
             "impl=alter,mods=%d,enabled=%d,cpp=%d,upstream=%d,ms=%ld,names=%s",
             (int) g_mods.size(), enabled, g_cpp, g_delegated, g_lastLoadMs, names.c_str());
    return env->NewStringUTF(buf);
}

JNINativeMethod kMethods[] = {
        {"nativeLoadMod", "(Ljava/lang/String;Ljava/lang/String;)Z", (void *) nativeLoadMod},
        {"nativeEnableLoadedMods", "()V", (void *) nativeEnableLoadedMods},
        {"nativeDisableAndUnloadLoadedMods", "()V", (void *) nativeDisableAndUnload},
        {"nativeStats", "()Ljava/lang/String;", (void *) nativeStats},
};

} // namespace

namespace AlterPreloader {

void setModsEnabled(bool enabled) {
    g_modsEnabled = enabled;
}

bool modsEnabled() {
    return g_modsEnabled;
}

void prepareAsync(const std::string &modsDir, bool eagerUpstream) {
    std::thread([modsDir, eagerUpstream]() {
        timespec t0{}, t1{};
        clock_gettime(CLOCK_MONOTONIC, &t0);
        bool needsUpstream = eagerUpstream;
        if (!needsUpstream && !modsDir.empty()) {
            needsUpstream = scanDirForUpstream(modsDir);
        }
        void *handle = nullptr;
        if (needsUpstream) {
            handle = dlopen("libpreloader.so", RTLD_NOW | RTLD_GLOBAL);
            if (handle == nullptr) {
                AP_LOGE("libpreloader.so needed but unavailable: %s", dlerror());
            }
        }
        clock_gettime(CLOCK_MONOTONIC, &t1);
        AP_LOGI("prepare: upstream=%s, %ld ms", needsUpstream ? "yes" : "no",
                (t1.tv_sec - t0.tv_sec) * 1000L + (t1.tv_nsec - t0.tv_nsec) / 1000000L);
        std::lock_guard<std::mutex> lock(g_prepMutex);
        g_upstreamHandle = handle;
        g_prepDone = true;
        g_prepCv.notify_all();
    }).detach();
}

void *awaitUpstreamHandle() {
    std::unique_lock<std::mutex> lock(g_prepMutex);
    g_prepCv.wait(lock, [] { return g_prepDone; });
    return g_upstreamHandle;
}

bool registerNatives(JNIEnv *env) {
    bool ok = Utils::registerNativeMethods(env, kBridgeClass, kMethods,
            (int) (sizeof(kMethods) / sizeof(kMethods[0]))) == JNI_TRUE;
    if (!ok) {
        // FindClass left a NoClassDefFoundError pending; JNI_OnLoad continues to
        // register NativeCore's methods, which would fail on the sticky exception.
        env->ExceptionClear();
    }
    return ok;
}

std::string stats() {
    std::lock_guard<std::mutex> lock(g_mutex);
    char buf[256];
    snprintf(buf, sizeof(buf), "mods=%d,cpp=%d,upstream=%d,ms=%ld",
             (int) g_mods.size(), g_cpp, g_delegated, g_lastLoadMs);
    return std::string(buf);
}

} // namespace AlterPreloader
