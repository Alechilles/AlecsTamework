package com.alechilles.alecstamework.api.commandhud;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable hotswap refresh hint for control slots and group status. */
public final class CommandHotswapHudChangeSet {
    /** The fixed hotswap controls that can change independently. */
    public enum Slot {
        PRIMARY,
        SECONDARY,
        Q,
        E,
        R
    }

    private final boolean fullRefresh;
    private final Set<Slot> changedSlots;
    private final boolean groupStatusChanged;
    private final Map<CommandHudContributorId, Set<String>> contributorPaths;

    /** Creates a hotswap change set without contributor path hints. */
    public CommandHotswapHudChangeSet(
            boolean fullRefresh,
            @Nullable Set<Slot> changedSlots,
            boolean groupStatusChanged
    ) {
        this(fullRefresh, changedSlots, groupStatusChanged, Map.of());
    }

    /** Creates a hotswap change set with detached contributor path hints. */
    public CommandHotswapHudChangeSet(
            boolean fullRefresh,
            @Nullable Set<Slot> changedSlots,
            boolean groupStatusChanged,
            @Nullable Map<CommandHudContributorId, Set<String>> contributorPaths
    ) {
        this.fullRefresh = fullRefresh;
        this.changedSlots = fullRefresh
                ? allSlots() : copySlots(changedSlots);
        this.groupStatusChanged = fullRefresh || groupStatusChanged;
        this.contributorPaths = copyContributorPaths(contributorPaths);
    }

    /** Creates a full hotswap refresh hint. */
    @Nonnull
    public static CommandHotswapHudChangeSet full() {
        return new CommandHotswapHudChangeSet(true, allSlots(), true, Map.of());
    }

    /** Creates a focused slot or group-status hint. */
    @Nonnull
    public static CommandHotswapHudChangeSet of(
            @Nullable Set<Slot> slots,
            boolean groupStatusChanged
    ) {
        return new CommandHotswapHudChangeSet(false, slots, groupStatusChanged, Map.of());
    }

    /** Creates a contributor-only hotswap refresh hint. */
    @Nonnull
    public static CommandHotswapHudChangeSet contributorPaths(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable Set<String> paths
    ) {
        return new CommandHotswapHudChangeSet(false, Set.of(), false,
                Map.of(contributorId, paths == null ? Set.of() : paths));
    }

    public boolean fullRefresh() {
        return fullRefresh;
    }

    @Nonnull
    public Set<Slot> changedSlots() {
        return changedSlots;
    }

    public boolean groupStatusChanged() {
        return groupStatusChanged;
    }

    @Nonnull
    public Map<CommandHudContributorId, Set<String>> contributorPaths() {
        return contributorPaths;
    }

    @Nonnull
    public Set<String> pathsFor(@Nonnull CommandHudContributorId contributorId) {
        return contributorPaths.getOrDefault(contributorId, Set.of());
    }

    public boolean changed(@Nonnull Slot slot) {
        return changedSlots.contains(slot);
    }

    @Nonnull
    private static Set<Slot> allSlots() {
        return Set.copyOf(EnumSet.allOf(Slot.class));
    }

    @Nonnull
    private static Set<Slot> copySlots(@Nullable Set<Slot> source) {
        if (source == null || source.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(EnumSet.copyOf(source));
    }

    @Nonnull
    private static Map<CommandHudContributorId, Set<String>> copyContributorPaths(
            @Nullable Map<CommandHudContributorId, Set<String>> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<CommandHudContributorId, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((id, paths) -> {
            if (id == null) return;
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if (paths != null) {
                for (String path : paths) {
                    String value = CommandHudDirtyScope.normalizePath(path);
                    if (value != null) normalized.add(value);
                    if (normalized.size() >= CommandHudDirtyScope.MAX_PATHS) break;
                }
            }
            copy.put(id, normalized.isEmpty()
                    ? Set.of() : Collections.unmodifiableSet(normalized));
        });
        return Map.copyOf(copy);
    }
}
