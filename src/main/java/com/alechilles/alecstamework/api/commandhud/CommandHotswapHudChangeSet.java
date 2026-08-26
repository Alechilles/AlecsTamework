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
    private final Set<CommandHudContributorId> fullRefreshContributors;

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
        this(fullRefresh, changedSlots, groupStatusChanged, contributorPaths, Set.of());
    }

    /** Creates a change set with explicit contributor path and full-refresh scopes. */
    public CommandHotswapHudChangeSet(
            boolean fullRefresh,
            @Nullable Set<Slot> changedSlots,
            boolean groupStatusChanged,
            @Nullable Map<CommandHudContributorId, Set<String>> contributorPaths,
            @Nullable Set<CommandHudContributorId> fullRefreshContributors
    ) {
        this.fullRefresh = fullRefresh;
        this.changedSlots = fullRefresh
                ? allSlots() : copySlots(changedSlots);
        this.groupStatusChanged = fullRefresh || groupStatusChanged;
        ContributorPathCopy copied = copyContributorPaths(contributorPaths);
        LinkedHashSet<CommandHudContributorId> fullRefreshIds =
                new LinkedHashSet<>(copied.fullRefreshes());
        if (fullRefreshContributors != null) {
            fullRefreshContributors.forEach(id -> {
                if (id != null) fullRefreshIds.add(id);
            });
        }
        this.contributorPaths = copied.paths();
        this.fullRefreshContributors = Set.copyOf(fullRefreshIds);
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

    /** Creates an explicit full refresh hint for one contributor. */
    @Nonnull
    public static CommandHotswapHudChangeSet fullContributor(
            @Nonnull CommandHudContributorId contributorId
    ) {
        return contributorScopes(Map.of(), Set.of(contributorId));
    }

    /** Creates contributor scopes without encoding overflow as path values. */
    @Nonnull
    public static CommandHotswapHudChangeSet contributorScopes(
            @Nullable Map<CommandHudContributorId, Set<String>> contributorPaths,
            @Nullable Set<CommandHudContributorId> fullRefreshContributors
    ) {
        return new CommandHotswapHudChangeSet(false, Set.of(), false,
                contributorPaths, fullRefreshContributors);
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

    /** Returns contributors whose local scope requires a complete refresh. */
    @Nonnull
    public Set<CommandHudContributorId> fullRefreshContributors() {
        return fullRefreshContributors;
    }

    /** Returns whether this contributor must be recomposed completely. */
    public boolean contributorFullRefresh(@Nonnull CommandHudContributorId contributorId) {
        return fullRefresh || fullRefreshContributors.contains(contributorId);
    }

    @Nonnull
    public Set<String> pathsFor(@Nonnull CommandHudContributorId contributorId) {
        return contributorPaths.getOrDefault(contributorId, Set.of());
    }

    /** Returns the bounded scope for one contributor, including overflow state. */
    @Nonnull
    public CommandHudDirtyScope scopeFor(@Nonnull CommandHudContributorId contributorId) {
        return contributorFullRefresh(contributorId)
                ? CommandHudDirtyScope.full()
                : CommandHudDirtyScope.paths(pathsFor(contributorId));
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
    private static ContributorPathCopy copyContributorPaths(
            @Nullable Map<CommandHudContributorId, Set<String>> source
    ) {
        if (source == null || source.isEmpty()) {
            return new ContributorPathCopy(Map.of(), Set.of());
        }
        LinkedHashMap<CommandHudContributorId, Set<String>> copy = new LinkedHashMap<>();
        LinkedHashSet<CommandHudContributorId> fullRefreshes = new LinkedHashSet<>();
        source.forEach((id, paths) -> {
            if (id == null) return;
            PathCopy pathCopy = copyPaths(paths);
            copy.put(id, pathCopy.paths());
            if (pathCopy.fullRefresh()) fullRefreshes.add(id);
        });
        return new ContributorPathCopy(Map.copyOf(copy), Set.copyOf(fullRefreshes));
    }

    @Nonnull
    private static PathCopy copyPaths(@Nullable Set<String> source) {
        if (source == null || source.isEmpty()) return new PathCopy(Set.of(), false);
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : source) {
            String value = CommandHudDirtyScope.normalizePath(path);
            if (value == null) continue;
            normalized.add(value);
            if (normalized.size() > CommandHudDirtyScope.MAX_PATHS) {
                return new PathCopy(Set.of(), true);
            }
        }
        Set<String> immutable = normalized.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(normalized);
        return new PathCopy(immutable, false);
    }

    private record PathCopy(@Nonnull Set<String> paths, boolean fullRefresh) {
    }

    private record ContributorPathCopy(
            @Nonnull Map<CommandHudContributorId, Set<String>> paths,
            @Nonnull Set<CommandHudContributorId> fullRefreshes
    ) {
    }
}
