package com.alechilles.alecstamework.api.commandui;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable bounded invalidation scope for one contributor refresh. */
public final class CommandUiDirtyScope {
    /** Maximum number of retained paths or row IDs in one scope. */
    public static final int MAX_ENTRIES = 256;

    private final boolean all;
    private final boolean page;
    private final Set<String> paths;
    private final Set<UUID> rowIds;

    /** Creates a bounded immutable scope. Overflow collapses to {@link #full()}. */
    public CommandUiDirtyScope(
            boolean all,
            boolean page,
            @Nullable Set<String> paths,
            @Nullable Set<UUID> rowIds
    ) {
        Bounded<String> boundedPaths = copyPaths(paths);
        Bounded<UUID> boundedRows = copyRows(rowIds);
        if (all || boundedPaths.overflow() || boundedRows.overflow()) {
            this.all = true;
            this.page = true;
            this.paths = Set.of();
            this.rowIds = Set.of();
            return;
        }
        this.all = false;
        this.page = page;
        this.paths = boundedPaths.values();
        this.rowIds = boundedRows.values();
    }

    /** Returns a scope that requests the complete contributor namespace. */
    @Nonnull
    public static CommandUiDirtyScope full() {
        return new CommandUiDirtyScope(true, true, Set.of(), Set.of());
    }

    /** Returns an empty scope. */
    @Nonnull
    public static CommandUiDirtyScope empty() {
        return new CommandUiDirtyScope(false, false, Set.of(), Set.of());
    }

    /** Returns a page-level scope. */
    @Nonnull
    public static CommandUiDirtyScope pageScope() {
        return new CommandUiDirtyScope(false, true, Set.of(), Set.of());
    }

    /** Returns a path-level scope. */
    @Nonnull
    public static CommandUiDirtyScope paths(@Nullable Set<String> paths) {
        return new CommandUiDirtyScope(false, false, paths, Set.of());
    }

    /** Returns a row-level scope. */
    @Nonnull
    public static CommandUiDirtyScope rows(@Nullable Set<UUID> rowIds) {
        return new CommandUiDirtyScope(false, false, Set.of(), rowIds);
    }

    /** Merges two already-bounded scopes without traversing more than 257 entries per set. */
    @Nonnull
    public CommandUiDirtyScope mergedWith(@Nonnull CommandUiDirtyScope other) {
        Objects.requireNonNull(other, "other");
        if (all || other.all) return full();
        Bounded<String> mergedPaths = merge(paths, other.paths);
        Bounded<UUID> mergedRows = merge(rowIds, other.rowIds);
        if (mergedPaths.overflow() || mergedRows.overflow()) return full();
        return new CommandUiDirtyScope(
                false, page || other.page, mergedPaths.values(), mergedRows.values());
    }

    public boolean all() {
        return all;
    }

    public boolean page() {
        return page;
    }

    /** Returns immutable contributor-local paths. */
    @Nonnull
    public Set<String> paths() {
        return paths;
    }

    /** Returns immutable stable companion row IDs. */
    @Nonnull
    public Set<UUID> rowIds() {
        return rowIds;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiDirtyScope that)) return false;
        return all == that.all && page == that.page
                && paths.equals(that.paths) && rowIds.equals(that.rowIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(all, page, paths, rowIds);
    }

    @Override
    public String toString() {
        return "CommandUiDirtyScope[all=" + all + ", page=" + page
                + ", paths=" + paths + ", rowIds=" + rowIds + "]";
    }

    @Nonnull
    private static Bounded<String> copyPaths(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) {
            return new Bounded<>(Set.of(), false);
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Iterator<String> iterator = source.iterator();
        int inspected = 0;
        while (iterator.hasNext() && inspected++ <= MAX_ENTRIES) {
            String value = iterator.next();
            if (value != null && !value.isBlank()) values.add(value.trim());
        }
        return new Bounded<>(immutable(values), iterator.hasNext()
                || values.size() > MAX_ENTRIES);
    }

    @Nonnull
    private static Bounded<UUID> copyRows(@Nullable Set<UUID> source) {
        if (source == null || source.isEmpty()) {
            return new Bounded<>(Set.of(), false);
        }
        LinkedHashSet<UUID> values = new LinkedHashSet<>();
        Iterator<UUID> iterator = source.iterator();
        int inspected = 0;
        while (iterator.hasNext() && inspected++ <= MAX_ENTRIES) {
            UUID value = iterator.next();
            if (value != null) values.add(value);
        }
        return new Bounded<>(Set.copyOf(values), iterator.hasNext()
                || values.size() > MAX_ENTRIES);
    }

    @Nonnull
    private static <T> Bounded<T> merge(
            @Nonnull Set<T> first,
            @Nonnull Set<T> second
    ) {
        LinkedHashSet<T> values = new LinkedHashSet<>();
        for (T value : first) {
            values.add(value);
            if (values.size() > MAX_ENTRIES) {
                return new Bounded<>(Set.of(), true);
            }
        }
        for (T value : second) {
            values.add(value);
            if (values.size() > MAX_ENTRIES) {
                return new Bounded<>(Set.of(), true);
            }
        }
        return new Bounded<>(immutable(values), false);
    }

    @Nonnull
    private static <T> Set<T> immutable(@Nonnull LinkedHashSet<T> values) {
        return values.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private record Bounded<T>(@Nonnull Set<T> values, boolean overflow) {
    }
}
