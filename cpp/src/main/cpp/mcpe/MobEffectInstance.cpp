#include <mcpe/MobEffectInstance.h>
#include <cstring>
#include <mcpe/GameSDK.h>

namespace mcpe {
using detail::safeReadU8;
using detail::safeReadI32;
using detail::dataPin;
using detail::inWritable;

int MobEffectInstance::getId() const {
    if (self_ == 0) return -1;
    uintptr_t off = 0;
    if (!dataPin("mobEffectIdField", &off)) return -1;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return -1;
    return static_cast<int>(val);
}

int MobEffectInstance::getAmplifier() const {
    if (self_ == 0) return -1;
    uintptr_t off = 0;
    if (!dataPin("mobEffectAmplifierField", &off)) return -1;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return -1;
    return static_cast<int>(val);
}

int MobEffectInstance::getDuration() const {
    if (self_ == 0) return -1;
    uintptr_t off = 0;
    if (!dataPin("mobEffectDurationField", &off)) return -1;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return -1;
    return val;
}

bool MobEffectInstance::isAmbient() const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("mobEffectAmbientField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    return val != 0;
}

bool MobEffectInstance::showParticles() const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("mobEffectParticlesField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    return val != 0;
}

}  // namespace mcpe
