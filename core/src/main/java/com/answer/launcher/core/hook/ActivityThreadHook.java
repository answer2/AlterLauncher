package com.answer.launcher.core.hook;

/**
 * Placeholder hook group for {@code ActivityThread} level interception
 * (per-activity base context, package-info substitution).
 *
 * <p>It intentionally installs nothing today: every hook that used to live here
 * was commented out, and the substitution they performed is now achieved by
 * inserting the game's {@code LoadedApk} into {@code ActivityThread.mPackages}
 * during runtime assembly. Keeping the group preserves the site ordering used by
 * {@link com.answer.launcher.core.manager.HookManager#inject(int)} and
 * {@code pauseHook}, so removing it would silently shift every later index.
 *
 * <p>Site 0.
 */
public class ActivityThreadHook extends BaseHook {

    @Override
    public void init() {
        // No-op by design; see class note.
    }
}
