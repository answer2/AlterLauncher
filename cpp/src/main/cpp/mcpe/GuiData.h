#ifndef ALTER_LAUNCHER_MCPE_GUI_DATA_H
#define ALTER_LAUNCHER_MCPE_GUI_DATA_H

// GuiData - the UI session singleton: window metrics, chat-message list
// fields, and message display methods.
#include <mcpe/ClassCommon.h>
#include <string>

namespace mcpe {

class GuiData {
public:
    explicit GuiData(uintptr_t self) : self_(self) {}

    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Window sizes. Reference layout (GuiData.h of D:\逆向分析\1\cpp): after a
    // 0x34-byte header come widthReal/heightReal, two more floats, then
    // widthGame/heightGame at +0x48/+0x4C. Rule key dat.guiDataWindowSize=d0x48
    // is the pin; values are sanity-checked (> 0, < 16384) before reporting.
    bool windowSize(Vec2 *gameSize, Vec2 *realSize) const;

    // sendMessage: calls GuiData::addMessage with type=1 (chat).
    // Uses GameSdk::addMessageRouteRef() or sig.guiDataAddMessage.
    bool sendMessage(const std::string &message) const;

    // showTipMessage: currently delivered as a plain chat line (the vanilla
  // type-2 tip wrapper is not pinned yet — see GuiData.cpp).
    bool showTipMessage(const std::string &message) const;

    // displayChatMessage: calls GuiData::displayChatMessage (sig key).
    // Reference: BedrockTools sig.GuiDataDisplayChatMessage.
    bool displayChatMessage(const std::string &sender, const std::string &message,
                            float duration = 5.0f) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_GUI_DATA_H
