package com.alechilles.alecstamework.api.commandhud;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable target-HUD refresh hint with bounded contributor-local paths. */
public final class CommandTargetHudChangeSet {
    /** Independently refreshable regions of the target snapshot. */
    public enum Section {
        IDENTITY,
        VITALS,
        COOLDOWNS,
        FOOD,
        ATTACHMENTS,
        TAME_REQUIREMENTS,
        PROGRESSION,
        TRAITS,
        OWNER,
        CONTRIBUTIONS;

        /** Returns every target section. */
        @Nonnull
        public static Set<Section> all() {
            return Set.copyOf(EnumSet.allOf(Section.class));
        }
    }

    private final boolean fullRefresh;
    private final Set<Section> changedSections;
    private final Map<CommandHudContributorId, Set<String>> contributorPaths;

    /** Creates a change set with no contributor path hints. */
    public CommandTargetHudChangeSet(
            boolean fullRefresh,
            @Nullable Set<Section> changedSections
    ) {
        this(fullRefresh, changedSections, Map.of());
    }

    /** Creates a change set with detached contributor path hints. */
    public CommandTargetHudChangeSet(
            boolean fullRefresh,
            @Nullable Set<Section> changedSections,
            @Nullable Map<CommandHudContributorId, Set<String>> contributorPaths
    ) {
        this.fullRefresh = fullRefresh;
        this.changedSections = fullRefresh
                ? Section.all() : copySections(changedSections);
        this.contributorPaths = copyContributorPaths(contributorPaths);
    }

    /** Creates a full target refresh hint. */
    @Nonnull
    public static CommandTargetHudChangeSet full() {
        return new CommandTargetHudChangeSet(true, Section.all(), Map.of());
    }

    /** Creates a focused target section hint. */
    @Nonnull
    public static CommandTargetHudChangeSet of(@Nullable Set<Section> sections) {
        return new CommandTargetHudChangeSet(false, sections, Map.of());
    }

    /** Creates a contributor-only target refresh hint. */
    @Nonnull
    public static CommandTargetHudChangeSet contributorPaths(
            @Nonnull CommandHudContributorId contributorId,
            @Nullable Set<String> paths
    ) {
        return new CommandTargetHudChangeSet(false, Set.of(Section.CONTRIBUTIONS),
                Map.of(contributorId, paths == null ? Set.of() : paths));
    }

    public boolean fullRefresh() {
        return fullRefresh;
    }

    @Nonnull
    public Set<Section> changedSections() {
        return changedSections;
    }

    /** Alias for integrations that call changed regions simply sections. */
    @Nonnull
    public Set<Section> sections() {
        return changedSections;
    }

    @Nonnull
    public Map<CommandHudContributorId, Set<String>> contributorPaths() {
        return contributorPaths;
    }

    @Nonnull
    public Set<String> pathsFor(@Nonnull CommandHudContributorId contributorId) {
        return contributorPaths.getOrDefault(contributorId, Set.of());
    }

    public boolean changed(@Nonnull Section section) {
        return changedSections.contains(section);
    }

    @Nonnull
    private static Set<Section> copySections(@Nullable Set<Section> source) {
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
