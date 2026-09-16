package com.alechilles.alecstamework.config.assets;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/** Shared pure lookup rules for role-scoped configuration families. */
final class TwConfigLookup {
    private TwConfigLookup() {
    }

    static <T> Map<String, T> buildRoleIndex(@Nullable Iterable<T> candidates,
                                             Predicate<T> enabled,
                                             Function<T, String[]> roleIds,
                                             ToIntFunction<T> priority,
                                             Function<T, String> id) {
        Map<String, T> selected = new HashMap<>();
        if (candidates == null) {
            return selected;
        }
        for (T candidate : candidates) {
            if (candidate == null || !enabled.test(candidate)) {
                continue;
            }
            String[] candidateRoleIds = roleIds.apply(candidate);
            if (candidateRoleIds == null) {
                continue;
            }
            for (String roleId : candidateRoleIds) {
                String normalizedRoleId = normalizeRoleId(roleId);
                if (normalizedRoleId.isEmpty()) {
                    continue;
                }
                T existing = selected.get(normalizedRoleId);
                if (prefers(candidate, existing, priority, id)) {
                    selected.put(normalizedRoleId, candidate);
                }
            }
        }
        return selected;
    }

    @Nullable
    static <T> T resolveById(@Nullable Map<String, T> candidates,
                             @Nullable String requestedId,
                             Function<T, String> id) {
        if (candidates == null || requestedId == null || requestedId.isBlank()) {
            return null;
        }
        T direct = candidates.get(requestedId);
        if (direct != null) {
            return direct;
        }
        String normalizedId = requestedId.trim();
        for (T candidate : candidates.values()) {
            if (candidate != null && normalizedId.equalsIgnoreCase(id.apply(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    static <T> boolean prefers(@Nullable T candidate,
                               @Nullable T existing,
                               ToIntFunction<T> priority,
                               Function<T, String> id) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        int candidatePriority = priority.applyAsInt(candidate);
        int existingPriority = priority.applyAsInt(existing);
        return candidatePriority != existingPriority
                ? candidatePriority > existingPriority
                : compareIds(id.apply(candidate), id.apply(existing)) < 0;
    }

    static String normalizeRoleId(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }
}
