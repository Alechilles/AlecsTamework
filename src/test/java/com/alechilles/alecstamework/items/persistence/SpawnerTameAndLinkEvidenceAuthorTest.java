package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.CommandActivationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupEvidence;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exactness and fail-closed admission tests for tame/link capture evidence authoring. */
class SpawnerTameAndLinkEvidenceAuthorTest {
    private final SpawnerTameAndLinkEvidenceFixture fixture =
            new SpawnerTameAndLinkEvidenceFixture();
    private final SpawnerTameAndLinkEvidenceAuthor author =
            new SpawnerTameAndLinkEvidenceAuthor();

    @Test
    void authorsExactCrossAuthorityTargetAtNegativeWorldTime() {
        CaptureTameAndLinkEvidence actual = author.author(
                fixture.baseInput()
        );
        CaptureTameAndLinkEvidence expected =
                new SpawnerTameAndLinkExpectedEvidenceFixture(fixture)
                        .expectedEvidence();

        assertEquals(expected, actual);
        assertEquals(
                fixture.REQUESTED_AT,
                actual.timedActivation().lease().checkpointedAtMs()
        );
        assertEquals(
                fixture.OPERATION.value(),
                actual.timedActivation().lease().sessionId().value()
        );
        assertEquals(1, actual.timedActivation().lease().leaseRevision());
        assertEquals(
                fixture.TIMED_POLICY.activeDurationMs(),
                actual.timedActivation().lease().remainingMs()
        );
    }

    @Test
    void rejectsAnyProfileThatIsNotExactWildLiveEvidence() {
        CompanionLifecycle current = fixture.lifecycle();
        assertFailure(
                fixture.withLifecycle(new CompanionLifecycle(
                        fixture.PROFILE,
                        fixture.OWNER,
                        LifecycleState.ACTIVE,
                        current.location(),
                        current.revision(),
                        null,
                        current.stateChangedAtMs(),
                        current.lastReconciledGeneration(),
                        null,
                        fixture.WORLD
                )),
                "capture_tame_profile_not_exact_wild_live"
        );
        assertFailure(
                fixture.withLifecycle(new CompanionLifecycle(
                        fixture.PROFILE,
                        null,
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        current.revision(),
                        null,
                        current.stateChangedAtMs(),
                        current.lastReconciledGeneration(),
                        null,
                        null
                )),
                "capture_tame_profile_not_exact_wild_live"
        );
        assertFailure(
                new SpawnerTameAndLinkEvidenceInput(
                        fixture.OPERATION,
                        fixture.REQUESTED_AT,
                        new CompanionIdentity(
                                fixture.PROFILE,
                                "Wild wyvern",
                                null,
                                "{}",
                                Sha256Hash.ofUtf8("{}"),
                                fixture.WORLD,
                                -2_000L,
                                -1_000L,
                                -900L,
                                4L
                        ),
                        current,
                        "wild_miniwyvern",
                        fixture.baseIntentEvidence()
                ),
                "capture_tame_profile_not_exact_wild_live"
        );
    }

    @Test
    void globalOwnerAdmissionIncludesPendingAndCommittedCounts() {
        assertFailure(
                fixture.withOwnerPopulation(new OwnerPopulationEvidence(
                        2,
                        2,
                        List.of(
                                fixture.ownerCount(
                                        OwnerPopulationScope.global(
                                                fixture.OWNER
                                        ),
                                        1, 1
                                ),
                                fixture.ownerCount(
                                        OwnerPopulationScope.perWorld(
                                                fixture.OWNER,
                                                fixture.WORLD
                                        ),
                                        0, 0
                                )
                        )
                )),
                "capture_tame_owner_capacity_reached"
        );
    }

    @Test
    void perWorldOwnerAdmissionIncludesPendingAndCommittedCounts() {
        assertFailure(
                fixture.withOwnerPopulation(new OwnerPopulationEvidence(
                        4,
                        2,
                        List.of(
                                fixture.ownerCount(
                                        OwnerPopulationScope.global(
                                                fixture.OWNER
                                        ),
                                        0, 0
                                ),
                                fixture.ownerCount(
                                        OwnerPopulationScope.perWorld(
                                                fixture.OWNER,
                                                fixture.WORLD
                                        ),
                                        1, 1
                                )
                        )
                )),
                "capture_tame_owner_capacity_reached"
        );
    }

    @Test
    void allPopulationGroupsMustAdmitAsOneAtomicSet() {
        PopulationGroupEvidence blocked = new PopulationGroupEvidence(
                null,
                fixture.POLICY_REVISION,
                List.of(fixture.GLOBAL_POLICY, fixture.WORLD_POLICY),
                List.of(
                        fixture.groupCount(
                                fixture.globalBucket(),
                                new PopulationGroupCounts(1, 1, 0, 0)
                        ),
                        fixture.groupCount(
                                fixture.worldBucket(),
                                new PopulationGroupCounts(1, 1, 1, 1)
                        )
                )
        );
        assertFailure(
                fixture.withGroups(blocked),
                "capture_tame_group_owned_capacity_reached"
        );

        CaptureTameAndLinkEvidence admitted =
                author.author(fixture.baseInput());
        assertEquals(
                Set.of(
                        fixture.globalBucket(), fixture.worldBucket()
                ),
                admitted.populationGroups().targetPlan().reservations()
                        .stream()
                        .map(PopulationGroupReservation::bucket)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void duplicateCanonicalProfileMembershipIsRejected() {
        CommandRosterMembership duplicate = fixture.membership(
                fixture.PROFILE, fixture.OTHER_SLOT
        );
        assertFailure(
                fixture.withCommand(fixture.command(
                        fixture.roster(List.of(duplicate)),
                        null,
                        null,
                        null
                )),
                "capture_tame_command_source_duplicate"
        );
    }

    @Test
    void duplicateCanonicalSlotMembershipIsRejected() {
        CommandRosterMembership duplicate = fixture.membership(
                fixture.OTHER_PROFILE, fixture.SLOT
        );
        assertFailure(
                fixture.withCommand(fixture.command(
                        fixture.roster(List.of(duplicate)),
                        null,
                        null,
                        null
                )),
                "capture_tame_command_source_duplicate"
        );
    }

    @Test
    void duplicateLookupOrTimedLeaseEvidenceIsRejected() {
        CommandRosterMembership existing = fixture.membership(
                fixture.OTHER_PROFILE, fixture.OTHER_SLOT
        );
        assertFailure(
                fixture.withCommand(fixture.command(
                        null, existing, null, null
                )),
                "capture_tame_command_source_duplicate"
        );
        assertFailure(
                fixture.withCommand(fixture.command(
                        null, null, existing, null
                )),
                "capture_tame_command_source_duplicate"
        );
        assertFailure(
                fixture.withCommand(fixture.command(
                        null, null, null, fixture.dormantLease()
                )),
                "capture_tame_command_source_duplicate"
        );
    }

    @Test
    void rosterAndGroupRevisionFencesRejectStaleEvidence() {
        CommandActivationEvidence staleRoster =
                new CommandActivationEvidence(
                        5L,
                        fixture.roster(List.of()),
                        null,
                        null,
                        null,
                        fixture.SLOT,
                        fixture.FAMILY,
                        "bonded",
                        true,
                        null,
                        fixture.TIMED_POLICY
                );
        assertFailure(
                fixture.withCommand(staleRoster),
                "capture_tame_roster_revision_stale"
        );

        PopulationGroupAssignment staleAssignment =
                fixture.currentAssignment(
                        3L, fixture.lifecycle().revision()
                );
        PopulationGroupEvidence staleGroups =
                new PopulationGroupEvidence(
                        staleAssignment,
                        fixture.POLICY_REVISION,
                        List.of(
                                fixture.GLOBAL_POLICY,
                                fixture.WORLD_POLICY
                        ),
                        fixture.baseGroups().counts()
                );
        assertFailure(
                fixture.withGroups(staleGroups),
                "capture_tame_group_assignment_stale"
        );
    }

    private void assertFailure(
            SpawnerTameAndLinkEvidenceInput input,
            String detail
    ) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> author.author(input)
        );
        assertEquals(detail, failure.getMessage());
    }
}
