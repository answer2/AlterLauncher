#include <mcpe/Network.h>

#include <string>

#include <mcpe/GameSDK.h>

// LoopbackPacketSender, verbatim from the original single-tree file.
namespace mcpe {

bool LoopbackPacketSender::sendChatMessage(const std::string &text) const {
    // Same route as the SDK chat API - one path, one identity proof. Real
    // network packets need the game's serialize machinery and stay out of
    // reach until a rule pins them explicitly.
    return GameSdk::requestMessage(text);
}

}  // namespace mcpe
