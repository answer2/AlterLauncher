#include <mcpe/MoveInputHandler.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::safeReadU8;
using detail::dataPin;
using detail::inWritable;
using detail::inExec;

// MoveInputHandler methods are virtual calls through the vtable.
// Each needs a vtable.<key> entry in the rule file.

static bool callBoolMethod(uintptr_t self, const char *key) {
    if (self == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string(key), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self));
}

bool MoveInputHandler::sneakInput() const { return callBoolMethod(self_, "moveInputSneak"); }
bool MoveInputHandler::spaceInput() const { return callBoolMethod(self_, "moveInputSpace"); }
bool MoveInputHandler::forwardLeftInput() const { return callBoolMethod(self_, "moveInputFwdLeft"); }
bool MoveInputHandler::forwardRightInput() const { return callBoolMethod(self_, "moveInputFwdRight"); }
bool MoveInputHandler::forwardInput() const { return callBoolMethod(self_, "moveInputForward"); }
bool MoveInputHandler::backwardInput() const { return callBoolMethod(self_, "moveInputBackward"); }
bool MoveInputHandler::leftInput() const { return callBoolMethod(self_, "moveInputLeft"); }
bool MoveInputHandler::rightInput() const { return callBoolMethod(self_, "moveInputRight"); }
bool MoveInputHandler::upInput() const { return callBoolMethod(self_, "moveInputUp"); }
bool MoveInputHandler::downInput() const { return callBoolMethod(self_, "moveInputDown"); }
bool MoveInputHandler::cInput() const { return callBoolMethod(self_, "moveInputC"); }
bool MoveInputHandler::isMove() const { return callBoolMethod(self_, "moveInputIsMove"); }

bool MoveInputHandler::getForwardMovement(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("moveInputGetForward"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = float (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool MoveInputHandler::getSideMovement(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("moveInputGetSide"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = float (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

}  // namespace mcpe