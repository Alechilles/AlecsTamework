package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact first dormant-to-live transition for one provisioned profile. */
public record ProvisioningActivationRequest(
        @Nonnull ProvisioningOrigin origin,
        @Nonnull PopulationGroupTransitionAdmissionRequest groupAdmission,
        @Nonnull NpcAlias targetAlias,
        @Nonnull CompanionSpawnPlacement placement,
        @Nonnull String spawnReceiptKey,
        @Nullable TimedSummonActivation timedActivation,
        long requestedAtMs
) {
    public ProvisioningActivationRequest {
        if (origin == null || groupAdmission == null
                || targetAlias == null || placement == null) {
            throw new IllegalArgumentException(
                    "Complete provisioning activation is required"
            );
        }
        spawnReceiptKey = text(
                spawnReceiptKey, "Provisioning activation receipt"
        );
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
                || !placement.worldKey().equals(before.ownerWorldKey())
                || after.state() != LifecycleState.ACTIVE
                || !after.location().equals(
                LifecycleLocation.liveEntity(
                        targetAlias.toString(), placement.worldKey()
                )
        )
                || !java.util.Objects.equals(
                before.ownerId(), after.ownerId()
        )
                || !java.util.Objects.equals(
                before.ownerWorldKey(), after.ownerWorldKey()
        )
                || requestedAtMs != groupAdmission.requestedAtMs()
                || requestedAtMs != after.stateChangedAtMs()) {
            throw new IllegalArgumentException(
                    "Provisioning activation lifecycle is inconsistent"
            );
        }
        requireTimed(timedActivation, before, requestedAtMs);
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

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}

