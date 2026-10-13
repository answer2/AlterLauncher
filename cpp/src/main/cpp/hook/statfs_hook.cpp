// statvfs / statfs 家族的兜底 Hook (无 JNI 版本)。
//
// 背景: Bedrock 原生层对"磁盘上不存在的来宾路径"调用 statvfs 会失败, 引擎把这个失败
// 当成 "0 字节可用", 从而弹出 "存储空间即将不足" 并限制功能。
// libminecraftpe.so 通过 libc 直接调用, Java 层 Hook 看不到, 所以在 libc 层处理。
//
// 设计要点:
//  1. 先原样调用原函数。路径真实存在时行为完全不变。
//  2. 只有 "调用失败 且 路径属于来宾包 且 不在隔离根目录内" 时, 才改查隔离根目录。
//     statvfs/statfs 返回的是分区信息, 同一分区的剩余空间相同, 不需要精确重定向。
//  3. 热路径里没有 JNI / FindClass: 根目录由 Java 在得知后调用
//     SetStatFsGuestRoot() 推送一次, 这里只读一个带锁的 std::string 副本。
//  4. 根目录还没推送时直接放行, 不会比不装 Hook 更糟。

#include <errno.h>
#include <dlfcn.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>
#include <string.h>
#include <mutex>
#include <string>
#include <android/log.h>
#include <util/HookEngine.h>

#define STATFS_TAG "StatFsHook"

namespace {

    const char* const GUEST_PACKAGE = "com.mojang.minecraftpe";

    int (*orig_statvfs)(const char*, struct statvfs*) = nullptr;
    int (*orig_statvfs64)(const char*, struct statvfs64*) = nullptr;
    int (*orig_statfs)(const char*, struct statfs*) = nullptr;
    int (*orig_statfs64)(const char*, struct statfs64*) = nullptr;

    std::mutex g_rootLock;
    std::string g_guestRoot;  // 这次安装的隔离数据根目录, 由 Java 推送

    std::string guestRoot() {
        std::lock_guard<std::mutex> lock(g_rootLock);
        return g_guestRoot;
    }

// 路径带着来宾包名, 并且不在隔离根目录里 (隔离目录名本身就含来宾包名, 属于自己的树)。
    bool isGuestPath(const char* path, const std::string& root) {
        if (path == nullptr || strstr(path, GUEST_PACKAGE) == nullptr) {
            return false;
        }
        return strncmp(path, root.c_str(), root.size()) != 0;
    }

    template <typename Fn, typename Buf>
    int redirectedStat(Fn orig, const char* path, Buf* buf) {
        if (orig == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        const int result = orig(path, buf);
        if (result == 0) {
            return 0;  // 路径存在, 原样放行
        }
        const int savedErrno = errno;

        const std::string root = guestRoot();
        if (root.empty() || !isGuestPath(path, root)) {
            errno = savedErrno;
            return result;
        }

        const int fallback = orig(root.c_str(), buf);
        // 诊断日志: 只在兜底发生时打印。确认弹窗修好后可以删掉。
        __android_log_print(ANDROID_LOG_INFO, STATFS_TAG,
                            "stat '%s' failed (errno=%d), fell back to guest root -> %s",
                            path != nullptr ? path : "(null)", savedErrno,
                            fallback == 0 ? "ok" : "failed");
        if (fallback == 0) {
            return 0;
        }
        errno = savedErrno;
        return -1;
    }

    int my_statvfs(const char* p, struct statvfs* b)     { return redirectedStat(orig_statvfs, p, b); }
    int my_statvfs64(const char* p, struct statvfs64* b) { return redirectedStat(orig_statvfs64, p, b); }
    int my_statfs(const char* p, struct statfs* b)       { return redirectedStat(orig_statfs, p, b); }
    int my_statfs64(const char* p, struct statfs64* b)   { return redirectedStat(orig_statfs64, p, b); }

// LP64 上 statvfs64 / statfs64 常常是对应无后缀符号的别名, 同一地址在 UNIQUE 模式下
// 只能挂一次, 这里记录已挂的地址并跳过重复。
    void* g_hookedTargets[8] = {};
    int g_hookedCount = 0;

    void hook(const char* sym, void* replacement, void** original) {
        // 来宾进程通过 linker namespace 解析 libc, 按 "libc.so" + 符号名查找会返回 ENOENT。
        // 先用 dlsym 取真实地址, 再按地址 Hook (与 fake_file.cpp hook fopen 的做法一致)。
        void* target = dlsym(RTLD_DEFAULT, sym);
        if (target == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, STATFS_TAG, "dlsym %s failed, skipped", sym);
            return;
        }
        for (int i = 0; i < g_hookedCount; i++) {
            if (g_hookedTargets[i] == target) {
                __android_log_print(ANDROID_LOG_INFO, STATFS_TAG,
                                    "%s shares an address with a hooked symbol, skipped", sym);
                return;
            }
        }
        void* stub = HookEngine::hookAddr(target, replacement, original);
        if (stub == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, STATFS_TAG, "hook %s skipped (%s)",
                                sym, HookEngine::lastError());
            return;
        }
        if (g_hookedCount < 8) {
            g_hookedTargets[g_hookedCount++] = target;
        }
        __android_log_print(ANDROID_LOG_INFO, STATFS_TAG, "hooked %s at %p", sym, target);
    }

}  // namespace

// 由 Java 在得知隔离根目录后调用一次 (例如 IOManager 打印 "guest data root" 的地方)。
void SetStatFsGuestRoot(const char* root) {
    std::lock_guard<std::mutex> lock(g_rootLock);
    g_guestRoot = root != nullptr ? root : "";
    __android_log_print(ANDROID_LOG_INFO, STATFS_TAG, "guest root = '%s'", g_guestRoot.c_str());
}

void InstallStatFsHooks() {
    hook("statvfs",   (void*) my_statvfs,   (void**) &orig_statvfs);
    hook("statvfs64", (void*) my_statvfs64, (void**) &orig_statvfs64);
    hook("statfs",    (void*) my_statfs,    (void**) &orig_statfs);
    hook("statfs64",  (void*) my_statfs64,  (void**) &orig_statfs64);
}