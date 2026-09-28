#ifndef ALTER_LAUNCHER_MCPE_MOVE_INPUT_H
#define ALTER_LAUNCHER_MCPE_MOVE_INPUT_H

// MoveInputHandler - player input state (movement, jump, sneak).
#include <mcpe/ClassCommon.h>

namespace mcpe {

class MoveInputHandler {
public:
    explicit MoveInputHandler(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    bool sneakInput() const;
    bool spaceInput() const;
    bool forwardLeftInput() const;
    bool forwardRightInput() const;
    bool forwardInput() const;
    bool backwardInput() const;
    bool leftInput() const;
    bool rightInput() const;
    bool upInput() const;
    bool downInput() const;
    bool cInput() const;
    bool isMove() const;

    // Get movement values as floats (-1.0 to 1.0).
    bool getForwardMovement(float *out) const;
    bool getSideMovement(float *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_MOVE_INPUT_H
