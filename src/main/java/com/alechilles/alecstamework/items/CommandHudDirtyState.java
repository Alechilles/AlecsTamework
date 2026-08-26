package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bounded contributor-local dirty state with overflow promotion. */
final class CommandHudDirtyState {
    private final LinkedHashSet<String> paths = new LinkedHashSet<>();
    private boolean fullRefresh;

    /** Marks all contributor data dirty. */
    synchronized void markAll() {
        fullRefresh = true;
        paths.clear();
    }

    /** Adds normalized paths, promoting overflow to a full refresh. */
    synchronized void markPaths(@Nonnull Set<String> values) {
        Objects.requireNonNull(values, "paths");
        if (fullRefresh) return;
        for (String value : values) {
            String normalized = normalizePath(value);
            if (normalized == null) continue;
            paths.add(normalized);
            if (paths.size() > CommandHudDirtyScope.MAX_PATHS) {
                fullRefresh = true;
                paths.clear();
                return;
            }
        }
    }

    @Nullable
    private static String normalizePath(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isBlank() ? null : trimmed;
    }

    /** Returns whether this state has a pending invalidation. */
    synchronized boolean dirty() {
        return fullRefresh || !paths.isEmpty();
    }

    /** Takes and clears the bounded scope for the next composition. */
    @Nonnull
    synchronized CommandHudDirtyScope take() {
        CommandHudDirtyScope result = fullRefresh
                ? CommandHudDirtyScope.full()
                : CommandHudDirtyScope.paths(paths);
        fullRefresh = false;
        paths.clear();
        return result;
    }

    /** Clears pending invalidations without producing a scope. */
    synchronized void clear() {
        fullRefresh = false;
        paths.clear();
    }

    /** Returns a detached diagnostic copy of the current paths. */
    @Nonnull
    synchronized Set<String> paths() {
        if (fullRefresh || paths.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(paths));
    }
}
