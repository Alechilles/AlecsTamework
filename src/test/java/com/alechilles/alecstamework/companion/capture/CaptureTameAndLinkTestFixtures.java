package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic complete tame/link evidence shared by focused tests. */
final class CaptureTameAndLinkTestFixtures {
    static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000101"
    );
    static final NpcAlias ALIAS = NpcAlias.parse(
            "20000000-0000-0000-0000-000000000101"
    );
    static final OwnerId OWNER = OwnerId.parse(
            "30000000-0000-0000-0000-000000000101"
    );
    static final UUID ATTEMPT = UUID.fromString(
            "40000000-0000-0000-0000-000000000101"
    );
    static final OperationId OPERATION = OperationId.parse(
            "50000000-0000-0000-0000-000000000101"
    );
    static final LifecycleRevision EXPECTED = new LifecycleRevision(4);
    static final long NOW = -500;

    private CaptureTameAndLinkTestFixtures() {
    }

    static CompanionCaptureRequest request() {
        CaptureAttemptResolution resolution = resolution();
        return new CompanionCaptureRequest(
                PROFILE,
                EXPECTED,
                OWNER,
                ALIAS,
                "world",
                new CaptureTerminalPlan.TameAndCommandLink(
                        resolution, evidence()
                ),
                new CaptureSourceEvidence(
                        OWNER.value(),
                        "world",
                        2,
                        "HyDragon_Draconic_Stone",
                        1,
                        Sha256Hash.ofUtf8("source"),
                        ATTEMPT.toString()
                ),
                NOW
        );
    }

    static CaptureTameAndLinkEvidence evidence() {
        CompanionIdentity before = identity(
                "Dragon_Fire", "{\"tamed\":false}", 3, -900
        );
        CompanionIdentity after = identity(
                "Tamed_Dragon_Fire",
                "{\"owner_name\":\"Alec\",\"tamed\":true}",
                4,
                NOW
        );
        CompanionLifecycle beforeLifecycle = new CompanionLifecycle(
                PROFILE,
                null,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world"
                ),
                EXPECTED,
                null,
                -800,
                new ReconciliationGeneration(2),
                null,
                null
        );
        CompanionLifecycle afterLifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                beforeLifecycle.location(),
                EXPECTED.next().next(),
                null,
                NOW,
                beforeLifecycle.lastReconciledGeneration(),
                null,
                "world"
        );
        CommandFamilyKey family = new CommandFamilyKey(
                OWNER, "hydragon:dragon_horn"
        );
        CommandRosterSlotId slot = new CommandRosterSlotId(
                UUID.fromString(
                        "60000000-0000-0000-0000-000000000101"
                )
        );
        CommandRosterMembershipDraft roster =
                new CommandRosterMembershipDraft(
                        slot, family, PROFILE, null, true, null, NOW
                );
        TimedSummonPolicy policy = new TimedSummonPolicy(
                "HyDragonFullDragons",
                1L,
                600_000,
                300_000,
                true,
                List.of(300_000L, 60_000L)
        );
        TimedSummonLease lease = new TimedSummonLease(
                PROFILE,
                1,
                new TimedSummonSessionId(UUID.fromString(
                        "70000000-0000-0000-0000-000000000101"
                )),
                600_000L,
                null,
                policy,
                Set.of(),
                NOW,
                NOW,
                NOW
        );
        return new CaptureTameAndLinkEvidence(
                before,
                after,
                beforeLifecycle,
                afterLifecycle,
                ownerPopulation(),
                groups(after, afterLifecycle),
                0,
                roster,
                new TimedSummonActivation(family, slot, 1, lease),
                new CaptureTameLiveEvidence(
                        "Dragon_Fire",
                        null,
                        false,
                        Sha256Hash.ofUtf8("live-before"),
                        "Tamed_Dragon_Fire",
                        OWNER,
                        "Alec",
                        Sha256Hash.ofUtf8("live-after"),
                        new CaptureCommandAccessEvidence(
                                "HyDragonDragonHorn",
                                7,
                                family.familyId(),
                                List.of("HyDragon_Dragon_Horn")
                        )
                )
        );
    }

    static CaptureAttemptResolution resolution() {
        return new CaptureAttemptResolution(
                ATTEMPT,
                "Dragon_Fire",
                new CaptureAttemptFormula(
                        "HyDragonDraconicStone",
                        7,
                        CaptureChanceMode.PROBABILITY,
                        4,
                        0.2D,
                        0.1D,
                        0.05D,
                        0.95D,
                        "HyDragonDragonCapture",
                        11,
                        2,
                        0.1D,
                        0.8D,
                        0.5D,
                        8,
                        Sha256Hash.ofUtf8("requirements"),
                        13
                ),
                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                CaptureAttemptResolution.Outcome.SUCCESS,
                "capture-probability-success",
                0.35D,
                false,
                0.5D,
                0.2D,
                null
        );
    }

    private static CompanionIdentity identity(
            String role,
            String metadata,
            long revision,
            long updatedAt
    ) {
        return new CompanionIdentity(
                PROFILE,
                "Dragon",
                role,
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -1_000,
                updatedAt,
                updatedAt,
                revision
        );
    }

    private static OwnerPopulationAdmissionPlan ownerPopulation() {
        return new OwnerPopulationAdmissionPlan(
                PROFILE,
                EXPECTED,
                List.of(
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.global(OWNER),
                                1,
                                0
                        ),
                        new OwnerPopulationAdmissionPlan.LimitIncrease(
                                OwnerPopulationScope.perWorld(
                                        OWNER, "world"
                                ),
                                1,
                                0
                        )
                )
        );
    }

    private static CapturePopulationGroupEvidence groups(
            CompanionIdentity identity,
            CompanionLifecycle lifecycle
    ) {
        PopulationGroupMembership membership =
                new PopulationGroupMembership(
                        "full_dragons",
                        PopulationGroupScope.GLOBAL
                );
        PopulationGroupAssignment target =
                new PopulationGroupAssignment(
                        PROFILE,
                        identity.roleId(),
                        List.of(membership),
                        9,
                        identity.metadataRevision(),
                        lifecycle.revision(),
                        1,
                        NOW
                );
        PopulationGroupReservation reservation =
                new PopulationGroupReservation(
                        OPERATION,
                        PROFILE,
                        EXPECTED,
                        new PopulationGroupBucket(
                                OWNER,
                                membership.groupId(),
                                membership.scope(),
                                null
                        ),
                        1,
                        1,
                        0,
                        1,
                        9,
                        NOW
                );
        return new CapturePopulationGroupEvidence(
                null,
                new PopulationGroupAssignmentPlan(
                        target, List.of(reservation)
                )
        );
    }
}
