#include <mcpe/network/Network.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

#include <mcpe/core/ClassCommon.h>
#include <mcpe/client/ClientInstance.h>
#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>
#include <memory/SigScanner.h>
#include <memory/RttiLookup.h>
#include <limits>

using mcpe::detail::dataPin;
using mcpe::detail::inExec;
using mcpe::detail::inGuestLib;
using mcpe::detail::safeReadPtr;
using mcpe::detail::safeReadU8;

namespace {

#if defined(__aarch64__)
// Bedrock's CommandRequestPacket, the packet the chat bar sends when a line
// starts with a slash. 0x4D is a protocol constant, not a build fact.
const uint16_t kCommandRequestPacket = 0x4D;

// The nontrivial shared_ptr result uses AAPCS64's indirect result register x8.
typedef std::shared_ptr<void> (*CreatePacketFn)(uint16_t);

// The Android entry borrows Packet& in x1; it never consumes a shared_ptr.
typedef void (*SendToServerFn)(void *self, void *packet);

bool gameObject(uintptr_t body) {
    uintptr_t vptr = 0;
    return body != 0 && safeReadPtr(body, 0, &vptr) && vptr != 0 && inGuestLib(vptr);
}

bool checkedAdd(uintptr_t base, uintptr_t offset, uintptr_t *result) {
    if (offset > std::numeric_limits<uintptr_t>::max() - base) return false;
    *result = base + offset;
    return true;
}

bool guestRange(const std::vector<RttiLookup::Region> &regions,
                uintptr_t address, size_t size, bool executable = false) {
    uintptr_t end = 0;
    if (!address || !size || !checkedAdd(address, size, &end)) return false;
    for (const auto &region : regions) {
        if (address >= region.start && end <= region.end &&
            (!executable || region.executable)) return true;
    }
    return false;
}

uintptr_t senderVirtual(uintptr_t self, const char *key) {
    const auto *rule = GameSdk::rule();
    std::string className;
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(key, &className, &slot) ||
        className != "LoopbackPacketSender") return 0;
    uintptr_t vptr = 0;
    if (!self || self % alignof(uintptr_t) || !safeReadPtr(self, 0, &vptr) ||
        !vptr || vptr % alignof(uintptr_t) ||
        slot > (std::numeric_limits<uintptr_t>::max() - vptr) / sizeof(uintptr_t)) return 0;
    const auto regions = RttiLookup::regionsOf("libminecraftpe.so");
    const uintptr_t entry = vptr + slot * sizeof(uintptr_t);
    // The object's vptr is already the address point; RO RELRO is readable here.
    if (!guestRange(regions, vptr, sizeof(uintptr_t)) ||
        !guestRange(regions, entry, sizeof(uintptr_t))) return 0;
    uintptr_t function = 0;
    memcpy(&function, reinterpret_cast<const void *>(entry), sizeof(function));
    return function % 4 == 0 && guestRange(regions, function, 4, true) &&
           inExec(function) ? function : 0;
}

bool clientContext(uintptr_t self) {
    uintptr_t systemOffset = 0, modeOffset = 0, system = 0, address = 0;
    uint32_t mode = 0;
    return dataPin("senderNetworkSystem", &systemOffset) &&
           dataPin("senderNetworkMode", &modeOffset) &&
           checkedAdd(self, systemOffset, &address) &&
           safeReadPtr(self, systemOffset, &system) && system != 0 &&
           checkedAdd(self, modeOffset, &address) &&
           mcpe::detail::safeReadU32(self, modeOffset, &mode) && mode == 0;
}

bool writableField(uintptr_t base, uintptr_t offset, size_t size, uintptr_t *result) {
    return checkedAdd(base, offset, result) &&
           size <= std::numeric_limits<uintptr_t>::max() - *result &&
           RttiLookup::inWritableRange(*result, size);
}
#endif

std::mutex g_commandMutex;
std::vector<std::string> g_pendingCommands;

}  // namespace

namespace mcpe {

std::shared_ptr<void> createPacket(uint16_t id) {
#if defined(__aarch64__)
    uintptr_t address = SigScanner::resolve(std::string("minecraftPacketsCreatePacket"));
    if (address == 0 || !inExec(address)) return {};
    auto packet = reinterpret_cast<CreatePacketFn>(address)(id);
    if (!gameObject(reinterpret_cast<uintptr_t>(packet.get()))) return {};
    return packet;
#else
    (void) id;
    return {};
#endif
}

std::optional<bool> LoopbackPacketSender::isInitialized() const {
#if defined(__aarch64__)
    const uintptr_t address = senderVirtual(self_, "packetSenderIsInitialized");
    if (address) return reinterpret_cast<bool (*)(void *)>(address)(reinterpret_cast<void *>(self_));
#endif
    return std::nullopt;
}

bool LoopbackPacketSender::send(const std::shared_ptr<void> &packet) const {
#if defined(__aarch64__)
    if (!gameObject(self_) || !gameObject(reinterpret_cast<uintptr_t>(packet.get())) ||
        !clientContext(self_) || isInitialized() != std::optional<bool>(true)) return false;
    const uintptr_t address = senderVirtual(self_, "packetSenderSend");
    if (!address) return false;
    reinterpret_cast<SendToServerFn>(address)(reinterpret_cast<void *>(self_), packet.get());
    return true;
#else
    (void) packet;
    return false;
#endif
}

bool LoopbackPacketSender::sendToServer(const std::shared_ptr<void> &packet) const {
#if defined(__aarch64__)
    if (!gameObject(self_) || !gameObject(reinterpret_cast<uintptr_t>(packet.get())) ||
        !clientContext(self_) || isInitialized() != std::optional<bool>(true)) return false;
    uintptr_t address = SigScanner::resolve(std::string("loopbackPacketSenderSendToServer"));
    if (address == 0) address = senderVirtual(self_, "packetSenderSendToServer");
    if (address == 0 || !inExec(address)) return false;
    reinterpret_cast<SendToServerFn>(address)(reinterpret_cast<void *>(self_), packet.get());
    return true;
#else
    (void) packet;
    return false;
#endif
}

bool LoopbackPacketSender::sendChatMessage(const std::string &text) const {
    return GameSdk::requestMessage(text);
}

bool LoopbackPacketSender::sendCommand(const std::string &command) const {
#if defined(__aarch64__)
    if (command.empty() || !gameObject(self_)) return false;

    // Packet layout is data, not code, and it is versioned like everything else.
    // Absent pin = refuse; a default here would be a wild write into a game
    // object of unknown size.
    uintptr_t packetSize = 0, cmdOffset = 0, originOffset = 0, internalOffset = 0;
    uintptr_t originTypeOffset = 0;
    if (!dataPin("packetSize", &packetSize) || !dataPin("cmdPacketCommand", &cmdOffset) ||
        !dataPin("cmdPacketOrigin", &originOffset) ||
        !dataPin("cmdPacketInternalSource", &internalOffset) ||
        !dataPin("cmdOriginType", &originTypeOffset)) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "Network: packet layout pins are missing, cannot build a command");
        return false;
    }

    std::shared_ptr<void> packet = createPacket(kCommandRequestPacket);
    if (!packet) return false;
    uintptr_t body = reinterpret_cast<uintptr_t>(packet.get());
    uintptr_t payload = 0, origin = 0, commandAddress = 0, typeAddress = 0, internalAddress = 0;
    // Validate every addition and the complete written ranges before touching any field.
    if (!checkedAdd(body, packetSize, &payload) ||
        !checkedAdd(payload, originOffset, &origin) ||
        !writableField(payload, cmdOffset, sizeof(std::string), &commandAddress) ||
        commandAddress % alignof(std::string) != 0 ||
        !writableField(origin, originTypeOffset, sizeof(uint8_t), &typeAddress) ||
        !writableField(payload, internalOffset, sizeof(bool), &internalAddress)) return false;

    // This path requires the pinned layout and a compatible libc++ string ABI.
    // Retain the empty short-string guard before assigning to the factory's member.
    void *commandSlot = reinterpret_cast<void *>(commandAddress);
    uint8_t firstByte = 1;
    if (!safeReadU8(reinterpret_cast<uintptr_t>(commandSlot), 0, &firstByte) || firstByte != 0) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "Network: %p is not an empty libc++ string, refusing to overwrite",
                            commandSlot);
        return false;
    }
    *reinterpret_cast<std::string *>(commandSlot) = command;

    // Preserve the pinned command path's origin and internal-source values.
    *reinterpret_cast<uint8_t *>(typeAddress) = 0;
    *reinterpret_cast<bool *>(internalAddress) = true;
    return sendToServer(packet);
#else
    (void) command;
    return false;
#endif
}

bool requestCommand(const std::string &command) {
    if (GameSdk::clientInstance() == 0) return false;  // no game thread will ever pump this
    if (command.empty()) return false;
    std::lock_guard<std::mutex> guard(g_commandMutex);
    g_pendingCommands.push_back(command);
    return true;
}

void pumpCommands() {
    // Swap under the lock, send outside it, for the same reason pumpMessages does:
    // the send can re-enter.
    std::vector<std::string> batch;
    {
        std::lock_guard<std::mutex> guard(g_commandMutex);
        if (g_pendingCommands.empty()) return;
        batch.swap(g_pendingCommands);
    }
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return;
    mcpe::Handle sender = mcpe::ClientInstance(ci).loopbackPacketSender();
    if (!sender.valid()) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "Network: %zu command(s) dropped, no LoopbackPacketSender yet",
                            batch.size());
        return;
    }
    LoopbackPacketSender pump(sender.ptr);
    for (size_t i = 0; i < batch.size(); i++) {
        if (!pump.sendCommand(batch[i])) {
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "Network: command not sent (unpinned build or refused)");
        }
    }
}

}  // namespace mcpe
