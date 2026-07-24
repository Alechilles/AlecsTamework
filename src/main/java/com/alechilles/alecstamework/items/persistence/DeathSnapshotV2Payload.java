package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable modern death envelope containing complete spawn state and released death facts.
 *
 * <p>The full state is retained as canonical JSON instead of holding mutable ECS component
 * objects. Callers receive a fresh decoded state for each access, so later runtime mutation
 * cannot alter the durable death artifact.</p>
 */
public record DeathSnapshotV2Payload(
        @Nonnull String fullStateJson,
        long diedAtMs,
        long respawnAvailableAtMs,
        @Nonnull DeathCauseKind deathCauseKind,
        @Nullable String deathSourceName
) {
    private static final CoopResidentStateSnapshotCodec FULL_STATE_CODEC =
            new CoopResidentStateSnapshotCodec();

    public DeathSnapshotV2Payload {
        fullStateJson = canonicalFullState(fullStateJson);
        if (deathCauseKind == null) {
            throw new IllegalArgumentException("Death cause is required");
        }
        deathSourceName = absentWhenBlank(deathSourceName);
    }

    /** Freezes one complete runtime state into the modern death envelope. */
    @Nonnull
    public static DeathSnapshotV2Payload capture(
            @Nonnull CoopResidentStateSnapshot fullState,
            long diedAtMs,
            long respawnAvailableAtMs,
            @Nonnull DeathCauseKind deathCauseKind,
            @Nullable String deathSourceName
    ) {
        if (fullState == null) {
            throw new IllegalArgumentException("Death full state is required");
        }
        return new DeathSnapshotV2Payload(
                FULL_STATE_CODEC.encode(fullState),
                diedAtMs,
                respawnAvailableAtMs,
                deathCauseKind,
                deathSourceName
        );
    }

    /** Returns a fresh full-state value so the envelope remains immutable. */
    @Nonnull
    public CoopResidentStateSnapshot fullState() {
        CoopResidentStateSnapshotCodec.DecodeResult result =
                FULL_STATE_CODEC.decode(fullStateJson);
        if (result.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || result.snapshot() == null) {
            throw new IllegalStateException("Canonical death full state is unreadable");
        }
        return result.snapshot();
    }

    private static String canonicalFullState(String payloadJson) {
        CoopResidentStateSnapshotCodec.DecodeResult result =
                FULL_STATE_CODEC.decode(payloadJson);
        if (result.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || result.snapshot() == null) {
            throw new IllegalArgumentException(
                    "Death full state is invalid: "
                            + (result.failure() == null
                            ? result.status().name()
                            : result.failure().name())
            );
        }
        return FULL_STATE_CODEC.encode(result.snapshot());
    }

    @Nullable
    private static String absentWhenBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Stable death-cause identifiers retained from the released payload. */
    public enum DeathCauseKind {
        STARVATION,
        DEHYDRATION,
        STARVATION_AND_DEHYDRATION,
        PLAYER,
        NPC,
        ENVIRONMENT,
        UNKNOWN
    }
}
