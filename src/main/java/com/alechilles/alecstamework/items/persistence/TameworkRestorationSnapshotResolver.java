package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
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
        NpcAlias sourceAlias = profile.currentAlias().alias();
        try {
            return resolveKnown(profile, source, sourceAlias);
        } catch (MissingRole missing) {
            return failed(
                    Failure.ROLE_MISSING,
                    "restoration_role_missing",
                    "roleId",
                    null
            );
        } catch (EvidenceConflict conflict) {
            return failed(
                    Failure.EVIDENCE_CONFLICT,
                    "restoration_evidence_conflict",
                    conflict.field,
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
        if (profile.currentAlias() == null
                || profile.currentAlias().state()
                != CompanionAlias.State.CURRENT) {
            return failed(
                    Failure.SOURCE_ALIAS_MISSING,
                    "restoration_source_alias_missing",
                    "currentAlias",
                    null
            );
        }
        return null;
    }

    private Resolution resolveKnown(
            CompanionProfileReadModel profile,
            CompanionSnapshot source,
            NpcAlias sourceAlias
    ) {
        if (source.payloadVersion() == COMPLETE_STATE_VERSION) {
            return resolveComplete(profile, source, sourceAlias);
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
            return resolveLegacyDeath(profile, source, sourceAlias);
        }
        if (source.kind().equals(TameworkSnapshotCodecs.LOST)) {
            return resolveLegacyLost(profile, source, sourceAlias);
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
            CompanionSnapshot source,
            NpcAlias sourceAlias
    ) {
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                codecs.decode(source, CoopResidentStateSnapshot.class);
        if (decoded instanceof SnapshotDecodeResult.Failed<
                CoopResidentStateSnapshot> failed) {
            return decodeFailure(failed);
        }
        CoopResidentStateSnapshot state = ((SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot>) decoded).value();
        validateComplete(profile, sourceAlias, state);
        SnapshotCodecRegistry.EncodedSnapshot artifact =
                new SnapshotCodecRegistry.EncodedSnapshot(
                        source.kind(),
                        source.payloadVersion(),
                        source.payloadJson(),
                        source.payloadHash()
                );
        return new Resolution.Resolved(
                new RestorationProjection(sourceAlias, artifact)
        );
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
        LegacyRestorationEvidence.Metadata metadata = metadata(profile);
        String roleId = reconcileRole(
                profile.identity().roleId(),
                legacy.roleId()
        );
        UUID ownerId = reconcileOwner(profile, legacy.ownerId());
        String ownerName = reconcile(
                "ownerName",
                metadata.ownerName(),
                legacy.ownerName()
        );
        if (ownerId == null && ownerName != null) {
            throw new EvidenceConflict("ownerName");
        }
        String customName = reconcile(
                "customName",
                metadata.customName(),
                legacy.customName()
        );
        if (metadata.tamed() != null
                && metadata.tamed() != legacy.tamed()) {
            throw new EvidenceConflict("tamed");
        }
        CoopResidentStateSnapshot state =
                LegacyRestorationFullStateMapper.death(
                        sourceAlias.value(),
                        roleId,
                        ownerId,
                        ownerName,
                        customName,
                        toolIds(profile),
                        legacy,
                        source.createdAtMs()
                );
        return encodeProjection(source.kind(), sourceAlias, state);
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
        if (legacy.replacementNpcUuid() != null
                || legacy.recoveredAtMs() != 0L) {
            throw new EvidenceConflict("legacyRecoveryEvidence");
        }
        LegacyRestorationEvidence.Metadata metadata = metadata(profile);
        String roleId = requireRole(profile.identity().roleId());
        UUID ownerId = owner(profile);
        if (ownerId == null && metadata.ownerName() != null) {
            throw new EvidenceConflict("ownerName");
        }
        CoopResidentStateSnapshot state =
                LegacyRestorationFullStateMapper.lost(
                        sourceAlias.value(),
                        roleId,
                        ownerId,
                        metadata.ownerName(),
                        metadata.customName(),
                        metadata.tamed(),
                        toolIds(profile),
                        legacy,
                        source.createdAtMs()
                );
        return encodeProjection(source.kind(), sourceAlias, state);
    }

    private Resolution encodeProjection(
            SnapshotKind kind,
            NpcAlias sourceAlias,
            CoopResidentStateSnapshot state
    ) {
        SnapshotCodecRegistry.EncodedSnapshot artifact = codecs.encode(
                kind,
                COMPLETE_STATE_VERSION,
                CoopResidentStateSnapshot.class,
                state
        );
        return new Resolution.Resolved(
                new RestorationProjection(sourceAlias, artifact)
        );
    }

    private void validateComplete(
            CompanionProfileReadModel profile,
            NpcAlias sourceAlias,
            CoopResidentStateSnapshot state
    ) {
        if (!sourceAlias.value().equals(state.npcUuid())) {
            throw new EvidenceConflict("sourceAlias");
        }
        String role = requireRole(state.roleId());
        String profileRole = normalize(profile.identity().roleId());
        if (profileRole != null && !profileRole.equalsIgnoreCase(role)) {
            throw new EvidenceConflict("roleId");
        }
        UUID canonicalOwner = owner(profile);
        TameworkOwnerComponent owner = state.owner();
        TameworkCommandLinksComponent links = state.commandLinks();
        validateOwner(canonicalOwner, owner == null ? null : owner.getOwnerId());
        validateOwner(canonicalOwner, links == null ? null : links.getOwnerId());
    }

    private void validateOwner(
            @Nullable UUID canonical,
            @Nullable UUID snapshot
    ) {
        if (snapshot != null && !Objects.equals(canonical, snapshot)) {
            throw new EvidenceConflict("ownerId");
        }
    }

    private UUID reconcileOwner(
            CompanionProfileReadModel profile,
            @Nullable UUID payloadOwner
    ) {
        UUID canonical = owner(profile);
        if (payloadOwner != null && !payloadOwner.equals(canonical)) {
            throw new EvidenceConflict("ownerId");
        }
        return canonical;
    }

    @Nullable
    private UUID owner(CompanionProfileReadModel profile) {
        OwnerId owner = profile.lifecycle().ownerId();
        return owner == null ? null : owner.value();
    }

    private String reconcileRole(
            @Nullable String profileRole,
            @Nullable String payloadRole
    ) {
        String canonical = normalize(profileRole);
        String payload = normalize(payloadRole);
        if (canonical != null && payload != null
                && !canonical.equalsIgnoreCase(payload)) {
            throw new EvidenceConflict("roleId");
        }
        return requireRole(canonical != null ? canonical : payload);
    }

    private String requireRole(@Nullable String role) {
        String normalized = normalize(role);
        if (normalized == null) {
            throw new MissingRole();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String reconcile(
            String field,
            @Nullable String first,
            @Nullable String second
    ) {
        String left = normalize(first);
        String right = normalize(second);
        if (left != null && right != null && !left.equals(right)) {
            throw new EvidenceConflict(field);
        }
        return left != null ? left : right;
    }

    private LegacyRestorationEvidence.Metadata metadata(
            CompanionProfileReadModel profile
    ) {
        return LegacyRestorationEvidence.metadata(
                profile.identity().metadataJson()
        );
    }

    private String[] toolIds(CompanionProfileReadModel profile) {
        return profile.toolLinks().stream()
                .map(CompanionToolLink::toolId)
                .distinct()
                .sorted()
                .map(UUID::toString)
                .toArray(String[]::new);
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

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        SOURCE_ALIAS_MISSING,
        ROLE_MISSING,
        EVIDENCE_CONFLICT,
        CANONICAL_ENCODE_FAILED
    }

    private static final class EvidenceConflict
            extends IllegalArgumentException {
        private final String field;

        private EvidenceConflict(String field) {
            super(field);
            this.field = field;
        }
    }

    private static final class MissingRole extends IllegalArgumentException {
    }
}
