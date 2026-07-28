package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable, gameplay-neutral evidence observed before a live companion becomes dormant.
 *
 * <p>Callers may describe non-authoritative observations so the author can reject them
 * explicitly. Only a saved death component or one of the two destructive events is positive
 * evidence.</p>
 */
public record DormantCompanionObservation(
        @Nonnull String observationKey,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias sourceAlias,
        @Nonnull String sourceWorldKey,
        @Nonnull Evidence evidence,
        @Nonnull String receiptKey,
        long observedAtMs,
        @Nullable PositionObservation lastKnownPosition,
        @Nullable DeathObservation death,
        @Nullable LostObservation lost
) {
    public DormantCompanionObservation {
        observationKey = requireText(observationKey, "Dormant observation key");
        Objects.requireNonNull(profileId, "Dormant profile is required");
        Objects.requireNonNull(sourceAlias, "Dormant source alias is required");
        sourceWorldKey = requireText(sourceWorldKey, "Dormant source world");
        Objects.requireNonNull(evidence, "Dormant evidence is required");
        receiptKey = requireText(receiptKey, "Dormant receipt is required");
        boolean deathEvidence = evidence == Evidence.SAVED_DEATH_COMPONENT;
        boolean lostEvidence = evidence == Evidence.DESTRUCTIVE_REMOVAL
                || evidence == Evidence.WORLD_DELETION;
        if (deathEvidence != (death != null)
                || lostEvidence != (lost != null)) {
            throw new IllegalArgumentException(
                    "Authoritative dormant evidence requires matching final facts"
            );
        }
    }

    /** Returns whether this observation positively proves a dormant transition. */
    public boolean authoritative() {
        return switch (evidence) {
            case SAVED_DEATH_COMPONENT, DESTRUCTIVE_REMOVAL, WORLD_DELETION ->
                    true;
            case UNLOAD, ABSENCE, TIMEOUT -> false;
        };
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    /** Observations accepted or explicitly rejected by the positive-evidence boundary. */
    public enum Evidence {
        SAVED_DEATH_COMPONENT,
        DESTRUCTIVE_REMOVAL,
        WORLD_DELETION,
        UNLOAD,
        ABSENCE,
        TIMEOUT
    }

    /**
     * Immutable facts copied from a saved death component without depending on legacy services.
     */
    public record DeathObservation(
            long diedAtMs,
            long restorationAvailableAtMs,
            @Nonnull DeathSnapshotV2Payload.DeathCauseKind cause,
            @Nullable String sourceName
    ) {
        public DeathObservation {
            Objects.requireNonNull(cause, "Death cause is required");
            sourceName = sourceName == null || sourceName.isBlank()
                    ? null
                    : sourceName.trim();
        }
    }

    /** Immutable position copied before the live source can disappear. */
    public record PositionObservation(double x, double y, double z) {
        public PositionObservation {
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                throw new IllegalArgumentException(
                        "Finite dormant observation position is required"
                );
            }
        }
    }

    /** Immutable released lost-event facts copied from authoritative removal evidence. */
    public record LostObservation(
            long lastRelocationQueuedAtMs,
            int relocationRetryAttempts
    ) {
        public LostObservation {
            if (relocationRetryAttempts < 0) {
                throw new IllegalArgumentException(
                        "Lost relocation retry attempts cannot be negative"
                );
            }
        }
    }
}
