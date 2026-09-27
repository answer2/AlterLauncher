package com.answer.launcher.api;

/**
 * Structured result of one load attempt, shown verbatim by the UI. No more
 * silent {@code e.printStackTrace()} (levi-compat-design.md §5.5).
 */
public final class LoadResult {

    public final String source;
    public final PluginManifest manifest;
    public final LoadStatus status;
    public final String reason;

    public LoadResult(String source, PluginManifest manifest, LoadStatus status, String reason) {
        this.source = source;
        this.manifest = manifest;
        this.status = status;
        this.reason = reason;
    }

    public static LoadResult installed(String source, PluginManifest m) {
        return new LoadResult(source, m, LoadStatus.INSTALLED, null);
    }

    public static LoadResult failed(String source, PluginManifest m, String reason) {
        return new LoadResult(source, m, LoadStatus.FAILED, reason);
    }

    public static LoadResult skipped(String source, PluginManifest m, String reason) {
        return new LoadResult(source, m, LoadStatus.SKIPPED, reason);
    }

    public String id() {
        return manifest != null && !manifest.id.isEmpty() ? manifest.id : source;
    }

    public String name() {
        return manifest != null && !manifest.name.isEmpty() ? manifest.name : source;
    }

    @Override
    public String toString() {
        return name() + " [" + status + (reason != null ? ": " + reason : "") + "]";
    }
}
