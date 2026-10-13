#include <cassert>
#include <cstdio>
#include <map>
#include <mcpe/network/Network.cpp>

// Exercise the production implementation with bounded fake guest objects/routes.
namespace {
constexpr uintptr_t guestVptr = 0x123400;
struct Packet {
    uintptr_t vptr = guestVptr;
    std::string command;
    uint8_t origin = 7;
    bool internal = false;
};
struct Sender {
    uintptr_t vptr;
    uintptr_t networkSystem = 1;
    uint32_t mode = 0;
    bool initialized = true;
};
Sender receiver{};
uintptr_t senderTable[6]{};
HookRules::Rule activeRule;
bool noRule, noRegions;
size_t tableBytes;
std::shared_ptr<Packet> owned;
std::map<std::string, uintptr_t> pins;
uintptr_t createRoute, sendRoute, deniedExec, deniedRange;
int creates, sends, deletes;
uint16_t expectedId;
bool nullFactory, badFactory;
long expectedOwners;
std::shared_ptr<void> factory(uint16_t id) {
    assert(id == expectedId);
    ++creates;
    if (nullFactory) return {};
    owned = std::shared_ptr<Packet>(new Packet, [](Packet *p) { ++deletes; delete p; });
    if (badFactory) owned->vptr = 0;
    return owned;
}
void send(void *self, void *packet) {
    assert(self == &receiver);
    assert(packet == owned.get());
    assert(owned.use_count() == expectedOwners);
    ++sends;
}
bool initialized(void *self) {
    assert(self == &receiver);
    return receiver.initialized;
}
void genericSend(void *self, void *packet) { send(self, packet); }
void reset() {
    owned.reset();
    receiver = {reinterpret_cast<uintptr_t>(senderTable), 1, 0, true};
    senderTable[2] = reinterpret_cast<uintptr_t>(&initialized);
    senderTable[3] = reinterpret_cast<uintptr_t>(&genericSend);
    senderTable[5] = reinterpret_cast<uintptr_t>(&send);
    tableBytes = sizeof(senderTable);
    noRule = noRegions = false;
    activeRule.vtable = {{"packetSenderIsInitialized", "LoopbackPacketSender:2"},
                         {"packetSenderSend", "LoopbackPacketSender:3"},
                         {"packetSenderSendToServer", "LoopbackPacketSender:5"}};
    createRoute = reinterpret_cast<uintptr_t>(&factory);
    sendRoute = reinterpret_cast<uintptr_t>(&send);
    deniedExec = deniedRange = 0;
    nullFactory = badFactory = false;
    pins = {{"packetSize", 0}, {"cmdPacketCommand", offsetof(Packet, command)},
            {"cmdPacketOrigin", offsetof(Packet, origin)}, {"cmdOriginType", 0},
            {"cmdPacketInternalSource", offsetof(Packet, internal)},
            {"senderNetworkSystem", offsetof(Sender, networkSystem)},
            {"senderNetworkMode", offsetof(Sender, mode)}};
}
}
namespace RttiLookup {
std::vector<Region> regionsOf(const std::string &name) {
    assert(name == "libminecraftpe.so");
    if (noRegions) return {};
    const uintptr_t table = reinterpret_cast<uintptr_t>(senderTable);
    std::vector<Region> result{{table, table + tableBytes, false}};
    for (uintptr_t fn : {reinterpret_cast<uintptr_t>(&initialized),
                         reinterpret_cast<uintptr_t>(&genericSend),
                         reinterpret_cast<uintptr_t>(&send)})
        result.push_back({fn, fn + 4, true});
    return result;
}
bool inWritableRange(uintptr_t address, size_t size) {
    if (address == deniedRange || !size) return false;
    uintptr_t base = reinterpret_cast<uintptr_t>(owned.get());
    return owned && address >= base && address - base <= sizeof(Packet) &&
           size <= sizeof(Packet) - (address - base);
}
}
namespace mcpe { namespace detail {
bool inGuestLib(uintptr_t address) {
    return address == guestVptr || address == reinterpret_cast<uintptr_t>(senderTable);
}
bool inExec(uintptr_t address) {
    return address != deniedExec &&
           (address == reinterpret_cast<uintptr_t>(&factory) ||
            address == reinterpret_cast<uintptr_t>(&send) ||
            address == reinterpret_cast<uintptr_t>(&genericSend) ||
            address == reinterpret_cast<uintptr_t>(&initialized));
}
bool safeReadPtr(uintptr_t body, uintptr_t offset, uintptr_t *out) {
    if (body == reinterpret_cast<uintptr_t>(&receiver)) {
        if (offset != 0 && offset != offsetof(Sender, networkSystem)) return false;
    } else if (offset || !owned || body != reinterpret_cast<uintptr_t>(owned.get())) return false;
    memcpy(out, reinterpret_cast<const void *>(body + offset), sizeof(*out));
    return true;
}
bool safeReadU32(uintptr_t body, uintptr_t offset, uint32_t *out) {
    if (body != reinterpret_cast<uintptr_t>(&receiver) || offset != offsetof(Sender, mode)) return false;
    *out = receiver.mode;
    return true;
}
bool safeReadU8(uintptr_t body, uintptr_t offset, uint8_t *out) {
    if (offset || !RttiLookup::inWritableRange(body, 1)) return false;
    *out = *reinterpret_cast<uint8_t *>(body);
    return true;
}
bool dataPin(const char *key, uintptr_t *out) {
    auto it = pins.find(key);
    if (it == pins.end()) return false;
    *out = it->second;
    return true;
}
}
Handle ClientInstance::loopbackPacketSender() const { return {}; }
}
namespace SigScanner {
uintptr_t resolve(const std::string &key) {
    return key == "minecraftPacketsCreatePacket" ? createRoute : sendRoute;
}
}
namespace GameSdk {
const HookRules::Rule *rule() { return noRule ? nullptr : &activeRule; }
uintptr_t clientInstance() { return 0; }
bool requestMessage(const std::string &) { return false; }
}
int main() {
    reset();
    mcpe::LoopbackPacketSender sender(reinterpret_cast<uintptr_t>(&receiver));
#if defined(__aarch64__)
    expectedId = 0xabcd;
    auto packet = mcpe::createPacket(expectedId);
    assert(packet && packet.get() == owned.get());
    expectedOwners = packet.use_count();
    assert(sender.sendToServer(packet) && sends == 1);
    assert(packet.use_count() == expectedOwners && deletes == 0);
    assert(!sender.sendToServer({}));
    assert(!mcpe::LoopbackPacketSender(0).sendToServer(packet));
    receiver.vptr = 0;
    assert(!sender.sendToServer(packet));
    receiver.vptr = reinterpret_cast<uintptr_t>(senderTable);
    owned->vptr = 0;
    assert(!sender.sendToServer(packet));
    owned->vptr = guestVptr;
    assert(sender.isInitialized() == std::optional<bool>(true));
    assert(sender.send(packet) && sends == 2);
    sendRoute = 0;
    assert(sender.sendToServer(packet) && sends == 3);
    assert(packet.use_count() == expectedOwners && deletes == 0);
    const int acceptedSends = sends;
    receiver.initialized = false;
    assert(sender.isInitialized() == std::optional<bool>(false));
    assert(!sender.send(packet) && !sender.sendToServer(packet));
    receiver.initialized = true;
    receiver.networkSystem = 0;
    assert(!sender.send(packet) && !sender.sendToServer(packet));
    receiver.networkSystem = 1;
    for (uint32_t mode : {1u, 2u, UINT32_MAX}) {
        receiver.mode = mode;
        assert(!sender.send(packet) && !sender.sendToServer(packet));
    }
    receiver.mode = 0;
    for (const char *key : {"senderNetworkSystem", "senderNetworkMode"}) {
        const uintptr_t saved = pins[key];
        pins.erase(key);
        assert(!sender.send(packet) && !sender.sendToServer(packet));
        pins[key] = std::numeric_limits<uintptr_t>::max();
        assert(!sender.send(packet) && !sender.sendToServer(packet));
        pins[key] = saved;
    }
    noRule = true;
    assert(!sender.isInitialized() && !sender.send(packet) && !sender.sendToServer(packet));
    noRule = false;
    for (const char *key : {"packetSenderIsInitialized", "packetSenderSend", "packetSenderSendToServer"}) {
        const auto saved = activeRule.vtable[key];
        for (const std::string &bad : {std::string("Packet:2"), std::string("LoopbackPacketSender:999999999999999999"),
                                       std::string("malformed")}) {
            activeRule.vtable[key] = bad;
            if (std::string(key) != "packetSenderSendToServer") assert(!sender.send(packet));
            if (std::string(key) != "packetSenderSend") assert(!sender.sendToServer(packet));
        }
        activeRule.vtable.erase(key);
        if (std::string(key) != "packetSenderSendToServer") assert(!sender.send(packet));
        if (std::string(key) != "packetSenderSend") assert(!sender.sendToServer(packet));
        activeRule.vtable[key] = saved;
    }
    noRegions = true;
    assert(!sender.isInitialized() && !sender.send(packet) && !sender.sendToServer(packet));
    noRegions = false;
    tableBytes = 6 * sizeof(uintptr_t) - 1;
    assert(!sender.sendToServer(packet));
    tableBytes = 3 * sizeof(uintptr_t) - 1;
    assert(!sender.isInitialized());
    tableBytes = sizeof(senderTable);
    senderTable[3] = guestVptr;
    assert(!sender.send(packet));
    senderTable[3] = reinterpret_cast<uintptr_t>(&genericSend);
    deniedExec = reinterpret_cast<uintptr_t>(&initialized);
    assert(!sender.isInitialized() && !sender.send(packet) && !sender.sendToServer(packet));
    sendRoute = reinterpret_cast<uintptr_t>(&send);
    deniedExec = sendRoute;
    assert(!sender.sendToServer(packet) && sends == acceptedSends);
    packet.reset();
    owned.reset();
    assert(deletes == 1);
    reset();
    createRoute = 0;
    assert(!mcpe::createPacket(expectedId));
    createRoute = reinterpret_cast<uintptr_t>(&factory);
    deniedExec = createRoute;
    assert(!mcpe::createPacket(expectedId) && creates == 1);
    deniedExec = 0;
    nullFactory = true;
    assert(!mcpe::createPacket(expectedId));
    nullFactory = false;
    badFactory = true;
    assert(!mcpe::createPacket(expectedId));
    reset();
    expectedId = 0x4d;
    expectedOwners = 2;
    assert(sender.sendCommand("/help"));
    assert(owned->command == "/help" && owned->origin == 0 && owned->internal);
    const int previousSends = sends;
    // Each bad pin must refuse before even the valid command string is changed.
    for (const char *key : {"packetSize", "cmdPacketCommand", "cmdPacketOrigin",
                            "cmdOriginType", "cmdPacketInternalSource"}) {
        reset();
        pins[key] = std::numeric_limits<uintptr_t>::max();
        assert(!sender.sendCommand("/help"));
        assert(owned->command.empty() && owned->origin == 7 && !owned->internal);
    }
    reset();
    pins["cmdPacketCommand"] = sizeof(Packet) - alignof(std::string);
    assert(!sender.sendCommand("/help"));
    assert(owned->command.empty() && owned->origin == 7 && !owned->internal);
    reset();
    pins["cmdPacketInternalSource"] = sizeof(Packet);
    assert(!sender.sendCommand("/help"));
    assert(owned->command.empty() && owned->origin == 7 && !owned->internal);
    reset();
    pins.erase("cmdOriginType");
    assert(!sender.sendCommand("/help") && !owned);
    assert(!sender.sendCommand(""));
    assert(sends == previousSends);
#else
    assert(!mcpe::createPacket(0x4d));
    assert(!sender.isInitialized());
    assert(!sender.send({}));
    assert(!sender.sendToServer({}));
    assert(!sender.sendCommand("/help"));
    assert(creates == 0 && sends == 0);
#endif
    std::puts("PASS: production network factory/send ownership, arguments, and failure boundaries");
}
