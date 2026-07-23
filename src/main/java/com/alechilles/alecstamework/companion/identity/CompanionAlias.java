package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable runtime UUID mapping for a stable companion profile.
 *
 * @param alias runtime NPC UUID
 * @param profileId stable profile
 * @param generation monotonically increasing profile-local alias generation
 * @param state lease/current/history state
 * @param leaseOperationId operation that created the lease, when applicable
 * @param mappedAtMs signed persisted mapping time
 * @param retiredAtMs signed persisted retirement time, or null while active
 */
public record CompanionAlias(@Nonnull NpcAlias alias,
                             @Nonnull ProfileId profileId,
                             long generation,
                             @Nonnull State state,
                             @Nullable OperationId leaseOperationId,
                             long mappedAtMs,
                             @Nullable Long retiredAtMs) {
    public CompanionAlias {
        if (alias == null || profileId == null || state == null) {
            throw new IllegalArgumentException("Alias identity, profile, and state are required");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("Alias generation cannot be negative");
        }
        if (state == State.LEASED && leaseOperationId == null) {
            throw new IllegalArgumentException("Leased aliases require an operation fence");
        }
        if (state != State.RETIRED && retiredAtMs != null) {
            throw new IllegalArgumentException("Only retired aliases carry retirement time");
        }
        if (state == State.RETIRED && retiredAtMs == null) {
            throw new IllegalArgumentException("Retired aliases require retirement time");
        }
    }

    /** Sole durable alias state vocabulary. */
    public enum State {
        LEASED,
        CURRENT,
        RETIRED
    }
}
