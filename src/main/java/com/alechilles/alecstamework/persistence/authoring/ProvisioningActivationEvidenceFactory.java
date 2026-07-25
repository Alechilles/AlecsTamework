package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure builder for exact initial provisioned dormant-to-live evidence. */
final class ProvisioningActivationEvidenceFactory {
    @Nonnull
    ProvisioningActivationRequest create(
            @Nonnull ProvisioningOrigin origin,
            @Nonnull CompanionLifecycle before,
            @Nonnull String roleId,
            @Nonnull PopulationGroupAssignment assignment,
            @Nonnull List<PopulationGroupPolicy> policies,
            @Nullable CommandRosterMembershipDraft draft,
            @Nullable CommandRosterMembership membership,
            boolean timedEnabled,
            @Nonnull TimedSummonPolicy timedPolicy,
            @Nonnull OperationId operationId,
            @Nonnull NpcAlias targetAlias,
            @Nonnull String receipt,
            @Nullable PopulationAdmissionLocation destination,
            @Nonnull ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence world
    ) {
        requireWorld(destination, targetAlias, world);
        CompanionLifecycle after = new CompanionLifecycle(
                before.profileId(),
                before.ownerId(),
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        targetAlias.toString(),
                        world.placement().worldKey()
                ),
                before.revision().next(),
                null,
                world.observedAtMs(),
                before.lastReconciledGeneration(),
                null,
                world.placement().worldKey()
        );
        PopulationGroupTransitionAdmissionRequest admission =
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        assignment.assignmentRevision(),
                        assignment.policyRevision(),
                        policies,
                        world.observedAtMs()
                );
        return new ProvisioningActivationRequest(
                origin,
                admission,
                targetAlias,
                roleId,
                world.fullState(),
                world.placement(),
                receipt,
                timed(
                        draft,
                        membership,
                        timedEnabled,
                        timedPolicy,
                        operationId,
                        world.observedAtMs()
                ),
                world.observedAtMs()
        );
    }

    @Nullable
    private TimedSummonActivation timed(
            @Nullable CommandRosterMembershipDraft draft,
            @Nullable CommandRosterMembership membership,
            boolean enabled,
            TimedSummonPolicy policy,
            OperationId operationId,
            long now
    ) {
        if (!enabled || draft == null && membership == null) {
            return null;
        }
        var family = membership == null
                ? draft.familyKey()
                : membership.familyKey();
        var slot = membership == null
                ? draft.slotId()
                : membership.slotId();
        long membershipRevision = membership == null
                ? 1
                : membership.membershipRevision();
        var profile = membership == null
                ? draft.profileId()
                : membership.profileId();
        TimedSummonLease lease = new TimedSummonLease(
                profile,
                1,
                new TimedSummonSessionId(operationId.value()),
                policy.unlimited() ? null : policy.activeDurationMs(),
                null,
                policy,
                Set.of(),
                now,
                now,
                now
        );
        return new TimedSummonActivation(
                family, slot, membershipRevision, lease
        );
    }

    private void requireWorld(
            @Nullable PopulationAdmissionLocation destination,
            NpcAlias targetAlias,
            ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence world
    ) {
        if (world == null || world.placement() == null
                || world.fullState() == null
                || destination != null
                && !destination.equals(world.admittedLocation())) {
            throw new IllegalArgumentException(
                    "Exact provisioning projection world is required"
            );
        }
        if (!world.placement().worldKey().equals(
                destination == null
                        ? world.placement().worldKey()
                        : destination.worldName()
        )) {
            throw new IllegalArgumentException(
                    "Provisioning placement world mismatch"
            );
        }
    }
}
