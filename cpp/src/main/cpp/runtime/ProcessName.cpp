#include <runtime/ProcessName.h>

#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstdint>
#include <cstring>

#include <android/android_log.h>

namespace ProcessName {
namespace {

bool findStackTop(uintptr_t *top) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        return false;
    }
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), maps)) {
        if (strstr(line, "[stack]") != nullptr) {
            unsigned long start = 0;
            unsigned long end = 0;
            if (sscanf(line, "%*s %lx-%lx", &start, &end) == 2) {
                *top = (uintptr_t) end;
                found = true;
            }
            break;
        }
    }
    fclose(maps);
    return found;
}

}  // namespace

void spoof(const std::string &target) {
    if (target.empty() || target.size() > 128) {
        LOGE("setProcessName: bad target");
        return;
    }

    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) {
        LOGE("setProcessName: cmdline unreadable");
        return;
    }
    char original[512];
    ssize_t len = read(fd, original, sizeof(original) - 1);
    close(fd);
    if (len <= 0) {
        LOGE("setProcessName: cmdline empty");
        return;
    }
    original[len] = '\0';
    if (strcmp(original, target.c_str()) == 0) {
        LOGI("setProcessName: already %s", target.c_str());
        return;
    }
    size_t argv0Len = strlen(original) + 1;
    if (target.size() + 1 > argv0Len) {
        // Growing past the old argv0 slot would eat the next argument; the
        // spoof is cosmetic, so refuse instead of corrupting the block.
        LOGE("setProcessName: target longer than current argv0");
        return;
    }

    uintptr_t stackTop = 0;
    if (!findStackTop(&stackTop)) {
        LOGE("setProcessName: no [stack] mapping");
        return;
    }
    char probe;
    uintptr_t sp = (uintptr_t) &probe;
    // The argv strings sit within the first kilobytes under the stack top;
    // scan a generous 64 KiB window, 16-byte aligned, above the current sp.
    uintptr_t begin = stackTop > 65536 ? stackTop - 65536 : sp;
    if (begin < sp) {
        begin = sp;
    }
    begin &= ~(uintptr_t) 15;
    for (uintptr_t addr = begin; addr + argv0Len <= stackTop; addr += 16) {
        if (memcmp((const void *) addr, original, argv0Len) == 0) {
            char *dst = (char *) addr;
            memcpy(dst, target.data(), target.size());
            memset(dst + target.size(), 0, argv0Len - target.size());
            LOGI("setProcessName: cmdline spoofed to %s", target.c_str());
            return;
        }
    }
    LOGE("setProcessName: argv block not found in stack window");
}

}  // namespace ProcessName
