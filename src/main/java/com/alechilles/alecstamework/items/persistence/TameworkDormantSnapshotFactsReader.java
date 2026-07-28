package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads the small released policy facts from one death or lost snapshot.
 *
 * <p>The reader is pure and preserves signed timestamps. It does not decide whether a deadline
 * has elapsed; callers compare their own clock with {@link Facts#restorationAvailableAtMs()} and
 * treat zero as the explicit unset sentinel.</p>
 */
public final class TameworkDormantSnapshotFactsReader {
    private final SnapshotCodecRegistry codecs;

    public TameworkDormantSnapshotFactsReader() {
        this(TameworkSnapshotCodecs.create());
    }

    public TameworkDormantSnapshotFactsReader(
            @Nonnull SnapshotCodecRegistry codecs
    ) {
        this.codecs = Objects.requireNonNull(
                codecs,
                "Snapshot codecs are required"
        );
    }

    /** Returns policy facts or one stable failure without consulting runtime state. */
    @Nonnull
    public ReadResult read(@Nonnull CompanionSnapshot snapshot) {
        if (snapshot == null) {
            return ReadResult.failed(Failure.INVALID_REQUEST);
        }
        if (snapshot.kind().equals(TameworkSnapshotCodecs.DEATH)) {
            return readDeath(snapshot);
        }
        if (snapshot.kind().equals(TameworkSnapshotCodecs.LOST)) {
            return readLost(snapshot);
        }
        return ReadResult.failed(Failure.NOT_DORMANT);
    }

    private ReadResult readDeath(CompanionSnapshot snapshot) {
        if (snapshot.payloadVersion() == 1) {
            SnapshotDecodeResult<LegacyDeathV1Payload> result = codecs.decode(
                    snapshot,
                    LegacyDeathV1Payload.class
            );
            if (result instanceof SnapshotDecodeResult.Decoded<
                    LegacyDeathV1Payload> decoded) {
                LegacyDeathV1Payload value = decoded.value();
                return ReadResult.found(new Facts(
                        LifecycleState.DEAD_REVIVABLE,
                        value.diedAtMs(),
                        value.respawnAvailableAtMs(),
                        enumName(value.deathCauseKind()),
                        value.deathSourceName()
                ));
            }
            return decodeFailed();
        }
        if (snapshot.payloadVersion() == 2) {
            SnapshotDecodeResult<DeathSnapshotV2Payload> result = codecs.decode(
                    snapshot,
                    DeathSnapshotV2Payload.class
            );
            if (result instanceof SnapshotDecodeResult.Decoded<
                    DeathSnapshotV2Payload> decoded) {
                DeathSnapshotV2Payload value = decoded.value();
                return ReadResult.found(new Facts(
                        LifecycleState.DEAD_REVIVABLE,
                        value.diedAtMs(),
                        value.respawnAvailableAtMs(),
                        value.deathCauseKind().name(),
                        value.deathSourceName()
                ));
            }
            return decodeFailed();
        }
        return ReadResult.failed(Failure.UNSUPPORTED_VERSION);
    }

    private ReadResult readLost(CompanionSnapshot snapshot) {
        if (snapshot.payloadVersion() == 1) {
            SnapshotDecodeResult<LegacyLostV1Payload> result = codecs.decode(
                    snapshot,
                    LegacyLostV1Payload.class
            );
            if (result instanceof SnapshotDecodeResult.Decoded<
                    LegacyLostV1Payload> decoded) {
                return lostFacts(decoded.value().lostAtMs());
            }
            return decodeFailed();
        }
        if (snapshot.payloadVersion() == 2) {
            SnapshotDecodeResult<CoopResidentStateSnapshot> result =
                    codecs.decode(
                    snapshot,
                    CoopResidentStateSnapshot.class
            );
            return result instanceof SnapshotDecodeResult.Decoded<
                    CoopResidentStateSnapshot>
                    ? lostFacts(snapshot.createdAtMs())
                    : decodeFailed();
        }
        return ReadResult.failed(Failure.UNSUPPORTED_VERSION);
    }

    private ReadResult lostFacts(long observedAtMs) {
        return ReadResult.found(new Facts(
                LifecycleState.LOST,
                observedAtMs,
                0L,
                null,
                null
        ));
    }

    private ReadResult decodeFailed() {
        return ReadResult.failed(Failure.DECODE_FAILED);
    }

    @Nullable
    private String enumName(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** Immutable facts shared by command status and restoration admission. */
    public record Facts(
            @Nonnull LifecycleState state,
            long observedAtMs,
            long restorationAvailableAtMs,
            @Nullable String deathCauseKind,
            @Nullable String deathSourceName
    ) {
        public Facts {
            Objects.requireNonNull(state, "Dormant lifecycle state is required");
            if (state != LifecycleState.DEAD_REVIVABLE
                    && state != LifecycleState.LOST) {
                throw new IllegalArgumentException(
                        "Dormant snapshot facts require death or lost state"
                );
            }
            deathCauseKind = absentWhenBlank(deathCauseKind);
            deathSourceName = absentWhenBlank(deathSourceName);
            if (state == LifecycleState.LOST
                    && (deathCauseKind != null || deathSourceName != null)) {
                throw new IllegalArgumentException(
                        "Lost snapshot facts cannot contain death details"
                );
            }
        }

        @Nullable
        private static String absentWhenBlank(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** Stable read failures; none are interpreted as companion absence. */
    public enum Failure {
        INVALID_REQUEST,
        NOT_DORMANT,
        UNSUPPORTED_VERSION,
        DECODE_FAILED
    }

    /** Exactly one facts value or one failure. */
    public record ReadResult(
            @Nullable Facts facts,
            @Nullable Failure failure
    ) {
        public ReadResult {
            if ((facts == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "Dormant facts read requires exactly one result"
                );
            }
        }

        @Nonnull
        static ReadResult found(@Nonnull Facts facts) {
            return new ReadResult(
                    Objects.requireNonNull(facts, "Dormant facts are required"),
                    null
            );
        }

        @Nonnull
        static ReadResult failed(@Nonnull Failure failure) {
            return new ReadResult(
                    null,
                    Objects.requireNonNull(failure, "Failure is required")
            );
        }

        /** Returns whether policy facts were decoded successfully. */
        public boolean successful() {
            return facts != null;
        }
    }
}
