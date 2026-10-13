// Standalone Android/Linux regression: link Reflection.cpp and RttiLookup.cpp.
#include <mcpe/core/ClassCommon.h>
#include <mcpe/core/Reflection.h>
#include <memory/RttiLookup.h>
#include <sys/mman.h>
#include <cassert>
#include <cstdio>

namespace {
uintptr_t expectedThis;
int first(void *self, int n) {
    assert(reinterpret_cast<uintptr_t>(self) == expectedThis);
    return n + 10;
}
int second(void *self, int n) {
    assert(reinterpret_cast<uintptr_t>(self) == expectedThis);
    return n + 20;
}
int wrong(void *, int) { return -1000; }
}

// Isolate dispatch arithmetic from guest-library discovery. Reflection and RTTI
// below still use their real implementations and process mappings.
namespace mcpe { namespace detail {
bool inWritable(uintptr_t p) { return p != 0; }
bool inGuestLib(uintptr_t p) { return p != 0; }
bool inExec(uintptr_t p) { return p != 0; }
}}

int main() {
    static_assert(sizeof(uintptr_t) == 8, "This regression targets the existing 64-bit RTTI layout");
    uintptr_t primary[] = {0, 0, reinterpret_cast<uintptr_t>(first),
                           reinterpret_cast<uintptr_t>(second),
                           reinterpret_cast<uintptr_t>(wrong), reinterpret_cast<uintptr_t>(wrong)};
    uintptr_t auxiliary[] = {static_cast<uintptr_t>(-8), 0,
                             reinterpret_cast<uintptr_t>(second), reinterpret_cast<uintptr_t>(wrong)};
    const uintptr_t header = reinterpret_cast<uintptr_t>(primary);
    const uintptr_t auxHeader = reinterpret_cast<uintptr_t>(auxiliary);
    uintptr_t object[] = {reinterpret_cast<uintptr_t>(&primary[2])};
    uintptr_t auxObject[] = {reinterpret_cast<uintptr_t>(&auxiliary[2])};
    uintptr_t badObject[] = {header};
    expectedThis = reinterpret_cast<uintptr_t>(object);
    assert(RttiLookup::addressPoint(0) == 0);
    assert(RttiLookup::addressPoint(header) == object[0]);
    assert(RttiLookup::slotAt(header, 0) == primary[2]);
    assert(RttiLookup::slotAt(header, 1) == primary[3]);
    assert((mcpe::detail::callVirtual<0, int>(expectedThis, 7) == 17));
    assert((mcpe::detail::callVirtual<1, int>(expectedThis, 7) == 27));
    assert((mcpe::detail::callVirtual<0, int>(0, 7) == 0));

    const std::vector<uintptr_t> tables = {0, header, auxHeader};
    assert(Reflection::vtableMatchesAny(expectedThis, tables.data(), tables.size()));
    assert(Reflection::vtableMatchesAny(reinterpret_cast<uintptr_t>(auxObject), tables.data(), tables.size()));
    assert(!Reflection::vtableMatchesAny(reinterpret_cast<uintptr_t>(badObject), tables.data(), tables.size()));
    assert(!Reflection::vtableMatchesAny(expectedThis, &object[0], 1));
    assert(!Reflection::vtableMatchesAny(expectedThis, nullptr, 0));
    uintptr_t owner[] = {reinterpret_cast<uintptr_t>(badObject), expectedThis,
                         reinterpret_cast<uintptr_t>(auxObject), 0};
    const uintptr_t ownerAddress = reinterpret_cast<uintptr_t>(owner);
    assert(Reflection::findMember(ownerAddress, tables, sizeof(owner)) == expectedThis);
    const auto members = Reflection::findAllMembers(ownerAddress, tables, sizeof(owner));
    assert(members.size() == 2 && members[0] == expectedThis && members[1] == owner[2]);
    assert(Reflection::findMember(ownerAddress, tables, sizeof(uintptr_t)) == 0);
    // The legacy inline scan compares a word directly: it takes an address point.
    uintptr_t inlineOwner[] = {0, object[0], 0};
    assert(RttiLookup::findMemberByVtable(reinterpret_cast<uintptr_t>(inlineOwner),
           RttiLookup::addressPoint(header), sizeof(inlineOwner)) == reinterpret_cast<uintptr_t>(&inlineOwner[1]));

    // A mapping created AFTER the first Reflection call must still be accepted:
    // the game allocates constantly, so a snapshot frozen at first use rejected a
    // live LocalPlayer on device. The cache above was parsed by the assertions
    // before this mmap, which is what makes this a staleness regression.
    {
        const size_t pageSize = 4096;
        void *fresh = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        assert(fresh != MAP_FAILED);
        uintptr_t *slot = reinterpret_cast<uintptr_t *>(fresh);
        slot[0] = 0;                                    // offset_to_top
        slot[1] = 0;                                    // typeinfo
        slot[2] = reinterpret_cast<uintptr_t>(first);   // virtual 0
        slot[3] = reinterpret_cast<uintptr_t>(second);  // virtual 1
        uintptr_t *lateObject = slot + 16;
        lateObject[0] = reinterpret_cast<uintptr_t>(slot + 2);
        const uintptr_t lateTables[] = {reinterpret_cast<uintptr_t>(slot)};
        assert(Reflection::vtableMatchesAny(reinterpret_cast<uintptr_t>(lateObject), lateTables, 1));
        uintptr_t lateOwner[] = {0, reinterpret_cast<uintptr_t>(lateObject)};
        const std::vector<uintptr_t> lateTableList(lateTables, lateTables + 1);
        assert(Reflection::findMember(reinterpret_cast<uintptr_t>(lateOwner), lateTableList,
                                      sizeof(lateOwner)) == reinterpret_cast<uintptr_t>(lateObject));
        const auto lateMembers = Reflection::findAllMembers(reinterpret_cast<uintptr_t>(lateOwner),
                                                            lateTableList, sizeof(lateOwner));
        assert(lateMembers.size() == 1 && lateMembers[0] == reinterpret_cast<uintptr_t>(lateObject));

#if defined(__aarch64__)
        // Scudo hands out TAGGED pointers (0xb4000073d628dc00) and the hardware
        // ignores the top byte on access, but /proc/self/maps lists the untagged
        // range. Comparing the raw value made every heap object look unmapped,
        // which is how a genuine LocalPlayer - vptr exactly the RTTI address
        // point - was refused as looksPlayer=0 on device.
        const uintptr_t taggedObject =
            reinterpret_cast<uintptr_t>(lateObject) | (static_cast<uintptr_t>(0xb4) << 56);
        const uintptr_t taggedOwner =
            reinterpret_cast<uintptr_t>(lateOwner) | (static_cast<uintptr_t>(0xb4) << 56);
        assert(Reflection::vtableMatchesAny(taggedObject, lateTables, 1));
        assert(Reflection::findMember(taggedOwner, lateTableList, sizeof(lateOwner)) ==
               reinterpret_cast<uintptr_t>(lateObject));
        // The tagged vptr word itself must classify too: a real object's vptr is
        // read straight out of guest memory and can carry the same tag.
        lateObject[0] = reinterpret_cast<uintptr_t>(slot + 2) |
                        (static_cast<uintptr_t>(0xb4) << 56);
        assert(Reflection::vtableMatchesAny(reinterpret_cast<uintptr_t>(lateObject), lateTables, 1));
        lateObject[0] = reinterpret_cast<uintptr_t>(slot + 2);
#endif
        munmap(fresh, pageSize);
    }
    std::puts("PASS: address-point dispatch, RTTI slots, primary/aux identity, member scans, late mappings");
}
