package com.alechilles.alecstamework.api.commandui;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable rendering hints for one snapshot transition.
 *
 * <p>A change set does not grant authority. Providers may ignore it and
 * compare the detached snapshots directly.</p>
 */
public final class CommandUiChangeSet {
    private final boolean fullRefresh;
    private final Set<CommandUiSection> changedSections;
    private final Set<UUID> changedCompanionIds;
    private final Set<UUID> removedCompanionIds;
    private final Set<CommandUiContributorId> changedContributorIds;
    private final Map<CommandUiContributorId, Set<String>> changedContributorPaths;
    private final Map<CommandUiContributorId, Set<UUID>> changedContributorRowIds;
    private final Map<CommandUiContributorId, Set<UUID>> removedContributorRowIds;

    public CommandUiChangeSet(
            boolean fullRefresh,
            @Nullable Set<CommandUiSection> changedSections,
            @Nullable Set<UUID> changedCompanionIds,
            @Nullable Set<UUID> removedCompanionIds
    ) {
        this(fullRefresh, changedSections, changedCompanionIds,
                removedCompanionIds, Set.of(), Map.of(), Map.of(), Map.of());
    }

    public CommandUiChangeSet(
            boolean fullRefresh,
            @Nullable Set<CommandUiSection> changedSections,
            @Nullable Set<UUID> changedCompanionIds,
            @Nullable Set<UUID> removedCompanionIds,
            @Nullable Set<CommandUiContributorId> changedContributorIds,
            @Nullable Map<CommandUiContributorId, Set<String>> changedContributorPaths,
            @Nullable Map<CommandUiContributorId, Set<UUID>> changedContributorRowIds,
            @Nullable Map<CommandUiContributorId, Set<UUID>> removedContributorRowIds
    ) {
        this.fullRefresh = fullRefresh;
        this.changedSections = copySections(changedSections, fullRefresh);
        this.changedCompanionIds = copyIds(changedCompanionIds);
        this.removedCompanionIds = copyIds(removedCompanionIds);
        this.changedContributorIds = copyContributorIds(changedContributorIds);
        this.changedContributorPaths = copyContributorPaths(changedContributorPaths);
        this.changedContributorRowIds = copyContributorRows(changedContributorRowIds);
        this.removedContributorRowIds = copyContributorRows(removedContributorRowIds);
    }

    public CommandUiChangeSet(
            boolean fullRefresh,
            @Nullable Set<CommandUiSection> changedSections,
            @Nullable Set<UUID> changedCompanionIds
    ) {
        this(fullRefresh, changedSections, changedCompanionIds, Set.of());
    }

    /** Creates a full refresh hint that marks every known section. */
    @Nonnull
    public static CommandUiChangeSet full() {
        return new CommandUiChangeSet(true, CommandUiSection.all(), Set.of(), Set.of());
    }

    /** Creates an empty, non-full hint. */
    @Nonnull
    public static CommandUiChangeSet empty() {
        return new CommandUiChangeSet(false, Set.of(), Set.of(), Set.of());
    }

    /** Creates a section-only hint. */
    @Nonnull
    public static CommandUiChangeSet sections(
            @Nonnull Set<CommandUiSection> sections
    ) {
        return new CommandUiChangeSet(false, sections, Set.of(), Set.of());
    }

    /** Creates a row-level hint. */
    @Nonnull
    public static CommandUiChangeSet rows(
            @Nonnull Set<UUID> changed,
            @Nonnull Set<UUID> removed
    ) {
        return new CommandUiChangeSet(false, Set.of(), changed, removed);
    }

    public boolean fullRefresh() {
        return fullRefresh;
    }

    @Nonnull
    public Set<CommandUiSection> changedSections() {
        return changedSections;
    }

    @Nonnull
    public Set<UUID> changedCompanionIds() {
        return changedCompanionIds;
    }

    @Nonnull
    public Set<UUID> removedCompanionIds() {
        return removedCompanionIds;
    }

    @Nonnull
    public Set<CommandUiContributorId> changedContributorIds() {
        return changedContributorIds;
    }

    @Nonnull
    public Map<CommandUiContributorId, Set<String>> changedContributorPaths() {
        return changedContributorPaths;
    }

    @Nonnull
    public Map<CommandUiContributorId, Set<UUID>> changedContributorRowIds() {
        return changedContributorRowIds;
    }

    @Nonnull
    public Map<CommandUiContributorId, Set<UUID>> removedContributorRowIds() {
        return removedContributorRowIds;
    }

    public boolean affects(@Nonnull CommandUiSection section) {
        return fullRefresh || changedSections.contains(
                Objects.requireNonNull(section, "section"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiChangeSet that)) return false;
        return fullRefresh == that.fullRefresh
                && changedSections.equals(that.changedSections)
                && changedCompanionIds.equals(that.changedCompanionIds)
                && removedCompanionIds.equals(that.removedCompanionIds)
                && changedContributorIds.equals(that.changedContributorIds)
                && changedContributorPaths.equals(that.changedContributorPaths)
                && changedContributorRowIds.equals(that.changedContributorRowIds)
                && removedContributorRowIds.equals(that.removedContributorRowIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullRefresh, changedSections, changedCompanionIds,
                removedCompanionIds, changedContributorIds,
                changedContributorPaths, changedContributorRowIds,
                removedContributorRowIds);
    }

    @Override
    public String toString() {
        return "CommandUiChangeSet[fullRefresh=" + fullRefresh
                + ", changedSections=" + changedSections
                + ", changedCompanionIds=" + changedCompanionIds
                + ", removedCompanionIds=" + removedCompanionIds
                + ", changedContributorIds=" + changedContributorIds
                + ", changedContributorPaths=" + changedContributorPaths
                + ", changedContributorRowIds=" + changedContributorRowIds
                + ", removedContributorRowIds=" + removedContributorRowIds + "]";
    }

    @Nonnull
    private static Set<CommandUiSection> copySections(
            @Nullable Set<CommandUiSection> source,
            boolean fullRefresh
    ) {
        if (fullRefresh) return CommandUiSection.all();
        if (source == null || source.isEmpty()) return Set.of();
        EnumSet<CommandUiSection> copy = EnumSet.noneOf(CommandUiSection.class);
        source.forEach(section -> {
            if (section != null) copy.add(section);
        });
        return copy.isEmpty() ? Set.of() : Set.copyOf(copy);
    }

    @Nonnull
    private static Set<UUID> copyIds(@Nullable Set<UUID> source) {
        if (source == null || source.isEmpty()) return Set.of();
        return source.stream().filter(Objects::nonNull).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Nonnull
    private static Set<CommandUiContributorId> copyContributorIds(
            @Nullable Set<CommandUiContributorId> source
    ) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<CommandUiContributorId> copy = new LinkedHashSet<>();
        source.forEach(id -> { if (id != null) copy.add(id); });
        return copy.isEmpty() ? Set.of() : Set.copyOf(copy);
    }

    @Nonnull
    private static Map<CommandUiContributorId, Set<String>> copyContributorPaths(
            @Nullable Map<CommandUiContributorId, Set<String>> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<CommandUiContributorId, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((id, paths) -> {
            if (id == null || paths == null) return;
            LinkedHashSet<String> values = new LinkedHashSet<>();
            paths.forEach(path -> {
                if (path != null && !path.isBlank()) values.add(path);
            });
            if (!values.isEmpty()) copy.put(id, Set.copyOf(values));
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    @Nonnull
    private static Map<CommandUiContributorId, Set<UUID>> copyContributorRows(
            @Nullable Map<CommandUiContributorId, Set<UUID>> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<CommandUiContributorId, Set<UUID>> copy = new LinkedHashMap<>();
        source.forEach((id, rows) -> {
            if (id == null || rows == null) return;
            LinkedHashSet<UUID> values = new LinkedHashSet<>();
            rows.forEach(row -> { if (row != null) values.add(row); });
            if (!values.isEmpty()) copy.put(id, Set.copyOf(values));
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}
