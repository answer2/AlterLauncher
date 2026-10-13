#include <cassert>
#include <cstdio>
#include <type_traits>
#include <mcpe/core/GameSDK.cpp>
#include <hook/GameHooks.cpp>

using ChatFn = void (*)(void *, const std::string &, const std::string &,
                       const std::optional<std::string> &, const std::string &, const std::string &);
static_assert(std::is_same_v<decltype(&GameHooks::guiDataAddMessageHook), ChatFn>);
static_assert(std::is_same_v<decltype(GameHooks::guiDataAddMessageReal), ChatFn>);
static uintptr_t signatureRoute;
static int warmupRequests;
namespace mcpe {
void selfTestWarmup() { ++warmupRequests; }
void selfTest() {}
bool selfTestWarm() { return true; }
void pumpCommands() {}
}
namespace SigScanner {
uintptr_t resolve(const std::string &key) { return key == "guiDataAddMessage" ? signatureRoute : 0; }
}
namespace RttiLookup {
std::vector<Region> regionsOf(const std::string &) { return {}; }
bool inRegions(const std::vector<Region> &, uintptr_t, bool) { return false; }
bool inWritableRange(uintptr_t, size_t) { return false; }
uintptr_t slotAt(uintptr_t, size_t) { return 0; }
uintptr_t findMemberByVtable(uintptr_t, uintptr_t, size_t) { return 0; }
}
namespace Reflection {
std::vector<uintptr_t> findAllMembers(uintptr_t, const std::vector<uintptr_t> &, size_t) { return {}; }
}
static uintptr_t object;
static int calls;
static bool forwarding;
static const std::optional<std::string> *expectedOptional;
static void chat(void *self, const std::string &sender, const std::string &message,
                 const std::optional<std::string> &metadata,
                 const std::string &extra1, const std::string &extra2) {
    assert(self == &object);
    assert(message == "message");
    if (forwarding) {
        assert(sender == "sender" && extra1 == "one" && extra2 == "two");
        assert(&metadata == expectedOptional);
        assert(metadata == *expectedOptional);
    } else {
        assert(sender.empty() && extra1.empty() && extra2.empty());
        assert(!metadata.has_value());
        assert(reinterpret_cast<const unsigned char *>(&metadata)[24] == 0);
    }
    ++calls;
}
int main() {
    const uintptr_t route = reinterpret_cast<uintptr_t>(&chat);
    HookRules::Rule rule;
    rule.offsets["guiDataAddMessage"] = 16;
    for (int mode = 0; mode < 3; ++mode) {
        GameSdk::state = {};
        GameSdk::state.guiData = reinterpret_cast<uintptr_t>(&object);
        signatureRoute = 0;
        if (mode == 0) GameSdk::addMessageRouteRef() = route;
        if (mode == 1) {
            GameSdk::init(route - 16);
            GameSdk::setRule(&rule, true);
        }
        if (mode == 2) signatureRoute = route;
        assert(GameSdk::deliverMessage("message"));
        assert(calls == mode + 1);
    }
    GameSdk::state = {};
    forwarding = true;
    GameHooks::guiDataAddMessageReal = &chat;
    for (const auto &metadata : {std::optional<std::string>{}, std::optional<std::string>{"metadata"}}) {
        expectedOptional = &metadata;
        GameHooks::guiDataAddMessageHook(&object, "sender", "message", metadata, "one", "two");
    }
    assert(calls == 5);
    GameHooks::guiDataAddMessageReal = nullptr;
    GameHooks::guiDataAddMessageHook(&object, "sender", "message", std::nullopt, "one", "two");
    assert(calls == 5);
    assert(warmupRequests == 0);
    GameHooks::guiDataAddMessageHook(&object, "sender", "666", std::nullopt, "", "");
    assert(warmupRequests == 1 && GameHooks::g_selfTestRequested.load());
    GameHooks::guiDataAddMessageHook(&object, "sender", "666", std::nullopt, "", "");
    assert(warmupRequests == 1);
    GameHooks::g_selfTestRequested.store(false);
    GameHooks::guiDataAddMessageHook(&object, "sender", "666", std::nullopt, "", "");
    assert(warmupRequests == 2);
    std::puts("PASS: chat ABI, exact 666 trigger, pending coalescing and retrigger");
}
