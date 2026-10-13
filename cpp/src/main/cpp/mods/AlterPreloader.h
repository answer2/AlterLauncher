#ifndef ALTER_NATIVE_MODS_ALTERPRELOADER_H
#define ALTER_NATIVE_MODS_ALTERPRELOADER_H

#include <jni.h>
#include <string>

/**
 * AlterLauncher's own native-mod loader — a drop-in replacement for the part of
 * Levi's `libpreloader.so` that actually matters at launch: read a mod's
 * manifest, dlopen it, dispatch whichever entry point it exports, and enable it
 * once the game is up.
 *
 * <p>Why a second implementation exists: the upstream preloader is ~19 MB of
 * mod-menu / HUD-editor / SVG / input runtime that this launcher does not use,
 * and loading it drags GlossHook's linker patch into the process, which fights
 * shadowhook over `soinfo::call_constructors`. This one has no hook framework
 * of its own — it only maps mod libraries — so with it selected the launch path
 * stays on exactly one framework and the 19 MB is never touched.
 *
 * <p>Compatibility is by contract, not by code sharing:
 * <ul>
 *   <li>the same mod directory shape (a `manifest.json` of type
 *       `preload-native` next to the entry library),</li>
 *   <li>the same entry points, probed in the same order
 *       (`PLGetModRegistration` → `PLMod_Load` → `LeviMod_Load` → none),</li>
 *   <li>the same `PLModInfo` C struct handed to the C entry points,</li>
 *   <li>the same C++ `ModRegistration` / `ModContext` dispatch for
 *       registration-style mods, built from the upstream SDK headers
 *       (see `AlterCppMods.h`),</li>
 *   <li>the same Java-facing method names, on our own bridge class.</li>
 * </ul>
 * A registration-style mod that also links `libpreloader.so` is the one
 * exception: the runtime state it can reach (`NativeMod::current()`, the
 * mod-menu owner stack) lives inside that library and is only set by code it
 * dispatched itself, so such a mod is handed over — mapped on demand, for that
 * mod only.
 */
namespace AlterPreloader {

    /** Mirrors the launcher's native-mods switch; false means every entry point refuses. */
    void setModsEnabled(bool enabled);

    bool modsEnabled();

    /**
     * Starts the launch-time scan on a worker thread: every {@code .so} under
     * {@code modsDir} is read off disk (mapped read-only, never loaded, so no
     * constructors and no hooks run early) for a dependency on
     * {@code libpreloader.so} or a Gloss entry point. When one shows up — or when
     * {@code eagerUpstream} says the upstream preloader is the selected
     * implementation anyway — the same thread dlopens {@code libpreloader.so}, so
     * the ~19 MB overlaps the Java-side launch work instead of blocking on it.
     *
     * <p>The answer matters before any hook framework initialises: GlossHook and
     * shadowhook both patch the linker's {@code soinfo::call_constructors}, and
     * whichever patches second trampolines into the first forever.
     */
    void prepareAsync(const std::string &modsDir, bool eagerUpstream);

    /**
     * Blocks until {@link #prepareAsync} finished and returns the mapped
     * {@code libpreloader.so}, or null when nothing needs it. The caller owns the
     * GlossHook claim that follows.
     */
    void *awaitUpstreamHandle();

    /** Registers the natives of {@code com.answer.launcher.core.mods.AlterModManager}. */
    bool registerNatives(JNIEnv *env);

    /** One line for the log and the settings screen: what was loaded and how long it took. */
    std::string stats();
}

#endif //ALTER_NATIVE_MODS_ALTERPRELOADER_H
