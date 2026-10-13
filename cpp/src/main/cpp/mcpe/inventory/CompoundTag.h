#ifndef ALTER_LAUNCHER_MCPE_COMPOUND_TAG_H
#define ALTER_LAUNCHER_MCPE_COMPOUND_TAG_H

// CompoundTag - NBT data access.
#include <string>
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class ListTag;

class CompoundTag {
public:
    explicit CompoundTag(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    std::string getString(const std::string &key) const;
    bool getInt(const std::string &key, int *out) const;
    bool getFloat(const std::string &key, float *out) const;
    bool getByte(const std::string &key, int8_t *out) const;
    bool getShort(const std::string &key, int16_t *out) const;
    bool getInt64(const std::string &key, int64_t *out) const;
    bool getBoolean(const std::string &key, bool *out) const;
    bool contains(const std::string &key) const;
    Handle getList(const std::string &key) const;

private:
    uintptr_t self_;
};

class ListTag {
public:
    explicit ListTag(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    bool size(int *out) const;
    Handle getCompound(int index) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_COMPOUND_TAG_H
