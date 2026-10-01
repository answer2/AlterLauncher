package com.answer.launcher.ui;

import android.view.MenuItem;

import androidx.annotation.NonNull;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.function.Function;

/**
 * Sets a toolbar menu callback from Kotlin without exposing the appcompat
 * {@code Toolbar.OnMenuItemClickListener} SAM type to the Kotlin compiler. The
 * compile-only guest dumps shadow that nested type on the Kotlin classpath, so the
 * bridge lives here where the Java classpath ordering is already corrected.
 */
public final class ToolbarMenu {

    private ToolbarMenu() {
    }

    public static void onClick(@NonNull MaterialToolbar toolbar,
                               @NonNull Function<MenuItem, Boolean> handler) {
        toolbar.setOnMenuItemClickListener(item -> handler.apply(item));
    }
}
