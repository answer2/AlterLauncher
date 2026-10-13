#ifndef ALTER_LAUNCHER_MCPE_PACKET_H
#define ALTER_LAUNCHER_MCPE_PACKET_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>

namespace mcpe {

// Borrowed packet: the caller must keep it alive and use the game thread.
// valid() only reports a non-null handle; isValid() calls the pinned virtual.
class Packet {
public:
    explicit Packet(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    std::optional<int> getId() const;
    // Copies the guest's pointer/length result; empty on unavailable/invalid data.
    std::string getName() const;
    std::optional<size_t> getMaxSize() const;
    std::optional<bool> disallowBatching() const;
    std::optional<bool> isValid() const;

    // Game thread only; requires a live connection, its NetEventCallback base-subobject pointer,
    // and the factory-owned packet matching self(). Supports Text/CommandRequest only.
    // True means dispatch was invoked, not socket receipt; address checks cannot prove lifetime.
    bool handle(uintptr_t networkIdentifier, uintptr_t netEventCallback,
                std::shared_ptr<void> &packet) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_PACKET_H
