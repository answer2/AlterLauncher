#include <hook/HookInstaller.h>

#include <string>
#include <vector>

#include <android/android_log.h>
#include <hook/GameHooks.h>
#include <hook/RuleState.h>
#include <mcpe/core/GameSDK.h>
#include <memory/FindSignature.h>
#include <memory/RttiLookup.h>
#include <memory/SigScanner.h>
#include <util/HookEngine.h>
#include <util/LongCall.h>

namespace HookInstaller {
namespace {

constexpr const char *kGuestLib = "libminecraftpe.so";

// The 1.26.45.1 answer, verified on device against the offline anchors: slots
// 18..55 of this table match the symbolised 1.21.131 build slot for slot by
// function size, and update(bool) is by far the largest of the group. A rule
// file may override both the class and the index for other builds.
constexpr const char *kClientInstanceClass = "ClientInstance";
constexpr size_t kClientInstanceUpdateSlot = 25;

// The patterns this code shipped with. Every one of them belongs to a single
// pre-1.26 build; the rule file overrides them per Minecraft version, and a rule
// that names no pattern for a function means that function stays unhooked on
// purpose instead of pretending the scan was worth running.
const char *const kPatternMinecraftUpdate =
        "E8 0F ?? ?? FD 7B 01 A9 FD 43 00 91 FC 6F 02 A9 FA 67 03 A9 F8 5F 04 A9 F6 57 05 A9 F4 4F 06 A9 FF 03 0C D1 5A ?? ?? ?? F3 03 00 AA 48 17";
const char *const kPatternSendClientMessage =
        "FF C3 07 D1 FD 7B 1C A9 FD 03 07 91 FC 57 1D A9 F4 4F 1E A9 55 ?? ?? ?? F4 03 03 2A F3 03 00 AA A8 16 ?? ?? E4 03 02 AA E3 03 01 AA A8 83 ?? ?? FF 83 ?? ?? FF 53 ?? ?? FF 23 ?? ?? 45 ?? ?? ?? E6 03 1F 2A E7 03 1F 2A E8 43 00 91 E9 A3 00 91 A0 E3 02 D1 A5 ?? ?? ?? E2 03 01 91 00 ?? ?? ?? 41 ?? ?? ?? E9 23 00 A9 8F ?? ?? ??";
const char *const kPatternAddMessage =
        "? ? ? D1 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 59 D0 3B D5 F3 03 00 AA F4 03 05 AA 28 17 40 F9";
const char *const kPatternJoinMultiplayerGame =
        "FD 7B BA A9 FC 6F 01 A9 FD 03 00 91 FA 67 02 A9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9 FF 43 17 D1 53 ?? ?? ?? F8 03 08 AA 68 16 ?? ?? F7 03 02 AA F4 03 01 AA F5 03 00 AA A8 83";

// One entry of the byte-signature table: the key it is looked up under in the
// rule file, the name it is reported as, the built-in pattern, and the pair of
// handler/original pointers the hook engine fills in.
struct SignatureHook {
    const char *key;
    const char *pretty;
    const char *fallback;
    void *handler;
    void **real;
    // Bare class name for the RTTI fallback, or "" when the function is not
    // virtual and a failed body patch simply means "skip this build".
    const char *rttiClass;
};

// Checks that a resolved vtable really has `index` slots and hands back the
// function stored in it, or 0.
uintptr_t resolveVirtual(uintptr_t vtable, size_t index, size_t slots) {
    if (vtable == 0) return 0;
    if (slots <= index) {
        LOGW("the vtable has only %zu virtual slots, index %zu is out of range", slots, index);
        return 0;
    }
    uintptr_t fn = RttiLookup::slotAt(vtable, index);
    std::vector<RttiLookup::Region> regions = RttiLookup::regionsOf(kGuestLib);
    if (!RttiLookup::inRegions(regions, fn, true)) {
        LOGW("slot %zu (0x%lx) is not executable", index, (unsigned long) fn);
        return 0;
    }
    return fn;
}

// Which class holds a virtual and at which index. The rule file may name both;
// otherwise the built-in pair applies.
struct VirtualTarget {
    std::string className;
    size_t slot;
    const char *key;
};

VirtualTarget virtualTarget(const char *key, const char *fallbackClass, size_t fallbackSlot) {
    VirtualTarget target;
    target.className = fallbackClass;
    target.slot = fallbackSlot;
    target.key = key;
    if (!RuleState::hasRule) return target;
    if (!RuleState::rule.lookupVtable(key, &target.className, &target.slot)) {
        LOGW("rule '%s' names no %s, using %s:%zu", RuleState::rule.name.c_str(), key, fallbackClass,
             fallbackSlot);
        target.className = fallbackClass;
        target.slot = fallbackSlot;
    }
    return target;
}

void hookBySignature(const ModuleInfo &info, const SignatureHook &entry) {
    // An explicit offset is the strongest handle the rule file can carry: no
    // scan, no pattern to rot. It is also build-specific, which is why it only
    // ever sits inside a version window.
    uintptr_t rva = 0;
    if (RuleState::hasRule && RuleState::rule.lookupOffset(entry.key, &rva)) {
        uintptr_t address = info.base + rva;
        LOGD("%s: rule '%s' pins it at 0x%llx (library base + 0x%llx)", entry.pretty,
             RuleState::rule.name.c_str(), (unsigned long long) address,
             (unsigned long long) rva);
        // Seed the scanner before the patch: the pin already answers sig.<key>,
        // and once the prologue is rewritten the pattern no longer matches.
        SigScanner::publish(entry.key, address);
        hookGameFunction(address, entry.handler, entry.real, entry.pretty);
        return;
    }
    std::string pattern;
    if (RuleState::hasRule) {
        pattern = RuleState::rule.lookupSignature(entry.key);
        if (pattern.empty()) {
            LOGW("rule '%s' defines no pattern or offset for %s: this Minecraft build is not "
                 "covered, hook skipped",
                 RuleState::rule.name.c_str(), entry.pretty);
            return;
        }
    } else {
        pattern = entry.fallback;
    }
    uintptr_t found = FindSignature::findSignatureInModule(info, pattern.c_str());
    if (found != 0) {
        LOGD("%s: pattern matched at 0x%lx (library base + 0x%llx)", entry.pretty,
             (unsigned long) found, (unsigned long long) (found - info.base));
        // Same reason as the pin path: publish before the prologue is rewritten,
        // so the SDK routes keep an address that a rescan could no longer find.
        SigScanner::publish(entry.key, found);
    }
    if (hookGameFunction(found, entry.handler, entry.real, entry.pretty)) return;

    // The body refused the patch (island dead band + unrelocatable prologue, or a
    // hook-engine failure). If this is a virtual function — the rule author names
    // its class in the table — the RTTI chain still knows who dispatches to it:
    // find the vtable slot equal to the matched address and patch THAT instead.
    // One pointer store, no trampoline, no island, MAP_PRIVATE so process-local.
    if (found != 0 && entry.rttiClass != nullptr && entry.rttiClass[0] != '\0') {
        uintptr_t table = 0;
        size_t slot = 0;
        uintptr_t original = 0;
        if (RttiLookup::findVirtualSlotForFunction(kGuestLib, entry.rttiClass, found, &table,
                                                   &slot) &&
            RttiLookup::patchVtableSlot(table, slot,
                                        reinterpret_cast<uintptr_t>(entry.handler), &original) &&
            // The resolver guarantees the slot IS the matched body or its deleting-
            // destructor wrapper (which BLs into it), so any nonzero read-back is the
            // untouched table value and forwarding to it keeps both call kinds
            // covered — same semantics as Levi's body patch.
            original != 0) {
            *entry.real = reinterpret_cast<void *>(original);
            LOGD("hooked %s at slot %zu of vtable %p by vtable patch (body unpatchable)",
                 entry.pretty, slot, reinterpret_cast<void *>(table));
        } else if (original != 0 && original != found) {
            RttiLookup::patchVtableSlot(table, slot, original, nullptr);  // restore
            LOGW("%s: vtable slot %zu changed under us, restored", entry.pretty, slot);
        } else {
            LOGW("%s: no vtable slot of '%s' dispatches to 0x%lx; hook skipped", entry.pretty,
                 entry.rttiClass, (unsigned long) found);
        }
    }
}

}  // namespace

bool hookGameFunction(uintptr_t address, void *handler, void **real, const char *name) {
    if (address == 0) {
        LOGW("signature not found for %s: this Minecraft build is not covered, hook skipped", name);
        return false;
    }
    // Ask the address space before asking the hook engine. On a library mapped as
    // one huge PT_LOAD the trampoline island has nowhere to go, and that failure
    // is about where the function sits, not about whether the pattern was right —
    // without this line the log blames a stale signature for an unmappable hook.
    std::string reach;
    if (!RttiLookup::islandReachable(address, &reach)) {
        // The island must sit within +/-128 MB of the target because shadowhook
        // gets there with a direct branch, and inside libminecraftpe.so no free
        // page does. LongCall drops the direct branch: it places its bridge on a
        // page of our own and reaches the handler through a register, which works
        // at any address in the address space.
        std::string why;
        if (LongCall::hook(address, handler, real, &why)) {
            LOGD("hooked %s at 0x%lx by long-reach patch (%s)", name, (unsigned long) address,
                 why.c_str());
            return true;
        }
        LOGW("cannot inline-hook %s at 0x%lx: %s; long-reach patch refused: %s", name,
             (unsigned long) address, reach.c_str(), why.c_str());
        return false;
    }
    if (HookEngine::hookFuncAddr((void *) address, handler, real) == nullptr) {
        LOGE("failed to hook %s at 0x%lx: %s", name, (unsigned long) address,
             HookEngine::lastError());
        return false;
    }
    LOGD("hooked %s at 0x%lx (%s)", name, (unsigned long) address, reach.c_str());
    return true;
}

// ---------------------------------------------------------------------------
// Runtime vtable resolution (the version-portable path)
//
// Byte signatures are pinned to one build and Mojang recompiles these functions
// on every release, so the pattern set can miss entirely and no game hook
// installs. RTTI survives: the guest keeps `14ClientInstance` type names in
// .rodata even though .symtab is stripped, so the class's vtable can be found by
// walking name -> typeinfo -> vtable in the live mappings. Hooking a virtual
// function hands us `this` for free, which is the only reliable way to get a
// handle on the running game.
// ---------------------------------------------------------------------------
void installRttiHooks() {
    size_t slots = 0;
    GameHooks::guiDataVtable = RttiLookup::findVtable(kGuestLib, "GuiData", NULL);
    // Collect every validated GuiData table (primary + aux/subobject variants):
    // on 1.26.x the live instance carries one of the aux tables, so identity
    // checks need the full set. The primary answer above still seeds gdVtable.
    uintptr_t gdTables[8];
    size_t gdTableCount = RttiLookup::findVtables(kGuestLib, "GuiData", gdTables, 8);
    LOGD("GuiData RTTI: %zu validated table(s), primary %p", gdTableCount,
         reinterpret_cast<void *>(GameHooks::guiDataVtable));

    VirtualTarget update_target =
            virtualTarget("clientInstanceUpdate", kClientInstanceClass, kClientInstanceUpdateSlot);
    LOGD("resolving %s:%zu through RTTI", update_target.className.c_str(), update_target.slot);
    GameHooks::clientInstanceVtable =
            RttiLookup::findVtable(kGuestLib, update_target.className, &slots);
    uintptr_t update = resolveVirtual(GameHooks::clientInstanceVtable, update_target.slot, slots);
    if (update == 0) {
        LOGW("%s::update is not resolvable by RTTI in this build", update_target.className.c_str());
        GameHooks::clientInstanceVtable = 0;
        return;
    }
    LOGD("resolved %s vtable %p (%zu slots), update(bool) at 0x%lx",
         update_target.className.c_str(),
         reinterpret_cast<void *>(GameHooks::clientInstanceVtable), slots, (unsigned long) update);

    // The vtable slot is patched rather than the function body: an inline hook
    // needs a free page within +/-128 MB of the target for the trampoline island,
    // and inside the guest's single 298 MB executable mapping there is none.
    std::string reachReason;
    bool reachable = RttiLookup::islandReachable(update, &reachReason);
    LOGD("an inline hook of update at 0x%lx would be %s: %s", (unsigned long) update,
         reachable ? "possible" : "impossible", reachReason.c_str());

    // For a virtual function the slot is the natural place to intercept anyway:
    // no trampoline, and the mapping is MAP_PRIVATE so the patch is local to
    // this process.
    uintptr_t original = 0;
    if (!RttiLookup::patchVtableSlot(GameHooks::clientInstanceVtable, update_target.slot,
                                     reinterpret_cast<uintptr_t>(GameHooks::clientInstanceUpdateHook),
                                     &original)) {
        LOGE("failed to patch %s::update slot %zu", update_target.className.c_str(),
             update_target.slot);
        GameHooks::clientInstanceVtable = 0;
        return;
    }
    if (original != update) {
        LOGE("%s::update slot changed under us: expected 0x%lx, found 0x%lx",
             update_target.className.c_str(), (unsigned long) update, (unsigned long) original);
        RttiLookup::patchVtableSlot(GameHooks::clientInstanceVtable, update_target.slot, original,
                                    nullptr);
        GameHooks::clientInstanceVtable = 0;
        return;
    }
    GameHooks::clientInstanceUpdateReal =
            reinterpret_cast<uintptr_t (*)(void *, bool)>(original);
    LOGD("hooked %s::update: original 0x%lx -> handler %p", update_target.className.c_str(),
         (unsigned long) original,
         reinterpret_cast<void *>(GameHooks::clientInstanceUpdateHook));

    // Hand the validated tables to the SDK layer so its getters resolve through
    // exactly what the hooks proved live, not a second RTTI walk.
    GameSdk::setClientInstanceTables(GameHooks::clientInstanceVtable, slots,
                                     GameHooks::guiDataVtable);
    if (gdTableCount != 0) GameSdk::setGuiDataTables(gdTables, gdTableCount);

    // ClientInstance::isShowingMenu — virtual, so it goes through the SAME vtable
    // we just validated. The slot index comes from the rule file only (Levi's
    // windows name it 150/151; there is no safe built-in guess, and a wrong slot
    // patched blindly would corrupt every call through it). No rule, no key →
    // skipped with a log, exactly like the sig path.
    if (RuleState::hasRule) {
        VirtualTarget menu_target;
        std::string cls;
        size_t slot = 0;
        if (RuleState::rule.lookupVtable("isShowingMenu", &cls, &slot)) {
            if (cls == update_target.className) {
                menu_target.className = cls;
                menu_target.slot = slot;
                menu_target.key = "isShowingMenu";
            } else {
                LOGW("isShowingMenu names class '%s' but update resolved '%s': not one table, "
                     "skipping the vtable patch",
                     cls.c_str(), update_target.className.c_str());
            }
        } else {
            LOGD("rule '%s' defines no vtable.isShowingMenu: menu-state flag stays at its "
                 "PauseMenu/HudScreen estimate",
                 RuleState::rule.name.c_str());
        }
        if (!menu_target.className.empty()) {
            uintptr_t fn = resolveVirtual(GameHooks::clientInstanceVtable, menu_target.slot, slots);
            uintptr_t orig2 = 0;
            if (fn != 0 &&
                RttiLookup::patchVtableSlot(
                        GameHooks::clientInstanceVtable, menu_target.slot,
                        reinterpret_cast<uintptr_t>(GameHooks::clientInstanceIsShowingMenuHook),
                        &orig2) &&
                orig2 == fn) {
                GameHooks::clientInstanceIsShowingMenuReal = reinterpret_cast<bool (*)(void *)>(orig2);
                LOGD("hooked %s::isShowingMenu slot %zu: original 0x%lx",
                     menu_target.className.c_str(), menu_target.slot, (unsigned long) orig2);
            } else if (fn != 0) {
                if (orig2 != 0 && orig2 != fn) {
                    RttiLookup::patchVtableSlot(GameHooks::clientInstanceVtable, menu_target.slot,
                                                orig2, nullptr);  // restore what we touched
                }
                LOGW("isShowingMenu slot %zu (0x%lx) could not be patched safely",
                     menu_target.slot, (unsigned long) fn);
            }
        }
    }
}

// The pinned-prologue path, for the functions RTTI cannot reach because they are
// not virtual. `Minecraft::update` is only tried when RTTI did not hand us the
// game instance, so a build is never patched twice for the same purpose.
void installSignatureHooks() {
    ModuleInfo info = FindSignature::getModuleInfo(kGuestLib);
    size_t scannedBytes = 0;
    for (const auto &r : info.regions) scannedBytes += r.second;
    LOGD("scanning %s: %zu executable region(s), %zu bytes total (first at 0x%lx, %zu bytes) for %s",
         kGuestLib, info.regions.size(), scannedBytes, (unsigned long) info.base, info.size,
         RuleState::hasRule ? "the patterns named by the active rule" : "the built-in patterns");

    if (GameHooks::clientInstanceVtable == 0) {
        const SignatureHook update = {"minecraftUpdate",
                                      "Minecraft::update",
                                      kPatternMinecraftUpdate,
                                      (void *) GameHooks::minecraftUpdateHook,
                                      (void **) &GameHooks::minecraftUpdateReal};
        hookBySignature(info, update);
    } else {
        LOGD("Minecraft::update signature skipped: the RTTI hook already owns the game instance");
    }

    const SignatureHook entries[] = {
            {"guiDataSendClientMessage", "GuiData::sendClientMessage", kPatternSendClientMessage,
             (void *) GameHooks::guiDataSendClientMessageHook,
             (void **) &GameHooks::guiDataSendClientMessageReal, ""},
            {"guiDataAddMessage",        "GuiData::addChatMessage(wrapper)", kPatternAddMessage,
             (void *) GameHooks::guiDataAddMessageHook,
             (void **) &GameHooks::guiDataAddMessageReal, ""},
            {"joinMultiplayerGame",      "MinecraftGame::joinMultiplayerGame",
             kPatternJoinMultiplayerGame,
             (void *) GameHooks::joinMultiplayerGameHook,
             (void **) &GameHooks::joinMultiplayerGameReal, ""},
            // M4 menu-state hooks. No built-in fallback pattern: these keys only
            // exist in the levi-* rule windows, and outside them the hook is
            // skipped with a log rather than guessed. The dtors are virtual, so
            // they name their class for the vtable-slot fallback when the body
            // patch is refused (no island + unrelocatable prologue words).
            {"pauseMenuDtor",            "PauseMenu::~PauseMenu",           "",
             (void *) GameHooks::pauseMenuDtorHook,
             (void **) &GameHooks::pauseMenuDtorReal, "PauseMenu"},
            {"pauseMenuOpen",            "PauseMenu::_openPauseScreen",     "",
             (void *) GameHooks::pauseMenuOpenHook,
             (void **) &GameHooks::pauseMenuOpenReal, ""},
            {"hudScreenDtor",            "HudScreen::~HudScreen",           "",
             (void *) GameHooks::hudScreenDtorHook,
             (void **) &GameHooks::hudScreenDtorReal, "HudScreen"},
            {"hudScreenOpen",            "HudScreen::_openHudScreen",       "",
             (void *) GameHooks::hudScreenOpenHook,
             (void **) &GameHooks::hudScreenOpenReal, ""},
    };
    for (size_t i = 0; i < sizeof(entries) / sizeof(entries[0]); i++) {
        hookBySignature(info, entries[i]);
    }

    // Publish the chat route to the SDK layer: the real (pre-hook or bridge)
    // addMessage if the hook installed, else the rule-pinned address itself.
    if (reinterpret_cast<uintptr_t>(GameHooks::guiDataAddMessageReal) != 0) {
        GameSdk::addMessageRouteRef() =
                reinterpret_cast<uintptr_t>(GameHooks::guiDataAddMessageReal);
    } else {
        uintptr_t rva = 0;
        if (RuleState::hasRule &&
            RuleState::rule.lookupOffset(std::string("guiDataAddMessage"), &rva)) {
            GameSdk::addMessageRouteRef() = info.base + rva;
        } else if (RuleState::hasRule) {
            // No hook bridge and no offset pin: the BedrockTools route. The
            // pattern scan resolves an address we can CALL even though the
            // island dead band forbids PATCHING it. One cached scan.
            uintptr_t sig = SigScanner::resolve(std::string("guiDataAddMessage"));
            if (sig != 0) GameSdk::addMessageRouteRef() = sig;
        }
    }
    LOGD("SigScanner: %zu/%zu signature key(s) resolved so far", SigScanner::hitCount(),
         SigScanner::scannedCount());
}

}  // namespace HookInstaller
