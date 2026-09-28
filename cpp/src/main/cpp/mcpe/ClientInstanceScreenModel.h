#ifndef ALTER_LAUNCHER_MCPE_SCREEN_MODEL_H
#define ALTER_LAUNCHER_MCPE_SCREEN_MODEL_H

// ClientInstanceScreenModel - command execution and screen interaction.
#include <string>
#include <mcpe/ClassCommon.h>

namespace mcpe {

class ClientInstanceScreenModel {
public:
    explicit ClientInstanceScreenModel(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Static accessor: resolve via sig.clientInstanceScreenModelGet.
    static Handle get();

    // Execute a game command (without the leading slash).
    // Vtable key: screenModelExecuteCommand.
    bool executeCommand(const std::string &command) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_SCREEN_MODEL_H
