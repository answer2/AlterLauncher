#include <mcpe/client/GuiData.h>

#include <cstring>
#include <optional>

#include <mcpe/core/GameSDK.h>
#include <memory/SigScanner.h>

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

bool GuiData::sendMessage(const std::string &message) const {
    return displayChatMessage({}, message);
}

bool GuiData::showTipMessage(const std::string &message) const {
#if defined(__aarch64__)
    static_assert(sizeof(std::string) == 24 && sizeof(std::optional<std::string>) == 32,
                  "Guest optional-string ABI mismatch");
    if (self_ == 0) return false;
    uintptr_t fn = SigScanner::resolve(std::string("guiDataShowTipMessage"));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, const std::string &, const std::optional<std::string> &, bool);
    const std::optional<std::string> secondary;
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), message, secondary, false);
    return true;
#else
    return false;
#endif
}
// This string wrapper is distinct from BedrockTools' GuiMessage-based addMessage entry.
bool GuiData::displayChatMessage(const std::string &sender, const std::string &message) const {
    if (self_ == 0) return false;
    uintptr_t fn = resolveAddMessage();
    if (fn == 0) fn = SigScanner::resolve(std::string("guiDataDisplayChatMessage"));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, const std::string &, const std::string &,
                        const std::optional<std::string> &, const std::string &, const std::string &);
    static const std::string empty;
    const std::optional<std::string> secondary;
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), sender, message, secondary, empty, empty);
    return true;
}

}  // namespace mcpe
