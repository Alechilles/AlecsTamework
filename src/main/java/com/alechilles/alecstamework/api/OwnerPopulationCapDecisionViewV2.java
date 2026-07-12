package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Context-complete owner-cap preflight result.
 *
 * <p>Evaluation is informational. A caller must use {@link PopulationAdmissionApi} to bind a
 * mutation to reserved capacity. Unknown counts use {@link #UNKNOWN_COUNT}, never a misleading
 * zero.
 */
public record OwnerPopulationCapDecisionViewV2(@Nonnull UUID ownerUuid,
                                               @Nullable String worldName,
                                               int requestedSlots,
                                               boolean allowed,
                                               boolean capEnabled,
                                               boolean authoritative,
                                               int limit,
                                               long committedCount,
                                               long pendingCount,
                                               long remainingHeadroom,
                                               @Nonnull Scope scope,
                                               @Nonnull Readiness readiness,
                                               @Nonnull String reason) {
    public static final long UNKNOWN_COUNT = -1L;

    public OwnerPopulationCapDecisionViewV2 {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        worldName = OwnerPopulationCapRequestV2.normalizeWorldName(worldName);
        scope = Objects.requireNonNull(scope, "scope");
        readiness = Objects.requireNonNull(readiness, "readiness");
        reason = Objects.requireNonNull(reason, "reason");
        if (requestedSlots <= 0) {
            throw new IllegalArgumentException("Requested owner population slots must be positive.");
        }
        if (limit < 0 || remainingHeadroom < 0L) {
            throw new IllegalArgumentException("Limit and remaining headroom cannot be negative.");
        }
        requireKnownOrUnknown("committedCount", committedCount);
        requireKnownOrUnknown("pendingCount", pendingCount);
        if (authoritative && (committedCount == UNKNOWN_COUNT || pendingCount == UNKNOWN_COUNT)) {
            throw new IllegalArgumentException("Authoritative owner population decisions require known counts.");
        }
        if (allowed && capEnabled && !authoritative) {
            throw new IllegalArgumentException("A capped allow decision must be authoritative.");
        }
        if (scope == Scope.PER_WORLD && worldName == null && allowed && capEnabled) {
            throw new IllegalArgumentException("A per-world capped allow decision requires world context.");
        }
    }

    @Nonnull
    public static OwnerPopulationCapDecisionViewV2 unavailable(@Nonnull OwnerPopulationCapRequestV2 request,
                                                               @Nonnull Scope scope,
                                                               @Nonnull String reason) {
        Objects.requireNonNull(request, "request");
        return new OwnerPopulationCapDecisionViewV2(
                request.ownerUuid(),
                request.worldName(),
                request.requestedSlots(),
                false,
                true,
                false,
                0,
                UNKNOWN_COUNT,
                UNKNOWN_COUNT,
                0L,
                scope,
                Readiness.UNAVAILABLE,
                reason
        );
    }

    private static void requireKnownOrUnknown(String field, long value) {
        if (value < 0L && value != UNKNOWN_COUNT) {
            throw new IllegalArgumentException(field + " must be non-negative or UNKNOWN_COUNT.");
        }
    }

    public enum Scope {
        UNKNOWN,
        GLOBAL,
        PER_WORLD
    }

    public enum Readiness {
        LOADING,
        RECONCILING,
        READY,
        DEGRADED,
        UNAVAILABLE
    }
}
