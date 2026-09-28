#include <mcpe/Dimension.h>
#include <cstring>
#include <mcpe/GameSDK.h>

namespace mcpe {
using detail::safeReadI32;
using detail::dataPin;

bool Dimension::getDimensionId(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("dimensionIdField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

}  // namespace mcpe
