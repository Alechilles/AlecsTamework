package com.alechilles.alecstamework.api.commandui;

import java.util.EnumSet;
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

    public CommandUiChangeSet(
            boolean fullRefresh,
            @Nullable Set<CommandUiSection> changedSections,
            @Nullable Set<UUID> changedCompanionIds,
            @Nullable Set<UUID> removedCompanionIds
    ) {
        this.fullRefresh = fullRefresh;
        this.changedSections = copySections(changedSections, fullRefresh);
        this.changedCompanionIds = copyIds(changedCompanionIds);
        this.removedCompanionIds = copyIds(removedCompanionIds);
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

    public boolean isFullRefresh() {
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

    /** Alias for providers that call a companion row a presentation row. */
    @Nonnull
    public Set<UUID> changedRowIds() {
        return changedCompanionIds;
    }

    @Nonnull
    public Set<UUID> removedCompanionIds() {
        return removedCompanionIds;
    }

    @Nonnull
    public Set<UUID> removedRowIds() {
        return removedCompanionIds;
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
                && removedCompanionIds.equals(that.removedCompanionIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullRefresh, changedSections, changedCompanionIds,
                removedCompanionIds);
    }

    @Override
    public String toString() {
        return "CommandUiChangeSet[fullRefresh=" + fullRefresh
                + ", changedSections=" + changedSections
                + ", changedCompanionIds=" + changedCompanionIds
                + ", removedCompanionIds=" + removedCompanionIds + "]";
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
}
