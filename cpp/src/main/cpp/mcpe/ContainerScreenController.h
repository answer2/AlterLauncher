#ifndef ALTER_LAUNCHER_MCPE_CONTAINER_SCREEN_H
#define ALTER_LAUNCHER_MCPE_CONTAINER_SCREEN_H

// ContainerScreenController - inventory/container UI interaction.
#include <string>
#include <mcpe/ClassCommon.h>

namespace mcpe {

class ContainerScreenController {
public:
    explicit ContainerScreenController(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get item stack at container slot.
    // Vtable key: containerScreenGetItemStack.
    Handle getItemStack(const std::string &containerName, int slot) const;

    // Handle auto-place item.
    // Vtable key: containerScreenAutoPlace.
    bool handleAutoPlace(int containerSize, const std::string &containerName, int slot) const;

    // Handle swap between containers.
    // Vtable key: containerScreenSwap.
    bool handleSwap(const std::string &fromName, int fromSlot,
                    const std::string &toName, int toSlot) const;

    // Exit/close the container screen.
    // Vtable key: containerScreenTryExit.
    bool tryExit() const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_CONTAINER_SCREEN_H
