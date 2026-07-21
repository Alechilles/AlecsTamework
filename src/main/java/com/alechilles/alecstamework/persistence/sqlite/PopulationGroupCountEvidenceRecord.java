package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable count snapshot and delta reserved by one unified population operation. */
public record PopulationGroupCountEvidenceRecord(
        @Nonnull String operationId,
        @Nonnull UUID ownerUuid,
        @Nonnull String groupId,
        @Nonnull ScopeKind scopeKind,
        @Nullable String scopeWorldName,
        int committedOwnedBefore,
        int committedActiveBefore,
        int pendingOwnedBefore,
        int pendingActiveBefore,
        int ownedDelta,
        int activeDelta,
        int maxOwned,
        int maxActive,
        long policyRevision,
        @Nonnull State state,
        long createdAtMs,
        long updatedAtMs
) {
    public enum ScopeKind {
        GLOBAL,
        PER_WORLD
    }

    public enum State {
        RESERVED,
        APPLIED,
        RELEASED,
        QUARANTINED
    }

    public PopulationGroupCountEvidenceRecord {
        operationId = requireText(operationId, "operationId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        groupId = requireText(groupId, "groupId");
        scopeKind = Objects.requireNonNull(scopeKind, "scopeKind");
        scopeWorldName = normalize(scopeWorldName);
        state = Objects.requireNonNull(state, "state");
        if (scopeKind == ScopeKind.GLOBAL && scopeWorldName != null) {
            throw new IllegalArgumentException("GLOBAL evidence cannot carry a world.");
        }
        if (scopeKind == ScopeKind.PER_WORLD && scopeWorldName == null) {
            throw new IllegalArgumentException("PER_WORLD evidence requires a world.");
        }
        if (committedOwnedBefore < 0 || committedActiveBefore < 0
                || pendingOwnedBefore < 0 || pendingActiveBefore < 0
                || maxOwned < 0 || maxActive < 0 || policyRevision < 0L) {
            throw new IllegalArgumentException("Counts, limits, and revisions must be non-negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
