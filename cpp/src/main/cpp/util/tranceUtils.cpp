//
// Created by Aanswer_Dev on 2025/8/5.
//

#include "tranceUtils.h"
#include "android/android_log.h"
#include <unwind.h>
#include <stdio.h>

struct StackCrawlState {
    int count;
};

static _Unwind_Reason_Code trace_func(struct _Unwind_Context* context, void* arg) {
    StackCrawlState* state = static_cast<StackCrawlState*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        LOGD("frame #%d: pc = 0x%lx\n", state->count++, (long)pc);
    }
    return _URC_NO_REASON;
}

void print_callstack_unwind() {
    StackCrawlState state = {0};
    _Unwind_Backtrace(trace_func, &state);
}
