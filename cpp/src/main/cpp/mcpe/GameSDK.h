#ifndef ALTER_LAUNCHER_MCPE_GAME_SDK_H
#define ALTER_LAUNCHER_MCPE_GAME_SDK_H

#include <stddef.h>
#include <stdint.h>

#include <string>

namespace HookRules { struct Rule; }

// The native game-SDK surface, modelled on the reference Android SDKs
// (D:\逆向分析\1\cpp Divine Realm + Horion's method maps) but resolved through
// OUR OWN handles: the RTTI-parsed ClientInstance vtable and the version-gated
// rule file. Every getter is a ladder:

//   1. an explicit rule pin (off.<key> field offset / vtable.<key> slot),
//   2. an RTTI or member-vtable discovery step that works with no pin at all,
//   3. failure -> 0 plus one log line; nothing is ever guessed.

// Nothing here patches anything. Acquisition is read-only except for the two
// virtual calls the pins authorize (getGuiData / getMinecraftGame), and those
// only run after the slot was validated to point into the guest's executable
// mapping.
namespace GameSdk {

// Called once from load() after Main::baseAddress is set. Captures the library
// base for provenance logs.
void init(uintptr_t guestBase);

// main.cpp owns the parsed rule and the RTTI tables; it injects them here so
// both hook installation and the SDK read exactly the same data.
void setRule(const HookRules::Rule *rule, bool hasRule);
void setClientInstanceTables(uintptr_t vtable, size_t slots, uintptr_t guiDataVtable);

// Every RTTI-validated GuiData table (primary first). main.cpp collects them
// once at install time; identity checks accept a candidate whose own vtable
// pointer matches ANY of them: on 1.26.x the live instance often carries an
// aux/subobject table, so the primary alone would reject a genuine GuiData.
void setGuiDataTables(const uintptr_t *tables, size_t count);

// The ClientInstance as captured by its update hook (the authoritative owner).
// Published via setClientInstance on first capture; until then every derived
// getter returns 0.
uintptr_t clientInstance();
void setClientInstance(uintptr_t ci);

// MinecraftGame when the legacy Minecraft::update hook captured it.
void setMinecraftGameFromHook(uintptr_t mg);

// MinecraftGame acquisition. Ladder: off.minecraftGameField=<rva> read from CI;
// else vtable.getMinecraftGame=Class:N virtual call; else 0.
uintptr_t minecraftGame();

// GuiData acquisition. Ladder: vtable.getGuiData virtual call; else
// off.guiDataField=<rva> read from CI; else a read-only member scan against the
// RTTI-found GuiData vtable. All three validate the result's own vtable pointer
// before publishing it. Cached once valid.
uintptr_t guiData();

// True when the client reports an in-game session. vtable.isInGame=Class:N
// only; no pin, no call, false.
bool isInGame();

// main.cpp publishes the real (pre-hook) GuiData::addMessage address once it
// exists, so delivery forwards through the hook bridge instead of jumping into
// a possibly-patched entry. And when the game's own addMessage call runs, its
// handler publishes the instance pointer — the strongest identity proof there is.
uintptr_t &addMessageRouteRef();

void setGuiDataFromHook(uintptr_t gd);

// Writes the chat line through GuiData::addMessage on the discovered instance,
// on the game thread: main.cpp posts this to ClientInstance::update's next tick
// rather than calling into the UI from a binder thread. The published route (a
// real pointer from the installed hook, or the rule-pinned address) is used;
// returns false when no game thread exists to pump it.
bool requestMessage(const std::string &message);

// Runs pending requests on the calling (game) thread; called from the update hook.
void pumpMessages();

// Same pump, offered to the addMessage hook: it runs on the game thread at
// exactly the moment GuiData becomes provable, so messages queued while the
// member scan had not yet resolved go out with the game's first chat line.
// No-op when the queue is empty.
void pumpFromAddMessageHook();

// One-line snapshot for logs and Java surfaces:
// "sdk ci=0x.. mg=0x..(src) gd=0x..(src) inGame=..".
void describe(char *buffer, size_t capacity);

// Identity of the loaded game. setVersion is called once from load() with the
// version name the binder side parsed out of the APK; version() returns ""
// before that. ModApi.buildInfo() reports it, and a mod can gate on it.
void setVersion(const char *versionName);
const char *version();

// True once a game-hooks.ini rule was selected and injected via setRule.
bool ruleActive();

// Read-only accessors for the typed facade (mcpe/ClassTree.h) — it must read
// exactly the same rule/base/identity the ladder uses, never a second copy.
const HookRules::Rule *rule();
uintptr_t guestBase();
// Source tag of the cached GuiData ("call", "field", "scan", "hook", "seen")
// or "-" when unresolved. Only meaningful together with guiData() != 0.
const char *guiDataSource();
// Count of game-thread pump ticks observed; diagnostics only.
size_t pumpCount();

}  // namespace GameSdk

#endif  // ALTER_LAUNCHER_MCPE_GAME_SDK_H