package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves released and complete dormant snapshots into one immutable full-state spawn artifact.
 *
 * <p>Resolution is pure: it uses only persisted profile/snapshot evidence and never consults a
 * clock, runtime config, live entity, or world. Legacy payloads are upgraded once before operation
 * submission; replay subsequently needs only the returned projection.</p>
 */
public final class TameworkRestorationSnapshotResolver {
    private static final int COMPLETE_STATE_VERSION = 2;

    private final SnapshotCodecRegistry codecs;
    private final TameworkRestorationEvidenceResolver evidence;

    public TameworkRestorationSnapshotResolver() {
        this(TameworkSnapshotCodecs.create());
    }

    public TameworkRestorationSnapshotResolver(
            @Nonnull SnapshotCodecRegistry codecs
    ) {
        if (codecs == null) {
            throw new IllegalArgumentException("Snapshot codecs are required");
        }
        this.codecs = codecs;
        this.evidence = new TameworkRestorationEvidenceResolver();
    }

    /** Resolves exactly one current death or lost source into a crash-safe projection. */
    @Nonnull
    public Resolution resolve(
            @Nonnull CompanionProfileReadModel profile,
            @Nonnull CompanionSnapshot source
    ) {
        if (profile == null || source == null) {
            throw new IllegalArgumentException(
                    "Profile and source snapshot are required"
            );
        }
        Resolution contextFailure = validateContext(profile, source);
        if (contextFailure != null) {
            return contextFailure;
        }
        try {
            return resolveKnown(profile, source);
        } catch (TameworkRestorationEvidenceResolver.MissingRole missing) {
            return failed(
                    Failure.ROLE_MISSING,
                    "restoration_role_missing",
                    "roleId",
                    null
            );
        } catch (TameworkRestorationEvidenceResolver.EvidenceConflict conflict) {
            return failed(
                    Failure.EVIDENCE_CONFLICT,
                    "restoration_evidence_conflict",
                    conflict.field(),
                    conflict.getCause()
            );
        } catch (LegacyRestorationEvidence.EvidenceException invalid) {
            return failed(
                    Failure.DECODE_FAILED,
                    "restoration_legacy_evidence_invalid",
                    invalid.field(),
                    invalid
            );
        } catch (RuntimeException failure) {
            return failed(
                    Failure.CANONICAL_ENCODE_FAILED,
                    "restoration_projection_encode_failed",
                    null,
                    failure
            );
        }
    }

    @Nullable
    private Resolution validateContext(
            CompanionProfileReadModel profile,
            CompanionSnapshot source
    ) {
        if (!profile.identity().profileId().equals(source.profileId())
                || !source.current()
                || !profile.currentSnapshots().contains(source)) {
            return failed(
                    Failure.CONTEXT_MISMATCH,
                    "restoration_snapshot_context_mismatch",
                    "sourceSnapshot",
                    null
            );
        }
        LifecycleState expected = expectedState(source.kind());
        if (expected == null
                || profile.lifecycle().state() != expected
                || !profile.lifecycle().location().equals(
                        LifecycleLocation.none()
                )
                || profile.lifecycle().activeOperationId() != null
                || profile.lifecycle().quarantined()) {
            return failed(
                    Failure.CONTEXT_MISMATCH,
                    "restoration_lifecycle_context_mismatch",
                    "lifecycle",
                    null
            );
        }
        return null;
    }

    private Resolution resolveKnown(
            CompanionProfileReadModel profile,
            CompanionSnapshot source
    ) {
        if (source.payloadVersion() == COMPLETE_STATE_VERSION) {
            return resolveComplete(profile, source);
        }
        if (source.payloadVersion() != 1) {
            return failed(
                    Failure.UNSUPPORTED_CODEC,
                    "restoration_snapshot_codec_unsupported",
                    "payloadVersion",
                    null
            );
        }
        if (source.kind().equals(TameworkSnapshotCodecs.DEATH)) {
            return resolveLegacyDeath(
                    profile, source, evidence.legacySourceAlias(profile)
            );
        }
        if (source.kind().equals(TameworkSnapshotCodecs.LOST)) {
            return resolveLegacyLost(
                    profile, source, evidence.legacySourceAlias(profile)
            );
        }
        return failed(
                Failure.UNSUPPORTED_CODEC,
                "restoration_snapshot_codec_unsupported",
                "kind",
                null
        );
    }

    private Resolution resolveComplete(
            CompanionProfileReadModel profile,
            CompanionSnapshot source
    ) {
        if (source.kind().equals(TameworkSnapshotCodecs.DEATH)) {
            return resolveCompleteDeath(profile, source);
        }
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                codecs.decode(source, CoopResidentStateSnapshot.class);
        if (decoded instanceof SnapshotDecodeResult.Failed<
                CoopResidentStateSnapshot> failed) {
            return decodeFailure(failed);
        }
        CoopResidentStateSnapshot state = ((SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot>) decoded).value();
        NpcAlias sourceAlias = evidence.modernSourceAlias(profile, state);
        evidence.validateComplete(profile, sourceAlias, state);
        return encodeProjection(sourceAlias, state);
    }

    private Resolution resolveCompleteDeath(
            CompanionProfileReadModel profile,
            CompanionSnapshot source
    ) {
        SnapshotDecodeResult<DeathSnapshotV2Payload> decoded =
                codecs.decode(source, DeathSnapshotV2Payload.class);
        if (decoded instanceof SnapshotDecodeResult.Failed<
                DeathSnapshotV2Payload> failed) {
            return decodeFailure(failed);
        }
        DeathSnapshotV2Payload death =
                ((SnapshotDecodeResult.Decoded<DeathSnapshotV2Payload>) decoded)
                        .value();
        CoopResidentStateSnapshot state = death.fullState();
        NpcAlias sourceAlias = evidence.modernSourceAlias(profile, state);
        evidence.validateComplete(profile, sourceAlias, state);
        return encodeProjection(sourceAlias, state);
    }

    private Resolution resolveLegacyDeath(
            CompanionProfileReadModel profile,
            CompanionSnapshot source,
            NpcAlias sourceAlias
    ) {
        SnapshotDecodeResult<LegacyDeathV1Payload> decoded =
                codecs.decode(source, LegacyDeathV1Payload.class);
        if (decoded instanceof SnapshotDecodeResult.Failed<
                LegacyDeathV1Payload> failed) {
            return decodeFailure(failed);
        }
        LegacyDeathV1Payload legacy = ((SnapshotDecodeResult.Decoded<
                LegacyDeathV1Payload>) decoded).value();
        CoopResidentStateSnapshot state = evidence.resolveLegacyDeath(
                profile,
                sourceAlias,
                legacy,
                source.createdAtMs()
        );
        return encodeProjection(sourceAlias, state);
    }

    private Resolution resolveLegacyLost(
            CompanionProfileReadModel profile,
            CompanionSnapshot source,
            NpcAlias sourceAlias
    ) {
        SnapshotDecodeResult<LegacyLostV1Payload> decoded =
                codecs.decode(source, LegacyLostV1Payload.class);
        if (decoded instanceof SnapshotDecodeResult.Failed<
                LegacyLostV1Payload> failed) {
            return decodeFailure(failed);
        }
        LegacyLostV1Payload legacy = ((SnapshotDecodeResult.Decoded<
                LegacyLostV1Payload>) decoded).value();
        CoopResidentStateSnapshot state = evidence.resolveLegacyLost(
                profile,
                sourceAlias,
                legacy,
                source.createdAtMs()
        );
        return encodeProjection(sourceAlias, state);
    }

    private Resolution encodeProjection(
            NpcAlias sourceAlias,
            CoopResidentStateSnapshot state
    ) {
        SnapshotCodecRegistry.EncodedSnapshot artifact = codecs.encode(
                CompanionFullStateProjection.KIND,
                CompanionFullStateProjection.VERSION,
                CoopResidentStateSnapshot.class,
                state
        );
        return new Resolution.Resolved(
                new RestorationProjection(sourceAlias, artifact)
        );
    }

    @Nullable
    private LifecycleState expectedState(SnapshotKind kind) {
        if (TameworkSnapshotCodecs.DEATH.equals(kind)) {
            return LifecycleState.DEAD_REVIVABLE;
        }
        if (TameworkSnapshotCodecs.LOST.equals(kind)) {
            return LifecycleState.LOST;
        }
        return null;
    }

    private Resolution decodeFailure(SnapshotDecodeResult.Failed<?> failure) {
        Failure mapped = switch (failure.failure()) {
            case HASH_MISMATCH -> Failure.HASH_MISMATCH;
            case UNSUPPORTED_CODEC -> Failure.UNSUPPORTED_CODEC;
            case TYPE_MISMATCH -> Failure.TYPE_MISMATCH;
            case DECODE_FAILED -> Failure.DECODE_FAILED;
        };
        return failed(mapped, failure.code(), null, failure.cause());
    }

    private Resolution failed(
            Failure failure,
            String code,
            @Nullable String field,
            @Nullable Throwable cause
    ) {
        return new Resolution.Failed(failure, code, field, cause);
    }

    /** Pure resolution outcome; failure is never interpreted as absent state. */
    public sealed interface Resolution
            permits Resolution.Resolved, Resolution.Failed {
        record Resolved(
                @Nonnull RestorationProjection projection
        ) implements Resolution {
            public Resolved {
                if (projection == null) {
                    throw new IllegalArgumentException(
                            "Resolved restoration projection is required"
                    );
                }
            }
        }

        record Failed(
                @Nonnull Failure failure,
                @Nonnull String code,
                @Nullable String field,
                @Nullable Throwable cause
        ) implements Resolution {
            public Failed {
                if (failure == null || code == null || code.isBlank()) {
                    throw new IllegalArgumentException(
                            "Restoration failure and code are required"
                    );
                }
                code = code.trim();
            }
        }
    }

    /** Stable resolution failure classes used by request admission and diagnostics. */
    public enum Failure {
        CONTEXT_MISMATCH,
        HASH_MISMATCH,
        UNSUPPORTED_CODEC,
        TYPE_MISMATCH,
        DECODE_FAILED,
        ROLE_MISSING,
        EVIDENCE_CONFLICT,
        CANONICAL_ENCODE_FAILED
    }

}
