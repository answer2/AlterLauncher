#include <mcpe/network/Packet.h>

#include <cstring>
#include <limits>
#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>

namespace {

#if defined(__aarch64__)
// Check the entire range in one readable guest mapping, including RO RELRO.
// Checking endpoints alone could accept a hole between two mappings.
bool guestRange(const std::vector<RttiLookup::Region> &regions,
                uintptr_t address, size_t size, bool executable = false) {
    if (!address || !size || size > std::numeric_limits<uintptr_t>::max() - address)
        return false;
    if (!RttiLookup::inRegions(regions, address, executable) ||
        !RttiLookup::inRegions(regions, address + size - 1, executable)) return false;
    for (const auto &region : regions) {
        if (address >= region.start && address + size <= region.end &&
            (!executable || region.executable)) return true;
    }
    return false;
}
#endif

uintptr_t resolve(uintptr_t self, const char *key) {
#if defined(__aarch64__)
    const auto *rule = GameSdk::rule();
    std::string className;
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(key, &className, &slot) || className != "Packet")
        return 0;
    constexpr uintptr_t maximum = std::numeric_limits<uintptr_t>::max();
    if (!self || self % alignof(uintptr_t) || sizeof(uintptr_t) > maximum - self ||
        !RttiLookup::inWritableRange(self, sizeof(uintptr_t))) return 0;
    uintptr_t addressPoint = 0;
    std::memcpy(&addressPoint, reinterpret_cast<const void *>(self), sizeof(addressPoint));
    if (!addressPoint || addressPoint % alignof(uintptr_t) ||
        slot > (maximum - addressPoint) / sizeof(uintptr_t)) return 0;
    const auto regions = RttiLookup::regionsOf("libminecraftpe.so");
    // An object's vptr already points at slot zero: do not add the RTTI header.
    const uintptr_t entry = addressPoint + slot * sizeof(uintptr_t);
    if (!guestRange(regions, addressPoint, sizeof(uintptr_t)) ||
        !guestRange(regions, entry, sizeof(uintptr_t))) return 0;
    uintptr_t function = 0;
    std::memcpy(&function, reinterpret_cast<const void *>(entry), sizeof(function));
    return function % 4 == 0 && guestRange(regions, function, 4, true) ? function : 0;
#else
    (void)self;
    (void)key;
    return 0;
#endif
}

template <typename Result>
std::optional<Result> call(uintptr_t self, const char *key) {
    const uintptr_t function = resolve(self, key);
    if (!function) return std::nullopt;
    return reinterpret_cast<Result (*)(const void *)>(function)(
            reinterpret_cast<const void *>(self));
}

}  // namespace

namespace mcpe {

std::optional<int> Packet::getId() const {
    return call<int>(self_, "packetGetId");
}

std::string Packet::getName() const {
#if defined(__aarch64__)
    const uintptr_t function = resolve(self_, "packetGetName");
    if (!function) return {};
    // CommandRequestPacket and TextPacket in 1.26.45.1 / 1.26.52.3 return
    // {pointer, length} in x0/x1, NOT a nontrivial std::string via x8.
    struct NameView { uintptr_t data; size_t size; };
    static_assert(sizeof(NameView) == 16);
    const auto name = reinterpret_cast<NameView (*)(const void *)>(function)(
            reinterpret_cast<const void *>(self_));
    if (!name.size) return {};
    const auto regions = RttiLookup::regionsOf("libminecraftpe.so");
    if (!guestRange(regions, name.data, name.size)) return {};
    return std::string(reinterpret_cast<const char *>(name.data), name.size);
#else
    return {};
#endif
}

std::optional<size_t> Packet::getMaxSize() const {
    return call<size_t>(self_, "packetGetMaxSize");
}

std::optional<bool> Packet::disallowBatching() const {
    return call<bool>(self_, "packetDisallowBatching");
}

std::optional<bool> Packet::isValid() const {
    return call<bool>(self_, "packetIsValid");
}

bool Packet::handle(uintptr_t networkIdentifier, uintptr_t netEventCallback,
                    std::shared_ptr<void> &packet) const {
#if defined(__aarch64__)
    if (!packet || packet.use_count() == 0 ||
        self_ != reinterpret_cast<uintptr_t>(packet.get())) return false;
    const auto *rule = GameSdk::rule();
    constexpr uintptr_t maximum = std::numeric_limits<uintptr_t>::max();
    const auto writablePointer = [](uintptr_t address) {
        return address && address % alignof(uintptr_t) == 0 &&
               sizeof(uintptr_t) <= maximum - address &&
               RttiLookup::inWritableRange(address, sizeof(uintptr_t));
    };
    uintptr_t offset = 0;
    if (!rule || !rule->lookupDataOffset("packetHandler", &offset) ||
        offset > maximum - sizeof(uintptr_t) ||
        offset + sizeof(uintptr_t) > maximum - self_ ||
        !writablePointer(self_) || offset % alignof(uintptr_t) ||
        !RttiLookup::inWritableRange(self_, offset + sizeof(uintptr_t)) ||
        !writablePointer(networkIdentifier) || !writablePointer(netEventCallback)) return false;
    const auto regions = RttiLookup::regionsOf("libminecraftpe.so");
    const auto callable = [&](uintptr_t object, const char *key, const char *expectedClass) {
        std::string className;
        size_t slot = 0;
        if (expectedClass) {
            if (!rule->lookupVtable(key, &className, &slot) || className != expectedClass)
                return uintptr_t{0};
        } else {
            uintptr_t pinnedSlot = 0;
            if (!rule->lookupDataOffset(key, &pinnedSlot)) return uintptr_t{0};
            slot = pinnedSlot;
        }
        if (!object || object % alignof(uintptr_t) ||
            (!guestRange(regions, object, sizeof(uintptr_t)) && !writablePointer(object)))
            return uintptr_t{0};
        uintptr_t vptr = 0;
        std::memcpy(&vptr, reinterpret_cast<const void *>(object), sizeof(vptr));
        if (vptr % alignof(uintptr_t) || !guestRange(regions, vptr, sizeof(uintptr_t)) ||
            slot > (maximum - vptr) / sizeof(uintptr_t)) return uintptr_t{0};
        const uintptr_t entry = vptr + slot * sizeof(uintptr_t);
        if (!guestRange(regions, entry, sizeof(uintptr_t))) return uintptr_t{0};
        uintptr_t function = 0;
        std::memcpy(&function, reinterpret_cast<const void *>(entry), sizeof(function));
        return function % 4 == 0 && guestRange(regions, function, 4, true)
               ? function : uintptr_t{0};
    };
    uintptr_t dispatcher = 0;
    std::memcpy(&dispatcher, reinterpret_cast<const void *>(self_ + offset), sizeof(dispatcher));
    const uintptr_t function = callable(dispatcher, "packetHandlerDispatchSlot", nullptr);
    if (!function) return false;
    const auto id = getId();
    const char *callbackKey = id == 9 ? "netEventCallbackText" :
                              id == 0x4d ? "netEventCallbackCommandRequest" : nullptr;
    if (!callbackKey || !callable(netEventCallback, callbackKey, "NetEventCallback")) return false;
    // Android wrappers c297ef0/c640834 load +0x20 and tail-call dispatcher slot 2.
    // Text c2bb3d8/c664be8 and Command c2bd544/c666d54 dereference x3's
    // shared_ptr and tail-call callback slots 36/118 with the packet's raw pointer.
    using Dispatch = void (*)(void *, const void *, void *, std::shared_ptr<void> &);
    reinterpret_cast<Dispatch>(function)(reinterpret_cast<void *>(dispatcher),
            reinterpret_cast<const void *>(networkIdentifier),
            reinterpret_cast<void *>(netEventCallback), packet);
    return true;
#else
    (void)networkIdentifier;
    (void)netEventCallback;
    (void)packet;
    return false;
#endif
}

}  // namespace mcpe
