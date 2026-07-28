package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturePopulationGroupEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameLiveEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.Set;

/** Independently constructs the exact expected tame/link cross-authority value. */
final class SpawnerTameAndLinkExpectedEvidenceFixture {
    private final SpawnerTameAndLinkEvidenceFixture source;

    SpawnerTameAndLinkExpectedEvidenceFixture(
            SpawnerTameAndLinkEvidenceFixture source
    ) {
        this.source = source;
    }

    CaptureTameAndLinkEvidence expectedEvidence() {
        CompanionIdentity targetIdentity = new CompanionIdentity(
                source.PROFILE,
                "Wild wyvern",
                "tamed_miniwyvern",
                "{\"tamed\":true}",
                Sha256Hash.ofUtf8("{\"tamed\":true}"),
                source.WORLD,
                -2_000L,
                source.REQUESTED_AT,
                source.REQUESTED_AT,
                5L
        );
        CompanionLifecycle finalLifecycle = new CompanionLifecycle(
                source.PROFILE,
                source.OWNER,
                LifecycleState.ACTIVE,
                source.lifecycle().location(),
                new LifecycleRevision(9L),
                null,
                source.REQUESTED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                source.WORLD
        );
        OwnerPopulationAdmissionPlan ownerPlan =
                new OwnerPopulationAdmissionPlan(
                        source.PROFILE,
                        new LifecycleRevision(7L),
                        List.of(
                                new OwnerPopulationAdmissionPlan.LimitIncrease(
                                        OwnerPopulationScope.global(
                                                source.OWNER
                                        ),
                                        1,
                                        4
                                ),
                                new OwnerPopulationAdmissionPlan.LimitIncrease(
                                        OwnerPopulationScope.perWorld(
                                                source.OWNER, source.WORLD
                                        ),
                                        1,
                                        2
                                )
                        )
                );
        PopulationGroupAssignment assignment =
                new PopulationGroupAssignment(
                        source.PROFILE,
                        "tamed_miniwyvern",
                        List.of(
                                new PopulationGroupMembership(
                                        "bonded",
                                        PopulationGroupScope.GLOBAL
                                ),
                                new PopulationGroupMembership(
                                        "wyvern",
                                        PopulationGroupScope.PER_WORLD
                                )
                        ),
                        source.POLICY_REVISION,
                        5L,
                        new LifecycleRevision(9L),
                        1L,
                        source.REQUESTED_AT
                );
        CapturePopulationGroupEvidence groups =
                new CapturePopulationGroupEvidence(
                        null,
                        new PopulationGroupAssignmentPlan(
                                assignment,
                                List.of(
                                        reservation(
                                                source.globalBucket(),
                                                source.GLOBAL_POLICY
                                        ),
                                        reservation(
                                                source.worldBucket(),
                                                source.WORLD_POLICY
                                        )
                                )
                        )
                );
        CommandRosterMembershipDraft membership =
                new CommandRosterMembershipDraft(
                        source.SLOT,
                        source.FAMILY,
                        source.PROFILE,
                        "bonded",
                        true,
                        null,
                        source.REQUESTED_AT
                );
        TimedSummonLease lease = new TimedSummonLease(
                source.PROFILE,
                1L,
                new TimedSummonSessionId(source.OPERATION.value()),
                source.TIMED_POLICY.activeDurationMs(),
                null,
                source.TIMED_POLICY,
                Set.of(),
                source.REQUESTED_AT,
                source.REQUESTED_AT,
                source.REQUESTED_AT
        );
        return new CaptureTameAndLinkEvidence(
                source.identity(),
                targetIdentity,
                source.lifecycle(),
                finalLifecycle,
                ownerPlan,
                groups,
                0L,
                membership,
                new TimedSummonActivation(
                        source.FAMILY, source.SLOT, 1L, lease
                ),
                new CaptureTameLiveEvidence(
                        "wild_miniwyvern",
                        null,
                        false,
                        Sha256Hash.ofUtf8("wild-live"),
                        "tamed_miniwyvern",
                        source.OWNER,
                        "Alec",
                        Sha256Hash.ofUtf8("tamed-live"),
                        source.ACCESS
                )
        );
    }

    private PopulationGroupReservation reservation(
            com.alechilles.alecstamework.companion.population.group
                    .PopulationGroupBucket bucket,
            PopulationGroupPolicy policy
    ) {
        return new PopulationGroupReservation(
                source.OPERATION,
                source.PROFILE,
                new LifecycleRevision(7L),
                bucket,
                1,
                1,
                policy.maxOwnedPerOwner(),
                policy.maxActivePerOwner(),
                source.POLICY_REVISION,
                source.REQUESTED_AT
        );
    }
}
