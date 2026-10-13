#include <mcpe/world/Dimension.h>

#include <android/log.h>
#include <cstring>
#include <atomic>
#include <string>

#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>

namespace mcpe {
using detail::safeReadI32;
using detail::dataPin;

// The id is a plain int32 member, but its offset is a heap-layout fact no
// reference project agrees on (+0x140 and +0x138 are what two of them claim for
// other versions). So the offset is DERIVED rather than pinned: the rule names a
// vtable slot, and detail::verifiedGetterOffset reads that slot's two instruction
// words and accepts them only if they are exactly `ldr w0,[x0,#imm]; ret`. That
// checks the field's address on this build, at runtime, with no call and no
// cross-version guess. A slot that shifted - or is a stub like `mov w0,#-1` -
// simply fails the shape test and the accessor returns false.
bool Dimension::getDimensionId(int *out) const {
    if (self_ == 0 || out == nullptr) return false;

    uintptr_t off = 0;
    if (!dataPin("dimensionIdField", &off)) {
        const HookRules::Rule *rule = GameSdk::rule();
        size_t slot = 0;
        if (rule == nullptr ||
            !rule->lookupVtable(std::string("dimensionGetId"), nullptr, &slot))
            return false;
        // Derived name first. The live object is an OverworldDimension, so its own
        // vptr is OverworldDimension's address point and verifiedGetterOffset - which
        // only ever decodes the table the object ACTUALLY carries - would refuse a
        // lookup under "Dimension" alone. Both tables put slot 3 at the same body
        // (0xf97c108, `ldr w0,[x0,#0x170]; ret`), so whichever matches reads the same
        // field; trying the base name second keeps this working on a build where the
        // object really is a plain Dimension.
        static const char *const kClasses[] = {"OverworldDimension", "Dimension"};
        bool decoded = false;
        for (size_t i = 0; i < 2 && !decoded; i++)
            decoded = detail::verifiedGetterOffset(self_, kClasses[i], slot, false, &off);
        if (!decoded) return false;   // offset 0 is a legitimate field position
        static std::atomic<bool> logged(false);
        if (!logged.exchange(true))
            __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                                "dimension probe: slot %zu decodes to ldr w0,[x0,#0x%x]; ret "
                                "- pin dat.dimensionIdField = d0x%x",
                                slot, (unsigned) off, (unsigned) off);
    }

    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

}  // namespace mcpe
