package com.answer.launcher.ui;

import java.util.function.Consumer;

/**
 * One-shot bridge between the sign-in worker (which knows when the device code was redeemed) and
 * the in-app {@link com.answer.launcher.ui.activity.WebViewLoginActivity} (which should dismiss
 * itself the moment that happens).
 *
 * <p>Kept as a single active listener rather than a broadcast: only one sign-in can run at a time
 * (the dialog refuses while the game runtime is up), and the WebView registers on open and clears
 * on destroy, so a stale listener can never fire into a dead activity. The worker stays the source
 * of truth — the WebView does not try to guess success from Microsoft's page, which changes.
 */
public final class LoginSession {

    public static final LoginSession ACTIVE = new LoginSession();

    private volatile Consumer<Boolean> listener;

    private LoginSession() {
    }

    public void attach(Consumer<Boolean> onFinished) {
        listener = onFinished;
    }

    public void detach(Consumer<Boolean> onFinished) {
        if (listener == onFinished) {
            listener = null;
        }
    }

    /** Called by the sign-in worker once redemption succeeds (true) or ends (false). */
    public void finish(boolean success) {
        Consumer<Boolean> current = listener;
        if (current != null) {
            current.accept(success);
        }
    }
}
