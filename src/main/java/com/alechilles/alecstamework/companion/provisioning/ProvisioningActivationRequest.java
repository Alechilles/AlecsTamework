package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact first dormant-to-live transition for one provisioned profile. */
public record ProvisioningActivationRequest(
        @Nonnull ProvisioningOrigin origin,
        @Nonnull PopulationGroupTransitionAdmissionRequest groupAdmission,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String expectedRoleId,
        @Nonnull SnapshotCodecRegistry.EncodedSnapshot fullState,
        @Nonnull CompanionSpawnPlacement placement,
        @Nonnull String spawnReceiptKey,
        @Nullable TimedSummonActivation timedActivation,
        long requestedAtMs,
        @Nullable LifecycleAdmissionEvidence admissionEvidence
) {
    public ProvisioningActivationRequest {
        if (origin == null || groupAdmission == null
                || targetAlias == null || fullState == null
                || placement == null) {
            throw new IllegalArgumentException(
                    "Complete provisioning activation is required"
            );
        }
        expectedRoleId = text(
                expectedRoleId, "Provisioning activation role"
        );
        spawnReceiptKey = text(
                spawnReceiptKey, "Provisioning activation receipt"
        );
        if (!CompanionFullStateProjection.KIND.equals(fullState.kind())
                || fullState.payloadVersion()
                != CompanionFullStateProjection.VERSION) {
            throw new IllegalArgumentException(
                    "Provisioning activation requires modern full state"
            );
        }
        CompanionLifecycle before = groupAdmission.before();
        CompanionLifecycle after = groupAdmission.after();
        if (!before.profileId().equals(origin.profileId())
                || before.state()
                != LifecycleState.PROVISIONED_DORMANT
                || !before.location().equals(
                LifecycleLocation.keyed(
                        com.alechilles.alecstamework.companion.lifecycle
                                .LifecycleLocationKind.PROVISIONING,
                        origin.stableKey()
                )
                )
                || before.ownerId() == null
                || after.state() != LifecycleState.ACTIVE
                || !after.location().equals(
                LifecycleLocation.liveEntity(
                        targetAlias.toString(), placement.worldKey()
                )
        )
                || !java.util.Objects.equals(
                before.ownerId(), after.ownerId()
        )
                || !placement.worldKey().equals(after.ownerWorldKey())
                || requestedAtMs != groupAdmission.requestedAtMs()
                || requestedAtMs != after.stateChangedAtMs()) {
            throw new IllegalArgumentException(
                    "Provisioning activation lifecycle is inconsistent"
            );
        }
        requireTimed(timedActivation, before, requestedAtMs);
        requireAdmissionEvidence(
                admissionEvidence,
                origin,
                before,
                after,
                targetAlias,
                placement.worldKey()
        );
    }

    /** Source-compatible constructor without managed admission evidence. */
    public ProvisioningActivationRequest(
            ProvisioningOrigin origin,
            PopulationGroupTransitionAdmissionRequest groupAdmission,
            NpcAlias targetAlias,
            String expectedRoleId,
            SnapshotCodecRegistry.EncodedSnapshot fullState,
            CompanionSpawnPlacement placement,
            String spawnReceiptKey,
            TimedSummonActivation timedActivation,
            long requestedAtMs
    ) {
        this(
                origin,
                groupAdmission,
                targetAlias,
                expectedRoleId,
                fullState,
                placement,
                spawnReceiptKey,
                timedActivation,
                requestedAtMs,
                null
        );
    }

    /** Returns this request with its frozen lifecycle admission evidence attached. */
    @Nonnull
    public ProvisioningActivationRequest withAdmissionEvidence(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence is required"
            );
        }
        if (admissionEvidence != null
                && !admissionEvidence.equals(evidence)) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence cannot be replaced"
            );
        }
        return new ProvisioningActivationRequest(
                origin,
                groupAdmission,
                targetAlias,
                expectedRoleId,
                fullState,
                placement,
                spawnReceiptKey,
                timedActivation,
                requestedAtMs,
                evidence
        );
    }

    /** Returns the canonical target world without storing a second placement authority. */
    @Nonnull
    public String targetWorldKey() {
        return placement.worldKey();
    }

    /** Returns the post-fence lifecycle committed after live confirmation. */
    @Nonnull
    public CompanionLifecycle finalLifecycle() {
        CompanionLifecycle target = groupAdmission.after();
        return new CompanionLifecycle(
                target.profileId(),
                target.ownerId(),
                target.state(),
                target.location(),
                groupAdmission.before().revision().next().next(),
                null,
                requestedAtMs,
                target.lastReconciledGeneration(),
                target.quarantineIncidentId(),
                target.ownerWorldKey()
        );
    }

    private static void requireTimed(
            TimedSummonActivation timed,
            CompanionLifecycle before,
            long requestedAtMs
    ) {
        if (timed == null) {
            return;
        }
        TimedSummonLease lease = timed.lease();
        Long expectedRemaining = lease.policy().unlimited()
                ? null
                : lease.policy().activeDurationMs();
        if (timed.expectedPreviousLease() != null
                || !timed.familyKey().ownerId().equals(before.ownerId())
                || !lease.profileId().equals(before.profileId())
                || lease.leaseRevision() != 1
                || !lease.activeSession()
                || !java.util.Objects.equals(
                expectedRemaining, lease.remainingMs()
        )
                || lease.cooldownUntilMs() != null
                || !lease.emittedWarningThresholdsMs().equals(Set.of())
                || !java.util.Objects.equals(
                lease.checkpointedAtMs(), requestedAtMs
        )
                || lease.createdAtMs() != requestedAtMs
                || lease.updatedAtMs() != requestedAtMs) {
            throw new IllegalArgumentException(
                    "Provisioning timed activation must start one full session"
            );
        }
    }

    private static void requireAdmissionEvidence(
            LifecycleAdmissionEvidence evidence,
            ProvisioningOrigin origin,
            CompanionLifecycle before,
            CompanionLifecycle after,
            NpcAlias targetAlias,
            String targetWorld
    ) {
        if (evidence == null
                || evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return;
        }
        var payload = evidence.payload();
        if (payload == null
                || !payload.profileId().equals(origin.profileId())
                || !java.util.Objects.equals(
                payload.expectedLifecycleRevision(), before.revision()
        )
                || payload.sourceLifecycle() != before.state()
                || !java.util.Objects.equals(
                payload.sourceOwnerId(), before.ownerId()
        )
                || !java.util.Objects.equals(
                payload.sourceWorldKey(), before.ownerWorldKey()
        )
                || payload.targetLifecycle() != after.state()
                || !java.util.Objects.equals(
                payload.ownerId(), after.ownerId()
        )
                || !java.util.Objects.equals(
                payload.ownerWorldKey(), after.ownerWorldKey()
        )) {
            throw new IllegalArgumentException(
                    "Provisioning activation admission evidence is inconsistent"
            );
        }
        if (after.location().kind()
                != com.alechilles.alecstamework.companion.lifecycle
                .LifecycleLocationKind.LIVE_ENTITY
                || !targetAlias.toString().equals(after.location().key())
                || !targetWorld.equals(after.location().worldKey())) {
            throw new IllegalArgumentException(
                    "Provisioning activation target evidence is inconsistent"
            );
        }
        var convergence = evidence.convergencePlan();
        if (convergence != null
                && (!convergence.profileId().equals(origin.profileId())
                || !convergence.sourceLifecycleRevision().equals(
                before.revision()
        )
                || convergence.sourceState() != before.state()
                || convergence.targetState() != after.state()
                || !java.util.Objects.equals(
                convergence.sourceOwner(), before.ownerId()
        )
                || !java.util.Objects.equals(
                convergence.sourceWorldKey(), before.ownerWorldKey()
        )
                || !java.util.Objects.equals(
                convergence.targetOwner(), after.ownerId()
        )
                || !java.util.Objects.equals(
                convergence.targetWorldKey(), after.ownerWorldKey()
        ))) {
            throw new IllegalArgumentException(
                    "Provisioning activation convergence evidence is inconsistent"
            );
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
