//
// Created by Aanswer_Dev on 2025/8/5.
//

#include "NativeInvoker.h"
#include "Tag.h"
#include <tuple>

NativeInvoker* gNativeInvoker = nullptr;

NativeInvoker::NativeInvoker(void* base)
        : base(reinterpret_cast<uintptr_t>(base)) {
    gNativeInvoker = this;
}

template <typename T>
T NativeInvoker::ptr(uintptr_t offset) const {
    return reinterpret_cast<T>(base + offset);
}

template <typename Ret, typename... Args>
Ret NativeInvoker::callFun(uintptr_t offset, Args... args) {
    using Fn = Ret(*)(Args...);
    Fn fn = reinterpret_cast<Fn>(offset);
    return fn(args...);
}

template <typename Ret, typename... Args>
Ret NativeInvoker::call(uintptr_t offset, Args... args) const {
    using Fuc = Ret(*)(Args...);
    Fuc fuc = reinterpret_cast<Fuc>(base + offset);
    return fuc(args...);
}



template<uintptr_t index, typename TRet = void *, typename... TArgs>
 auto NativeInvoker::callVirtualFunc(void const *ptr, TArgs... argList) -> TRet {
    using Fn = TRet(*)(void const *, decltype(argList)...);
    return (*((Fn **) ptr))[index](ptr, argList...);
}


NativeInvoker& NativeInvoker::get() {
    return *gNativeInvoker;
}

// 显式实例化
template void* NativeInvoker::ptr<void*>(uintptr_t) const;
template std::unique_ptr<Tag> NativeInvoker::call<std::unique_ptr<Tag>>(uintptr_t) const;