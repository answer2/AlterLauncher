//
// Created by Aanswer_Dev on 2025/8/5.
//

#ifndef ALTERLAUNCHER_TRANCEUTILS_H
#define ALTERLAUNCHER_TRANCEUTILS_H

#ifdef __cplusplus
extern "C" {
#endif

// 打印当前 native 调用栈（使用 _Unwind_Backtrace）
void print_callstack_unwind();

#ifdef __cplusplus
}
#endif


#endif //ALTERLAUNCHER_TRANCEUTILS_H
