//
// Created by Aanswer_Dev on 2025/8/5.
//

#ifndef ALTERLAUNCHER_NATIVEINVOKER_H
#define ALTERLAUNCHER_NATIVEINVOKER_H

#pragma once
#include <cstdint>
#include <memory>
#include <functional>

class NativeInvoker {
public:
    explicit NativeInvoker(void* base);

    template <typename T = void*>
    T ptr(uintptr_t offset) const;

    template <typename Ret, typename... Args>
    static Ret callFun(uintptr_t offset, Args... args);

    template <typename Ret, typename... Args>
    Ret call(uintptr_t offset, Args... args) const;

    template <int VIndex, typename Ret, typename... Args>
   static Ret callV(void* obj, Args... args) {
        void** vtable = *reinterpret_cast<void***>(obj);
        using Fn = Ret(*)(void*, Args...);
        Fn fn = reinterpret_cast<Fn>(vtable[VIndex]);
        return fn(obj, args...);
    }

    template<uintptr_t index, typename TRet , typename... TArgs>
    static TRet callVirtualFunc(const void *ptr, TArgs... argList);

    template <typename Ret, typename... Args>
    static Ret callV_runtime(void* obj, int vIndex, Args... args) {
        void** vtable = *reinterpret_cast<void***>(obj);
        using Fn = Ret(*)(void*, Args...);
        Fn fn = reinterpret_cast<Fn>(vtable[vIndex]);
        return fn(obj, args...);
    }

    static NativeInvoker& get();

private:
    uintptr_t base;
};

#endif //ALTERLAUNCHER_NATIVEINVOKER_H
