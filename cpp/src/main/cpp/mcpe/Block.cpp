#include <mcpe/Block.h>
#include <cstring>
#include <mcpe/GameSDK.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadI32;
using detail::dataPin;
using detail::inWritable;

std::string Block::getName() const {
    Handle legacy = toLegacy();
    if (!legacy.valid()) return "";
    return BlockLegacy(legacy.ptr).getName();
}

bool Block::getVariant(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("blockVariantField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

Handle Block::toLegacy() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("blockLegacyField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

std::string BlockLegacy::getName() const {
    if (self_ == 0) return "";
    uintptr_t off = 0;
    if (!dataPin("blockLegacyNameField", &off)) return "";
    if (!inWritable(self_ + off)) return "";
    uint8_t flag = 0;
    if (!detail::safeReadU8(self_, off, &flag)) return "";
    uint32_t len = 0;
    if (!detail::safeReadU32(self_, off + 0x18, &len)) return "";
    if (len == 0 || len > 256) return "";
    const char *data = nullptr;
    if ((flag & 1) != 0) {
        uintptr_t ptr = 0;
        memcpy(&ptr, reinterpret_cast<const void *>(self_ + off), sizeof(ptr));
        ptr &= ~static_cast<uintptr_t>(1);
        data = reinterpret_cast<const char *>(ptr);
    } else {
        data = reinterpret_cast<const char *>(self_ + off + 1);
    }
    if (!inWritable(reinterpret_cast<uintptr_t>(data))) return "";
    return std::string(data, len);
}

}  // namespace mcpe
