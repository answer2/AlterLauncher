// Link the production Packet.cpp and HookRules.cpp. Run on AArch64 to exercise
// dispatch and x0/x1 name returns; other architectures must fail closed.
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <mcpe/network/Packet.h>
#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>
#include <cassert>
#include <cstdio>
#include <limits>

namespace {
const HookRules::Rule *activeRule = nullptr;
uintptr_t table[15] = {};
uintptr_t object = reinterpret_cast<uintptr_t>(table);
const char nameBytes[] = {'T', 'e', 'x', 't'}; // Deliberately not NUL-terminated.
struct NameView { uintptr_t data; size_t size; };
NameView nameResult{reinterpret_cast<uintptr_t>(nameBytes), sizeof(nameBytes)};
std::vector<RttiLookup::Region> mappings;
bool writable = true;
int calls = 0;
struct HandleObject { uintptr_t vptr; uintptr_t padding[3]; uintptr_t dispatcher; };
HandleObject handleObject{};
uintptr_t dispatcherTable[3] = {}, callbackTable[119] = {};
uintptr_t dispatcherObject = reinterpret_cast<uintptr_t>(dispatcherTable);
uintptr_t callbackObject = reinterpret_cast<uintptr_t>(callbackTable), networkObject = 0;
int packetId = 9, dispatchCalls = 0;
std::shared_ptr<void> *expectedOwner = nullptr;
size_t packetRange = sizeof(handleObject);
void check(const void *self) { assert(self == &object || self == &handleObject); ++calls; }
int id(const void *self) { check(self); return packetId; }
void callback() {}
void dispatch(void *dispatcher, const void *network, void *eventCallback,
              std::shared_ptr<void> &owner) {
    assert(dispatcher == &dispatcherObject && network == &networkObject);
    assert(eventCallback == &callbackObject && &owner == expectedOwner);
    assert(owner.get() == &handleObject && owner.use_count() == 1);
    ++dispatchCalls;
}
NameView name(const void *self) { check(self); return nameResult; }
size_t maximum(const void *self) { check(self); return size_t{1} << 33; }
bool batching(const void *self) { check(self); return false; }
bool validity(const void *self) { check(self); return true; }
void addRegion(uintptr_t address, size_t size, bool executable = false) {
    mappings.push_back({address, address + size, executable});
}
void resetMappings() {
    mappings.clear();
    addRegion(reinterpret_cast<uintptr_t>(table), sizeof(table));
    addRegion(reinterpret_cast<uintptr_t>(nameBytes), sizeof(nameBytes));
    for (const size_t slot : {2, 3, 4, 11, 12}) addRegion(table[slot], 4, true);
}
void unavailable(const mcpe::Packet &packet) {
    const int before = calls;
    assert(!packet.getId());
    assert(packet.getName().empty());
    assert(!packet.getMaxSize());
    assert(!packet.disallowBatching());
    assert(!packet.isValid());
    assert(calls == before);
}
}  // namespace

namespace GameSdk {
const HookRules::Rule *rule() { return activeRule; }
}
namespace RttiLookup {
std::vector<Region> regionsOf(const std::string &library) {
    assert(library == "libminecraftpe.so");
    return mappings;
}
bool inRegions(const std::vector<Region> &regions, uintptr_t address, bool executable) {
    for (const auto &region : regions)
        if (address >= region.start && address < region.end &&
            (!executable || region.executable)) return true;
    return false;
}
bool inWritableRange(uintptr_t address, size_t size) {
    // Vtables and name bytes intentionally are NOT writable.
    return writable &&
           ((address == reinterpret_cast<uintptr_t>(&object) && size == sizeof(object)) ||
            (address == reinterpret_cast<uintptr_t>(&handleObject) && size <= packetRange) ||
            (address == reinterpret_cast<uintptr_t>(&callbackObject) && size == sizeof(uintptr_t)) ||
            (address == reinterpret_cast<uintptr_t>(&networkObject) && size == sizeof(uintptr_t)));
}
}

int main() {
    table[2] = reinterpret_cast<uintptr_t>(&id);
    table[3] = reinterpret_cast<uintptr_t>(&name);
    table[4] = reinterpret_cast<uintptr_t>(&maximum);
    table[11] = reinterpret_cast<uintptr_t>(&batching);
    table[12] = reinterpret_cast<uintptr_t>(&validity);
    resetMappings();
    HookRules::Rule rule;
    rule.vtable = {{"packetGetId", "Packet:2"}, {"packetGetName", "Packet:3"},
                  {"packetGetMaxSize", "Packet:4"}, {"packetDisallowBatching", "Packet:11"},
                  {"packetIsValid", "Packet:12"}};
    const mcpe::Packet packet(reinterpret_cast<uintptr_t>(&object));
    assert(packet.valid() && packet.self() == reinterpret_cast<uintptr_t>(&object));
    assert(!mcpe::Packet().valid());
    unavailable(packet); // Missing active rule.
    activeRule = &rule;
    unavailable(mcpe::Packet());
#if defined(__aarch64__)
    assert(packet.getId() == 9);
    assert(packet.getName() == "Text");
    assert(packet.getMaxSize() == size_t{1} << 33);
    assert(packet.disallowBatching().has_value() && !*packet.disallowBatching());
    assert(packet.isValid() == true);
    const auto pins = rule.vtable;
    rule.vtable.clear();
    unavailable(packet);
    rule.vtable = pins;
    for (auto &entry : rule.vtable) entry.second = "Other:2";
    unavailable(packet);
    rule.vtable = pins;
    for (auto &entry : rule.vtable) entry.second = "Packet:18446744073709551615";
    unavailable(packet);
    rule.vtable = pins;
    writable = false;
    unavailable(packet);
    writable = true;
    unavailable(mcpe::Packet(std::numeric_limits<uintptr_t>::max() - 7));
    const uintptr_t saved = object;
    object = std::numeric_limits<uintptr_t>::max() - 7;
    unavailable(packet); // Address-point arithmetic overflow.
    object = saved;
    mappings.clear();
    unavailable(packet);
    resetMappings();
    mappings[0].end = object + 2 * sizeof(uintptr_t) + sizeof(uintptr_t) - 1;
    assert(!packet.getId()); // Last byte of slot is outside the mapping.
    resetMappings();
    mappings[2].executable = false;
    assert(!packet.getId());
    resetMappings();
    const uintptr_t savedId = table[2];
    table[2] = 0;
    assert(!packet.getId());
    table[2] = savedId + 1;
    assert(!packet.getId());
    table[2] = savedId;
    nameResult.size = sizeof(nameBytes) + 1;
    assert(packet.getName().empty());
    nameResult = {std::numeric_limits<uintptr_t>::max() - 1, 4};
    assert(packet.getName().empty());
    nameResult = {reinterpret_cast<uintptr_t>(nameBytes), sizeof(nameBytes)};
    resetMappings();
    mappings[1].end = nameResult.data + 1;
    addRegion(nameResult.data + 3, 1);
    assert(packet.getName().empty()); // Endpoints mapped, middle is a hole.
    resetMappings();
    assert(packet.getName() == "Text");
#else
    unavailable(packet);
#endif
    resetMappings();
    handleObject.vptr = reinterpret_cast<uintptr_t>(table);
    handleObject.dispatcher = reinterpret_cast<uintptr_t>(&dispatcherObject);
    dispatcherTable[2] = reinterpret_cast<uintptr_t>(&dispatch);
    callbackTable[36] = callbackTable[118] = reinterpret_cast<uintptr_t>(&callback);
    addRegion(reinterpret_cast<uintptr_t>(&dispatcherObject), sizeof(dispatcherObject)); // RO only.
    addRegion(reinterpret_cast<uintptr_t>(dispatcherTable), sizeof(dispatcherTable));
    addRegion(reinterpret_cast<uintptr_t>(callbackTable), sizeof(callbackTable));
    addRegion(reinterpret_cast<uintptr_t>(&dispatch), 4, true);
    addRegion(reinterpret_cast<uintptr_t>(&callback), 4, true);
    rule.dataOffsets["packetHandler"] = 0x20;
    rule.dataOffsets["packetHandlerDispatchSlot"] = 2;
    rule.vtable["netEventCallbackText"] = "NetEventCallback:36";
    rule.vtable["netEventCallbackCommandRequest"] = "NetEventCallback:118";
    std::shared_ptr<void> owner(&handleObject, [](void *) {});
    expectedOwner = &owner;
    const mcpe::Packet handled(reinterpret_cast<uintptr_t>(&handleObject));
    const auto invoke = [&](std::shared_ptr<void> &value) {
        return handled.handle(reinterpret_cast<uintptr_t>(&networkObject),
                              reinterpret_cast<uintptr_t>(&callbackObject), value);
    };
#if defined(__aarch64__)
    assert(invoke(owner) && dispatchCalls == 1);
    packetId = 0x4d;
    assert(invoke(owner) && dispatchCalls == 2);
    packetId = 123;
    assert(!invoke(owner));
    packetId = 9;
    std::shared_ptr<void> empty;
    assert(!invoke(empty));
    std::shared_ptr<void> unowned(empty, &handleObject);
    assert(!invoke(unowned));
    assert(!packet.handle(reinterpret_cast<uintptr_t>(&networkObject),
                          reinterpret_cast<uintptr_t>(&callbackObject), owner));
    assert(!handled.handle(0, reinterpret_cast<uintptr_t>(&callbackObject), owner));
    assert(!handled.handle(reinterpret_cast<uintptr_t>(&networkObject), 0, owner));
    activeRule = nullptr;
    assert(!invoke(owner));
    activeRule = &rule;
    rule.dataOffsets.clear();
    assert(!invoke(owner));
    rule.dataOffsets["packetHandler"] = 0x20;
    assert(!invoke(owner));
    rule.dataOffsets["packetHandlerDispatchSlot"] = std::numeric_limits<uintptr_t>::max();
    assert(!invoke(owner));
    rule.dataOffsets["packetHandlerDispatchSlot"] = 2;
    for (const char *key : {"netEventCallbackText"}) {
        const auto saved = rule.vtable[key];
        rule.vtable.erase(key);
        assert(!invoke(owner));
        rule.vtable[key] = "Wrong:2";
        assert(!invoke(owner));
        rule.vtable[key] = saved;
    }
    packetRange = sizeof(handleObject) - 1;
    assert(!invoke(owner));
    packetRange = sizeof(handleObject);
    const auto savedMappings = mappings;
    for (const uintptr_t target : {reinterpret_cast<uintptr_t>(dispatcherTable),
                                   reinterpret_cast<uintptr_t>(callbackTable)}) {
        for (auto &region : mappings)
            if (region.start == target) --region.end;
        if (target == reinterpret_cast<uintptr_t>(callbackTable)) packetId = 0x4d;
        assert(!invoke(owner));
        mappings = savedMappings;
    }
    packetId = 9;
    for (const uintptr_t target : {reinterpret_cast<uintptr_t>(&dispatch),
                                   reinterpret_cast<uintptr_t>(&callback)}) {
        for (auto &region : mappings)
            if (region.start == target) region.executable = false;
        assert(!invoke(owner));
        mappings = savedMappings;
    }
    const auto savedCallback = callbackObject;
    callbackObject = 0;
    assert(!invoke(owner));
    callbackObject = savedCallback;
    handleObject.dispatcher = 0;
    assert(!invoke(owner));
    handleObject.dispatcher = reinterpret_cast<uintptr_t>(&dispatcherObject);
    assert(dispatchCalls == 2);
    assert(invoke(owner) && dispatchCalls == 3);
#else
    assert(!invoke(owner) && dispatchCalls == 0);
#endif
    std::puts("PASS: Packet accessors and handle ABI, ownership, RO dispatcher, rules and context rejection");
}
