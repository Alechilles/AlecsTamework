package com.alechilles.alecstamework.api.commandhud;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, bounded contributor-local invalidation scope. */
public final class CommandHudDirtyScope {
    /** Maximum number of retained paths before a full refresh is requested. */
    public static final int MAX_PATHS = 256;

    private final boolean fullRefresh;
    private final Set<String> paths;

    /** Creates a bounded scope; overflow is promoted to a full refresh. */
    public CommandHudDirtyScope(boolean fullRefresh, @Nullable Set<String> paths) {
        Bounded bounded = copyPaths(paths);
        if (fullRefresh || bounded.overflow()) {
            this.fullRefresh = true;
            this.paths = Set.of();
        } else {
            this.fullRefresh = false;
            this.paths = bounded.values();
        }
    }

    /** Creates a scope for all contributor-local data. */
    @Nonnull
    public static CommandHudDirtyScope full() {
        return new CommandHudDirtyScope(true, Set.of());
    }

    /** Creates an empty scope. */
    @Nonnull
    public static CommandHudDirtyScope empty() {
        return new CommandHudDirtyScope(false, Set.of());
    }

    /** Creates a path-level scope. */
    @Nonnull
    public static CommandHudDirtyScope paths(@Nullable Set<String> paths) {
        return new CommandHudDirtyScope(false, paths);
    }

    /** Returns whether the complete contributor namespace must be recomposed. */
    public boolean fullRefresh() {
        return fullRefresh;
    }

    /** Alias for callers that use the shorter dirty-scope vocabulary. */
    public boolean all() {
        return fullRefresh;
    }

    /** Returns immutable normalized contributor-local paths. */
    @Nonnull
    public Set<String> paths() {
        return paths;
    }

    public boolean contains(@Nullable String path) {
        String normalized = normalizePath(path);
        return normalized != null && paths.contains(normalized);
    }

    /** Merges two scopes without retaining more than the bounded path count. */
    @Nonnull
    public CommandHudDirtyScope mergedWith(@Nonnull CommandHudDirtyScope other) {
        Objects.requireNonNull(other, "other");
        if (fullRefresh || other.fullRefresh) return full();
        LinkedHashSet<String> merged = new LinkedHashSet<>(paths);
        merged.addAll(other.paths);
        return new CommandHudDirtyScope(false, merged);
    }

    @Nonnull
    private static Bounded copyPaths(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) return new Bounded(Set.of(), false);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Iterator<String> iterator = source.iterator();
        int inspected = 0;
        while (iterator.hasNext() && inspected++ <= MAX_PATHS) {
            String normalized = normalizePath(iterator.next());
            if (normalized != null) values.add(normalized);
            if (values.size() > MAX_PATHS) return new Bounded(Set.of(), true);
        }
        return new Bounded(immutable(values), iterator.hasNext());
    }

    @Nullable
    static String normalizePath(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isBlank() ? null : trimmed;
    }

    @Nonnull
    private static Set<String> immutable(@Nonnull LinkedHashSet<String> values) {
        return values.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private record Bounded(@Nonnull Set<String> values, boolean overflow) {
    }
}
