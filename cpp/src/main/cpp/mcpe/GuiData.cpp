#include <mcpe/GuiData.h>

#include <cstring>

#include <mcpe/GameSDK.h>
#include <util/SigScanner.h>

// GuiData method bodies.
namespace mcpe {

using detail::safeReadF32;
using detail::dataPin;
using detail::inExec;

bool GuiData::windowSize(Vec2 *gameSize, Vec2 *realSize) const {
    if (self_ == 0) return false;
    uintptr_t fields = 0;
    if (!dataPin("guiDataWindowSize", &fields)) return false;  // d0x48 on 1.26.x
    float gw = 0.f, gh = 0.f;
    if (!safeReadF32(self_, fields, &gw)) return false;
    if (!safeReadF32(self_, fields + 4, &gh)) return false;
    if (!(gw > 0.f && gw < 16384.f) || !(gh > 0.f && gh < 16384.f)) return false;
    if (gameSize != nullptr) {
        gameSize->x = gw;
        gameSize->y = gh;
    }
    if (realSize != nullptr) {
        // Reference layout: the real pair sits 0x10 before the game pair.
        float rw = 0.f, rh = 0.f;
        if (safeReadF32(self_, fields - 0x10, &rw) && safeReadF32(self_, fields - 0xC, &rh) &&
            rw > 0.f && rh > 0.f) {
            realSize->x = rw;
            realSize->y = rh;
        } else {
            realSize->x = gw;
            realSize->y = gh;
        }
    }
    return true;
}

// Internal: resolve the addMessage function address.
static uintptr_t resolveAddMessage() {
    uintptr_t route = GameSdk::addMessageRouteRef();
    if (route != 0) return route;
    return SigScanner::resolve(std::string("guiDataAddMessage"));
}

// The route is the game's TYPE-1 chat wrapper (see GameSDK.cpp deliverMessage
// for the full story): (GuiData*, sender, message, s3, s4, s5, float duration).
// Every string argument is dereferenced by the GuiMessage ctor, so they must be
// real std::string objects — empty ones are fine, nullptr is not.
bool GuiData::sendMessage(const std::string &message) const {
    if (self_ == 0) return false;
    uintptr_t fn = resolveAddMessage();
    if (fn == 0 || !inExec(fn)) return false;
    using AddMessageFn = void (*)(void *, const std::string &, const std::string &,
                                   const std::string &, const std::string &,
                                   const std::string &, float);
    static const std::string empty;
    reinterpret_cast<AddMessageFn>(fn)(reinterpret_cast<void *>(self_), empty, message,
                                       empty, empty, empty, 5.0f);
    return true;
}

// NOTE: the vanilla tip path is a separate wrapper (RVA 0x923a29c on 1.26.45.1,
// (GuiData*, msg, msg2, int flag) building a type-2 GuiMessage with duration
// 10.0f). Until that entry is pinned in the rule file, tips degrade to a plain
// chat line — visible, correctly delivered, just not toast-styled.
bool GuiData::showTipMessage(const std::string &message) const {
    return sendMessage(message);
}
bool GuiData::displayChatMessage(const std::string &sender, const std::string &message,
                                  float duration) const {
    if (self_ == 0) return false;
    uintptr_t fn = SigScanner::resolve(std::string("guiDataDisplayChatMessage"));
    if (fn == 0 || !inExec(fn)) return false;
    // DisplayChatMessage signature (reference: BT GuiDataDisplayChatMessage):
    // void GuiData::displayChatMessage(const std::string& sender, const std::string& msg, float dur)
    using Fn = void (*)(void *, const std::string &, const std::string &, float);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), sender, message, duration);
    return true;
}

}  // namespace mcpe
