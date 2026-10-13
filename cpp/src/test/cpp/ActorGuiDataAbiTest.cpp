// Standalone Android regression; compile this file with the actual
// mcpe/actor/Actor.cpp and mcpe/client/GuiData.cpp, including src/main/cpp.
// NDK clang++: --target=aarch64-linux-android21 -std=c++17 -O2
// -static-libstdc++ -ffunction-sections -fdata-sections -Wl,--gc-sections.
// Unused production methods are discarded, not replaced by test implementations.
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <mcpe/actor/Actor.h>
#include <mcpe/client/GuiData.h>
#include <mcpe/core/GameSDK.h>
#include <memory/SigScanner.h>

#include <cassert>
#include <cstdio>
#include <initializer_list>
#include <optional>
#include <string>
#include <type_traits>
#include <vector>

#ifndef __aarch64__
#error This test must execute the actual aarch64 ABI paths.
#endif
static_assert(sizeof(std::string) == 24);
static_assert(sizeof(std::optional<std::string>) == 32);
using ChatMethod = bool (mcpe::GuiData::*)(const std::string &, const std::string &) const;
static_assert(std::is_same_v<decltype(&mcpe::GuiData::displayChatMessage), ChatMethod>,
              "displayChatMessage must have exactly two arguments, without duration");

namespace {
char actorObject, guiObject;
uintptr_t actorEntry = 0, addEntry = 0, displayEntry = 0, tipEntry = 0, route = 0;
bool writable = true;
int getterCalls = 0, chatCalls = 0, tipCalls = 0, routeReads = 0;
std::string name, expectedSender, expectedMessage;
std::vector<std::string> resolutions;

// A real non-trivial by-value return exercises the aarch64 x8 result storage.
__attribute__((noinline)) std::string nameGetter(const void *self) {
    assert(self == &actorObject);
    ++getterCalls;
    return name;
}

__attribute__((noinline)) void chat(void *self, const std::string &sender,
        const std::string &message, const std::optional<std::string> &secondary,
        const std::string &metadata1, const std::string &metadata2) {
    assert(self == &guiObject);
    assert(sender == expectedSender && message == expectedMessage);
    assert(!secondary.has_value());
    assert(metadata1.empty() && metadata2.empty());
    ++chatCalls;
}

__attribute__((noinline)) void tip(void *self, const std::string &message,
        const std::optional<std::string> &secondary, bool flag) {
    assert(self == &guiObject);
    assert(message == expectedMessage);
    assert(!secondary.has_value() && !flag);
    ++tipCalls;
}

void reset() {
    actorEntry = reinterpret_cast<uintptr_t>(&nameGetter);
    addEntry = reinterpret_cast<uintptr_t>(&chat);
    displayEntry = reinterpret_cast<uintptr_t>(&chat);
    tipEntry = reinterpret_cast<uintptr_t>(&tip);
    route = 0;
    writable = true;
    getterCalls = chatCalls = tipCalls = routeReads = 0;
    resolutions.clear();
}

void expectResolutions(std::initializer_list<const char *> keys) {
    assert(resolutions.size() == keys.size());
    size_t i = 0;
    for (const char *key : keys) assert(resolutions[i++] == key);
}

void pass(const char *label) { std::printf("PASS: %s\n", label); }
}  // namespace

namespace SigScanner {
uintptr_t resolve(const std::string &key) {
    resolutions.push_back(key);
    if (key == "actorGetNameTag") return actorEntry;
    if (key == "guiDataAddMessage") return addEntry;
    if (key == "guiDataDisplayChatMessage") return displayEntry;
    if (key == "guiDataShowTipMessage") return tipEntry;
    assert(false && "unexpected signature key");
    return 0;
}
}  // namespace SigScanner

namespace GameSdk {
uintptr_t &addMessageRouteRef() { ++routeReads; return route; }
}  // namespace GameSdk

namespace mcpe::detail {
bool inWritable(uintptr_t address) {
    return writable && address == reinterpret_cast<uintptr_t>(&actorObject);
}
bool inExec(uintptr_t address) {
    return address == reinterpret_cast<uintptr_t>(&nameGetter) ||
           address == reinterpret_cast<uintptr_t>(&chat) ||
           address == reinterpret_cast<uintptr_t>(&tip);
}
}  // namespace mcpe::detail

int main() {
    const mcpe::Actor actor(reinterpret_cast<uintptr_t>(&actorObject));
    const mcpe::GuiData gui(reinterpret_cast<uintptr_t>(&guiObject));

    for (const std::string &value : {std::string("Short name"), std::string(128, 'L')}) {
        reset();
        name = value;
        std::string result = actor.getNameTag();
        assert(result == value && getterCalls == 1);
        expectResolutions({"actorGetNameTag"});
        name.assign("changed after return");
        assert(result == value);  // Independent ownership, including heap-backed strings.
        pass(value.size() <= 22 ? "Actor short by-value string (10 bytes)" :
                                 "Actor long by-value string (128 bytes)");
    }
    reset();
    assert(mcpe::Actor().getNameTag().empty());
    writable = false;
    assert(actor.getNameTag().empty());
    assert(getterCalls == 0);
    expectResolutions({});
    pass("Actor null and non-writable receiver rejection");

    for (uintptr_t entry : {uintptr_t{0}, uintptr_t{1}}) {
        reset();
        actorEntry = entry;
        assert(actor.getNameTag().empty() && getterCalls == 0);
        expectResolutions({"actorGetNameTag"});
        pass(entry == 0 ? "Actor unresolved getter rejection" : "Actor non-executable getter rejection");
    }

    // Exercise both public chat methods through every supported resolution route.
    for (int source = 0; source < 3; ++source) {
        for (bool send : {true, false}) {
            reset();
            if (source == 0) route = reinterpret_cast<uintptr_t>(&chat);
            if (source == 2) addEntry = 0;
            expectedSender = send ? "" : "test sender";
            expectedMessage = std::string(80, 'M');
            assert(send ? gui.sendMessage(expectedMessage) :
                          gui.displayChatMessage(expectedSender, expectedMessage));
            assert(chatCalls == 1 && tipCalls == 0 && routeReads == 1);
            if (source == 0) expectResolutions({});
            if (source == 1) expectResolutions({"guiDataAddMessage"});
            if (source == 2) expectResolutions({"guiDataAddMessage", "guiDataDisplayChatMessage"});
            std::printf("PASS: GuiData %s via %s; sender/message, disengaged optional, empty metadata\n",
                        send ? "send" : "display", source == 0 ? "SDK route" :
                        source == 1 ? "add signature" : "display signature fallback");
        }
    }

    reset();
    assert(!mcpe::GuiData(0).sendMessage("null"));
    assert(!mcpe::GuiData(0).displayChatMessage("sender", "null"));
    assert(!mcpe::GuiData(0).showTipMessage("null"));
    assert(chatCalls == 0 && tipCalls == 0 && routeReads == 0);
    expectResolutions({});
    pass("GuiData null receivers reject without resolving or calling");

    for (int source = 0; source < 4; ++source) {
        reset();
        if (source == 0) { addEntry = 0; displayEntry = 0; }
        if (source == 1) route = 1;
        if (source == 2) addEntry = 1;
        if (source == 3) { addEntry = 0; displayEntry = 1; }
        assert(!gui.sendMessage("rejected"));
        assert(!gui.displayChatMessage("sender", "rejected"));
        assert(chatCalls == 0 && tipCalls == 0 && routeReads == 2);
        if (source == 1) expectResolutions({});
        else if (source == 2) expectResolutions({"guiDataAddMessage", "guiDataAddMessage"});
        else expectResolutions({"guiDataAddMessage", "guiDataDisplayChatMessage",
                                "guiDataAddMessage", "guiDataDisplayChatMessage"});
        const char *labels[] = {"GuiData unresolved chat rejection",
            "GuiData non-executable SDK route rejection",
            "GuiData non-executable add signature rejection",
            "GuiData non-executable display fallback rejection"};
        pass(labels[source]);
    }

    reset();
    route = reinterpret_cast<uintptr_t>(&chat); // An available chat route must be ignored.
    expectedMessage = "distinct tip entry";
    assert(gui.showTipMessage(expectedMessage));
    assert(tipCalls == 1 && chatCalls == 0 && routeReads == 0);
    expectResolutions({"guiDataShowTipMessage"});
    pass("GuiData tip uses distinct entry, message, disengaged optional, false flag");

    for (uintptr_t entry : {uintptr_t{0}, uintptr_t{1}}) {
        reset();
        route = reinterpret_cast<uintptr_t>(&chat);
        tipEntry = entry;
        assert(!gui.showTipMessage("must not become chat"));
        assert(tipCalls == 0 && chatCalls == 0 && routeReads == 0);
        expectResolutions({"guiDataShowTipMessage"});
        pass(entry == 0 ? "GuiData missing tip rejects without chat fallback" :
                          "GuiData non-executable tip rejects without chat fallback");
    }
    std::puts("PASS: all 19 Actor/GuiData aarch64 regression cases");
}
