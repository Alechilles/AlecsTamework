package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable command to restore one exact dormant snapshot.
 *
 * <p>Released active restoration carries a complete live projection and target
 * placement. A provisioned dormant revival deliberately carries none of those
 * live-only facts: it advances the canonical lifecycle while preserving the
 * current death snapshot for a later activation.</p>
 */
public record CompanionRestorationRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull LifecycleState sourceState,
        @Nonnull CompanionSnapshot sourceSnapshot,
        @Nonnull LifecycleState targetState,
        @Nullable RestorationProjection projection,
        @Nullable NpcAlias targetAlias,
        @Nullable CompanionSpawnPlacement placement,
        @Nullable String spawnReceiptKey,
        long requestedAtMs,
        @Nullable LifecycleAdmissionEvidence admissionEvidence
) {
    /** Compatibility constructor for the existing active restoration shape. */
    public CompanionRestorationRequest(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nonnull LifecycleState sourceState,
            @Nonnull CompanionSnapshot sourceSnapshot,
            @Nonnull RestorationProjection projection,
            @Nonnull NpcAlias targetAlias,
            @Nonnull CompanionSpawnPlacement placement,
            @Nonnull String spawnReceiptKey,
            long requestedAtMs
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceState,
                sourceSnapshot,
                LifecycleState.ACTIVE,
                projection,
                targetAlias,
                placement,
                spawnReceiptKey,
                requestedAtMs,
                null
        );
    }

    /** Compatibility constructor for the explicit target-state shape. */
    public CompanionRestorationRequest(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nonnull LifecycleState sourceState,
            @Nonnull CompanionSnapshot sourceSnapshot,
            @Nonnull LifecycleState targetState,
            @Nullable RestorationProjection projection,
            @Nullable NpcAlias targetAlias,
            @Nullable CompanionSpawnPlacement placement,
            @Nullable String spawnReceiptKey,
            long requestedAtMs
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceState,
                sourceSnapshot,
                targetState,
                projection,
                targetAlias,
                placement,
                spawnReceiptKey,
                requestedAtMs,
                null
        );
    }

    public CompanionRestorationRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || sourceState == null || sourceSnapshot == null
                || targetState == null) {
            throw new IllegalArgumentException("Complete companion restoration is required");
        }
        if (sourceState != LifecycleState.DEAD_REVIVABLE
                && sourceState != LifecycleState.LOST) {
            throw new IllegalArgumentException(
                    "Restoration source must be death or lost"
            );
        }
        if (!profileId.equals(sourceSnapshot.profileId())
                || !sourceSnapshot.current()
                || sourceSnapshot.sourceLifecycleRevision()
                .compareTo(expectedLifecycleRevision) > 0
                || !expectedSnapshotKind(sourceState)
                .equals(sourceSnapshot.kind())) {
            throw new IllegalArgumentException(
                    "Restoration snapshot must be the exact current source artifact"
            );
        }
        if (targetState == LifecycleState.ACTIVE) {
            spawnReceiptKey = requireText(
                    spawnReceiptKey, "Restoration spawn receipt"
            );
            requireActiveTarget(projection, targetAlias, placement);
        } else if (targetState == LifecycleState.PROVISIONED_DORMANT) {
            requireDormantTarget(
                    sourceState, projection, targetAlias,
                    placement, spawnReceiptKey
            );
        } else {
            throw new IllegalArgumentException(
                    "Restoration target must be active or provisioned dormant"
            );
        }
        requireAdmissionEvidence(
                admissionEvidence,
                profileId,
                expectedLifecycleRevision,
                sourceState,
                targetState,
                placement
        );
    }

    /** Authors a database-only provisioned revival with no live target. */
    @Nonnull
    public static CompanionRestorationRequest reviveProvisionedDormant(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nonnull CompanionSnapshot sourceSnapshot,
            long requestedAtMs
    ) {
        return new CompanionRestorationRequest(
                profileId,
                expectedLifecycleRevision,
                LifecycleState.DEAD_REVIVABLE,
                sourceSnapshot,
                LifecycleState.PROVISIONED_DORMANT,
                null,
                null,
                null,
                null,
                requestedAtMs,
                null
        );
    }

    /** True only when restoration must insert a live entity. */
    public boolean restoresLive() {
        return targetState == LifecycleState.ACTIVE;
    }

    /** Returns this request with frozen lifecycle admission evidence attached. */
    @Nonnull
    public CompanionRestorationRequest withAdmissionEvidence(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence is required"
            );
        }
        if (admissionEvidence != null
                && !java.util.Objects.equals(admissionEvidence, evidence)) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence cannot be replaced"
            );
        }
        return new CompanionRestorationRequest(
                profileId,
                expectedLifecycleRevision,
                sourceState,
                sourceSnapshot,
                targetState,
                projection,
                targetAlias,
                placement,
                spawnReceiptKey,
                requestedAtMs,
                evidence
        );
    }

    /** Returns the canonical target world for active restoration. */
    @Nullable
    public String targetWorldKey() {
        return placement == null ? null : placement.worldKey();
    }

    private static void requireAdmissionEvidence(
            LifecycleAdmissionEvidence evidence,
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            LifecycleState sourceState,
            LifecycleState targetState,
            CompanionSpawnPlacement placement
    ) {
        if (evidence == null
                || evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return;
        }
        if (sourceState != LifecycleState.DEAD_REVIVABLE
                || targetState != LifecycleState.ACTIVE) {
            throw new IllegalArgumentException(
                    "Managed restoration evidence requires death to active restoration"
                            + " (source=" + sourceState
                            + ", target=" + targetState + ")"
            );
        }
        var payload = evidence.payload();
        if (payload == null
                || !payload.profileId().equals(profileId)
                || !java.util.Objects.equals(
                payload.expectedLifecycleRevision(), expectedLifecycleRevision
        )
                || payload.sourceLifecycle() != LifecycleState.DEAD_REVIVABLE
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || !java.util.Objects.equals(
                payload.ownerWorldKey(),
                payload.ownerId() == null ? null : placement.worldKey()
        )) {
            throw new IllegalArgumentException(
                    "Restoration lifecycle admission evidence is inconsistent"
            );
        }
    }

    private static void requireActiveTarget(
            RestorationProjection projection,
            NpcAlias targetAlias,
            CompanionSpawnPlacement placement
    ) {
        if (projection == null || targetAlias == null || placement == null) {
            throw new IllegalArgumentException(
                    "Active restoration target is incomplete"
            );
        }
        if (!CompanionFullStateProjection.KIND.equals(
                projection.fullState().kind()
        )
                || projection.fullState().payloadVersion()
                != CompanionFullStateProjection.VERSION
                || projection.sourceAlias().equals(targetAlias)) {
            throw new IllegalArgumentException(
                    "Restoration projection must be complete modern state for a distinct source alias"
            );
        }
    }

    private static void requireDormantTarget(
            LifecycleState sourceState,
            RestorationProjection projection,
            NpcAlias targetAlias,
            CompanionSpawnPlacement placement,
            String spawnReceiptKey
    ) {
        if (sourceState != LifecycleState.DEAD_REVIVABLE
                || projection != null || targetAlias != null
                || placement != null || spawnReceiptKey != null) {
            throw new IllegalArgumentException(
                    "Provisioned dormant revival cannot declare a live target"
            );
        }
    }

    private static com.alechilles.alecstamework.companion.snapshot.SnapshotKind
    expectedSnapshotKind(LifecycleState sourceState) {
        return sourceState == LifecycleState.DEAD_REVIVABLE
                ? DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind()
                : DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL.snapshotKind();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
