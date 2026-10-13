# AlterLauncher

一个 Android 上的 Minecraft: Bedrock Edition（`com.mojang.minecraftpe`）第三方启动器：管理多实例与资源包、以微软设备码流程登录，并把一个原生 mod SDK 注入到游戏进程内。

> **非官方项目。** 与 Mojang / Microsoft 无关。它修改游戏进程运行方式，请自行判断风险；涉及登录凭据与世界存档，动手前建议备份。

---

## 它做什么

- **多实例与资源管理**：安装/导入基岩版安装包、资源包、插件包，按实例隔离数据目录。
- **微软登录**：设备码（device-code）流程为主，WebView 为可选回退；登录态以 XAL 形式导出给游戏使用。
- **多开**：一次可拉起多个游戏槽位，槽位之间互相隔离（详见 `docs/multi-instance-concurrency-design.md`）。
- **原生 mod SDK**：向游戏进程注入 `libalternative_core.so`，对 mod 作者暴露 `cpp/src/main/cpp/api`（启动时加载 `plugins/*.so`；协议见 `docs/native-mod-protocol.md`、插件开发见 `docs/plugin-development.md`）。

## 架构

```
:app     界面与实例管理（com.answer.launcher）
:core    注入、Hook、XAL 导出、进程/数据隔离（com.answer.launcher.core）
:auth    微软登录与会话（com.answer.launcher.auth）
:api     面向 mod 作者的公开 API（com.answer.api）
:cpp     原生侧 libalternative_core.so（shadowhook + 版本规则驱动的 SDK）
:stub    承载用的辅助模块
```

原生侧按职责分区：`hook/`（安装与拦截）、`mcpe/`（游戏对象模型：actor / client / world / inventory / network）、`memory/`（RTTI 查找、签名扫描、规则解析）、`mods/`、`runtime/`、`android/`、`jni/`、`util/`。

## 版本规则：一切偏移都在数据里，不在代码里

 stripped 的 `libminecraftpe.so` 每次发布都会挪地址，所以二进制偏移**不硬编码**在 C++ 里，而是放在随包资源
`core/src/main/assets/game-hooks.ini`：按版本区间分 `[rule]` 块，键分四类——
`sig.*`（字节特征码，`?` 为半字节通配）、`vtable.*`（`<Class>:<slot>`）、`dat.*`（对象内偏移，`d0x…`）、
`off.*`（已确认的函数 RVA）。

查找按**阶梯**进行，顺序既是正确性要求也是开销要求：

```
pin（已验证的固定值）→ signature（特征码扫描）→ read-only structural scan（只读结构探测）→ virtual（最后才调虚函数）
```

任何一级不成立就返回失败（fail-closed）：**宁可一个功能不存在，也不拿着猜测的偏移/槽位去解引用或发起虚调用**。参考实现的槽位号经常与本构建错位，错位槽位曾两次直接让游戏进程崩溃——这类事故就是这条纪律的来源。

`tools/sdk-re/` 是配套离线研究工具（ELF/RTTI/重定位/特征码/vtable 与 `.so` 对照），
`build/verify_rulefile.py` 是规则门禁：它按代码里实际请求的键核对**每个可达 rule 块**，
既报"代码要用但没钉"（功能在该版本静默缺失），也报"钉了但没人用"（惰性数据）。

## 支持版本

游戏侧已按规则块覆盖 `1.21.91` / `1.21.101` / `1.26.30`–`1.26.50` 区间；日常验证设备运行 **1.26.45.1**，
离线对照样本另有 1.26.52.3。覆盖与否以 `game-hooks.ini` 的 `[rule]` 块为准，未被样本证实的块会被门禁标为
`UNVERIFIED`，不会拿别的版本数字硬填。

## 构建

需要 Android SDK（本地路径写在未纳入版本管理的 `local.properties`）与 NDK；Gradle 8 / AGP 8.8。

```bash
./gradlew :app:assembleDebug                  # 64 位，com.answer.launcher
./gradlew :app:assembleDebug -Pabi32          # 32 位伴生包 com.answer.launcher.x32，输出在 app/build-x32/
./gradlew :app:assembleArm32Debug             # 等价入口（嵌套调用上面的 -Pabi32 构建）
```

`-Pabi32` 变体独立包名、独立输出目录，可与 64 位包共存。注意**不要**在同一次 invocation 里把普通
`assembleDebug` 与 `assembleArm32Debug` 混着跑——嵌套构建会原地复用中间产物，历史上产出过缺类的 APK；
串行执行即可。

## 测试

```bash
cpp/src/test/run_tests.sh              # 默认：NDK aarch64 交叉编译 + 连接设备运行
cpp/src/test/run_tests.sh --desktop    # 桌面 clang++/g++（需宿主 libc++/libstdc++）
```

测试覆盖规则解析、特征码扫描、RTTI/vtable 地址点等纯逻辑（这些不需要游戏在跑）。
真机回归由聊天里的 `666` 触发一次 SDK 自检（`hook/GameHooks.cpp`）：逐项报告句柄解析、
world→screen、投影矩阵、entt 组件枚举等结果并打日志（tag `AlterSelfTest`）。

## 已知未完成

原生 SDK 仍有明确未落地项，公开 API 对它们返回"失败"而不是猜测值：血量/最大血量（当前只能复现游戏自身
`Actor::getHealth` 的读取，但它在活着的本地玩家身上恒返回 0，**语义未证实**，故不对外发布）、`getRegion`、
`getLevel`/`getCamera`、`runtimeId`/`uniqueId`、部分 `Player`/`GameMode` 接口，以及护甲值。
