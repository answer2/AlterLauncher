#ifndef ALTER_LAUNCHER_MCPE_NETWORK_H
#define ALTER_LAUNCHER_MCPE_NETWORK_H

// Network-space: packet sender + the proven chat route.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class LoopbackPacketSender {
public:
    explicit LoopbackPacketSender(uintptr_t self) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // No packet construction here: building a real network packet needs the
    // game's serialize vtable, which is slot-resolved per build. This layer
    // only exposes the proven chat route - sendChatMessage forwards to the
    // SAME addMessage path GameSdk uses, so callers get one consistent API.
    bool sendChatMessage(const std::string &text) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_NETWORK_H
