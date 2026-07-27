package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves durable capture completion before mutable roster policy is read.
 *
 * <p>The identity lookup is only a positive-evidence routing hint. The author
 * still requires {@link #probeExact(BondedCompanionCaptureIntent)} to match the
 * complete immutable request before it bypasses current policy.</p>
 */
public interface BondedCompanionCaptureReplayGateway {
    /** Finds positive durable evidence for one exact live retry identity. */
    @Nonnull LookupResult lookup(@Nonnull Request request);

    /** Probes the complete request hash without creating or claiming an operation. */
    @Nonnull ExactResult probeExact(@Nonnull BondedCompanionCaptureIntent intent);

    /** Returns a gateway that never claims durable replay evidence. */
    static BondedCompanionCaptureReplayGateway unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Stable evidence available before current family resolution or chance sampling. */
    record Request(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey,
            @Nonnull UUID actorUuid,
            @Nonnull String rosterId,
            @Nonnull UUID sourceNpcUuid,
            @Nonnull String sourceWorldKey,
            @Nonnull String sourceItemId,
            @Nonnull String roleId
    ) {
        public Request {
            callerNamespace = text(callerNamespace, "callerNamespace");
            idempotencyKey = text(idempotencyKey, "idempotencyKey");
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            rosterId = text(rosterId, "rosterId");
            sourceNpcUuid = Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            sourceWorldKey = text(sourceWorldKey, "sourceWorldKey");
            sourceItemId = text(sourceItemId, "sourceItemId");
            roleId = text(roleId, "roleId");
        }
    }

    /** Frozen committed fields used to rebuild the exact request without rerolling. */
    record Evidence(
            @Nonnull BondedCompanionCaptureAttemptEvidence attemptEvidence,
            @Nonnull String roleId,
            @Nonnull String familyId,
            @Nonnull String sourceWorldKey,
            @Nonnull BondedCompanionSnapshot snapshot
    ) {
        public Evidence {
            attemptEvidence = Objects.requireNonNull(
                    attemptEvidence, "attemptEvidence");
            roleId = text(roleId, "roleId");
            familyId = text(familyId, "familyId");
            sourceWorldKey = text(sourceWorldKey, "sourceWorldKey");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    enum LookupStatus { ABSENT, MATCHED, CONFLICT, FAILED }
    enum ExactStatus { ABSENT, REPLAYED, CONFLICT, FAILED }

    record LookupResult(
            @Nonnull LookupStatus status,
            @Nullable Evidence evidence
    ) {
        public LookupResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == LookupStatus.MATCHED) != (evidence != null)) {
                throw new IllegalArgumentException("invalid replay lookup result");
            }
        }

        public static LookupResult absent() {
            return new LookupResult(LookupStatus.ABSENT, null);
        }

        public static LookupResult matched(Evidence evidence) {
            return new LookupResult(LookupStatus.MATCHED, evidence);
        }

        public static LookupResult conflict() {
            return new LookupResult(LookupStatus.CONFLICT, null);
        }

        public static LookupResult failed() {
            return new LookupResult(LookupStatus.FAILED, null);
        }
    }

    record ExactResult(@Nonnull ExactStatus status) {
        public ExactResult {
            status = Objects.requireNonNull(status, "status");
        }

        public static ExactResult absent() {
            return new ExactResult(ExactStatus.ABSENT);
        }

        public static ExactResult replayed() {
            return new ExactResult(ExactStatus.REPLAYED);
        }

        public static ExactResult conflict() {
            return new ExactResult(ExactStatus.CONFLICT);
        }

        public static ExactResult failed() {
            return new ExactResult(ExactStatus.FAILED);
        }
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    final class UnavailableHolder {
        private static final BondedCompanionCaptureReplayGateway INSTANCE =
                new BondedCompanionCaptureReplayGateway() {
                    @Override public LookupResult lookup(Request request) {
                        return LookupResult.absent();
                    }

                    @Override public ExactResult probeExact(
                            BondedCompanionCaptureIntent intent) {
                        return ExactResult.absent();
                    }
                };

        private UnavailableHolder() {}
    }
}
