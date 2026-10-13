#ifndef ALTER_LAUNCHER_MCPE_NETWORK_H
#define ALTER_LAUNCHER_MCPE_NETWORK_H

// Local chat display and outbound network packets.
#include <memory>
#include <optional>
#include <string>

#include <mcpe/core/ClassCommon.h>

namespace mcpe {

// Game thread only. Uses the game's factory; empty on unsupported ABI or failed validation.
std::shared_ptr<void> createPacket(uint16_t id);

class LoopbackPacketSender {
public:
    explicit LoopbackPacketSender(uintptr_t self) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Queues local chat display through GameSdk; does not send a network packet.
    bool sendChatMessage(const std::string &text) const;

    // Game thread only. Unknown on unsupported ABI or unresolved virtual.
    std::optional<bool> isInitialized() const;

    // Game thread only. Borrows packet.get() without transferring ownership.
    // Both sends require initialized client context (nonnull network system, mode 0).
    // True means the void game entry was called, not that the server received it.
    // Unsupported ABIs and failed validation return false.
    bool send(const std::shared_ptr<void> &packet) const;
    bool sendToServer(const std::shared_ptr<void> &packet) const;

    // Build a CommandRequestPacket (0x4D) with the game's own createPacket and
    // hand it to this sender. Game thread ONLY - it writes into a freshly
    // allocated game object. requestCommand() is the thread-safe front end.
    bool sendCommand(const std::string &command) const;

private:
    uintptr_t self_;
};

// Park a command for the update hook to send on the game thread. False when no
// game is running, so no request can sit in the queue forever.
bool requestCommand(const std::string &command);

// Called from the update hook, next to GameSdk::pumpMessages().
void pumpCommands();

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_NETWORK_H
