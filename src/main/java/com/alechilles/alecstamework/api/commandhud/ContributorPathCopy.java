package com.alechilles.alecstamework.api.commandhud;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached contributor paths and overflow scopes shared by both HUD change sets. */
record ContributorPathCopy(
        @Nonnull Map<CommandHudContributorId, Set<String>> paths,
        @Nonnull Set<CommandHudContributorId> fullRefreshes
) {
    @Nonnull
    static ContributorPathCopy copyOf(
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
}
