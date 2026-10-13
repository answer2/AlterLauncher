#ifndef ALTER_LAUNCHER_MCPE_SCREEN_MODEL_H
#define ALTER_LAUNCHER_MCPE_SCREEN_MODEL_H

// ClientInstanceScreenModel - command execution and screen interaction.
#include <string>
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class ClientInstanceScreenModel {
public:
    explicit ClientInstanceScreenModel(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // No static accessor exists. Checked against the symbolised reference
    // (Minecraft-EDU 1.21.131): the class has getGuiData, getGuiMessageList,
    // getClientUser, getBlockEntity, setClipboardToPosition,
    // sendCommandBlockUpdatePacket, toggleChatMute, tryBroadcastGlobalPause and
    // friends, but no get()/getInstance() - each screen builds its own via
    // SceneCreationUtils::_createModel<ClientInstanceScreenModel>(SceneFactory &,
    // IMinecraftGame &, IClientInstance &, ...), so a shared_ptr to it is not
    // reachable from a ClientInstance. get() therefore stays a signature lookup
    // that resolves to 0 and returns an empty Handle; do not "fix" it by pinning
    // an address in game-hooks.ini, there is no such function to point at.
    static Handle get();

    // executeCommand is not a member of this class either - see the same symbol
    // list. Running a command goes through CommandRequestPacket (id 0x4D), which
    // is what both Levi/BedrockTools and Latite do: createPacket(0x4D), fill
    // mCommand / mOrigin.mType = 0 / mInternalSource = true, then hand it to
    // ClientInstance::getPacketSender()'s LoopbackPacketSender. The pinned
    // sig.clientInstanceGetPacketSender is the reachable half of that route
    // today; MinecraftPackets::createPacket has no verified pattern on 1.26.52.3.
    // Vtable key: screenModelExecuteCommand.
    bool executeCommand(const std::string &command) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_SCREEN_MODEL_H
