// Standalone regression: link the real mcpe/actor/Actor.cpp and memory/HookRules.cpp
// with -ffunction-sections -fdata-sections -Wl,--gc-sections -llog.
// NDK aarch64: -std=c++17 --target=aarch64-linux-android21 -static-libstdc++.
// Only rule injection and guest-memory helpers are replaced; no game is needed.
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <mcpe/actor/Actor.h>
#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>

#include <cassert>
#include <cstddef>
#include <cstdio>
#include <cstring>

namespace {
struct Supplies {
    uintptr_t padding = 0;
    int32_t selected = 4;
} supplies;

struct PlayerObject {
    uintptr_t vptr = 0;
    uintptr_t suppliesPtr = 0;
} object;

uintptr_t heldItem = 0;
uintptr_t table[3] = {};
const HookRules::Rule *activeRule = nullptr;
int getterCalls = 0;
int pinLookups = 0;
int suppliesReads = 0;

// The carried-item virtual takes this alone, not an inventory slot.
uintptr_t carriedItem(void *self) {
    assert(self == &object);
    ++getterCalls;
    return reinterpret_cast<uintptr_t>(&heldItem);
}

uintptr_t unexpectedGetter(void *) {
    assert(false && "wrong virtual slot called");
    return 0;
}

void expectHeld(mcpe::Handle handle) {
    assert(handle.valid());
    assert(handle.ptr == reinterpret_cast<uintptr_t>(&heldItem));
    assert(std::strcmp(handle.source, "vcall") == 0);
}
}  // namespace

namespace GameSdk {
const HookRules::Rule *rule() { return activeRule; }
}  // namespace GameSdk

namespace mcpe { namespace detail {
bool inWritable(uintptr_t address) {
    return address == reinterpret_cast<uintptr_t>(&object) ||
           address == reinterpret_cast<uintptr_t>(&supplies) ||
           address == reinterpret_cast<uintptr_t>(&heldItem);
}

bool inGuestLib(uintptr_t address) {
    return address == reinterpret_cast<uintptr_t>(table);
}

bool inExec(uintptr_t address) {
    return address == reinterpret_cast<uintptr_t>(&carriedItem) ||
           address == reinterpret_cast<uintptr_t>(&unexpectedGetter);
}

bool safeReadPtr(uintptr_t address, uintptr_t offset, uintptr_t *out) {
    if (address != reinterpret_cast<uintptr_t>(&object)) return false;
    if (offset == offsetof(PlayerObject, vptr)) {
        *out = object.vptr;
        return true;
    }
    if (offset == offsetof(PlayerObject, suppliesPtr)) {
        ++suppliesReads;
        *out = object.suppliesPtr;
        return true;
    }
    return false;
}

bool safeReadI32(uintptr_t address, uintptr_t offset, int32_t *out) {
    if (address != reinterpret_cast<uintptr_t>(&supplies) ||
        offset != offsetof(Supplies, selected)) return false;
    ++suppliesReads;
    *out = supplies.selected;
    return true;
}

bool dataPin(const char *key, uintptr_t *offset) {
    ++pinLookups;
    return activeRule && activeRule->lookupDataOffset(key, offset);
}
}}  // namespace mcpe::detail

int main() {
    static_assert(sizeof(uintptr_t) == 8, "This regression targets aarch64");
    table[0] = reinterpret_cast<uintptr_t>(&unexpectedGetter);
    table[1] = reinterpret_cast<uintptr_t>(&carriedItem);
    table[2] = reinterpret_cast<uintptr_t>(&unexpectedGetter);
    object.vptr = reinterpret_cast<uintptr_t>(table);
    const mcpe::LocalPlayer player(reinterpret_cast<uintptr_t>(&object));
    HookRules::Rule rule;
    rule.vtable["playerGetCarriedItem"] = "Player:1";
    activeRule = &rule;

    // No supplies pointer or data pins: getHeldItem must dispatch directly.
    assert(rule.dataOffsets.empty());
    expectHeld(player.getHeldItem());
    assert(getterCalls == 1);
    assert(pinLookups == 0 && suppliesReads == 0);
    assert(!player.getItemStack(supplies.selected).valid());
    assert(getterCalls == 1);

    activeRule = nullptr;
    assert(!player.getHeldItem().valid());
    assert(getterCalls == 1);
    activeRule = &rule;
    rule.vtable.clear();
    assert(!player.getHeldItem().valid());
    assert(getterCalls == 1);
    rule.vtable["playerGetCarriedItem"] = "Player:1";

    object.suppliesPtr = reinterpret_cast<uintptr_t>(&supplies);
    rule.dataOffsets["playerSuppliesField"] = offsetof(PlayerObject, suppliesPtr);
    // A supplies pointer alone cannot establish which slot is selected.
    assert(!player.getItemStack(supplies.selected).valid());
    assert(getterCalls == 1);
    rule.dataOffsets["suppliesSelectedItemSlot"] = offsetof(Supplies, selected);

    const int pinsBeforeNegative = pinLookups;
    assert(!player.getItemStack(-1).valid());
    assert(pinLookups == pinsBeforeNegative);
    assert(!player.getItemStack(supplies.selected + 1).valid());
    assert(getterCalls == 1);
    expectHeld(player.getItemStack(supplies.selected));
    assert(getterCalls == 2);

    // Follow the supplies selection on each call, not a cached slot.
    supplies.selected = 7;
    assert(!player.getItemStack(4).valid());
    assert(getterCalls == 2);
    expectHeld(player.getItemStack(7));
    assert(getterCalls == 3);

    activeRule = nullptr;
    assert(!player.getItemStack(7).valid());
    assert(!mcpe::LocalPlayer().getHeldItem().valid());
    assert(!mcpe::LocalPlayer().getItemStack(7).valid());
    assert(getterCalls == 3);
    std::puts("PASS: Actor inventory: held without supplies, missing rule/pin, "
              "selected-slot-only dispatch, this pointer and getter call counts");
}
