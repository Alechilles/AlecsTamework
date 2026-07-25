package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureTameAndLinkTestFixtures;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.CommandActivationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationCountEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupCountEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.TargetEvidence;
import java.util.List;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Intent-to-freezer-to-request coverage for the tame/link terminal disposition. */
class SpawnerTameAndLinkCapturePipelineTest {

    @Test
    void canonicalProfileReadAuthorsTameAndLinkWithoutAnArtifactSnapshot() {
        CaptureTameAndLinkEvidence donor =
                CaptureTameAndLinkTestFixtures.evidence();
        SpawnerTameAndLinkIntentEvidence intentEvidence =
                intentEvidence(donor);
        SpawnerCaptureIntent intent = intent(donor, intentEvidence);
        SpawnerCaptureEvidenceFreezer.FrozenCapture frozen =
                freezer().freeze(intent);

        var request = new SpawnerCaptureRequestFactory().create(
                intent.frozenContext(),
                frozen,
                profile(donor)
        );

        assertTrue(request.tameAndCommandLink());
        assertEquals(
                CaptureTameAndLinkTestFixtures.PROFILE,
                request.profileId()
        );
        assertEquals(
                CaptureTameAndLinkTestFixtures.OWNER,
                request.resultingOwnerId()
        );
        assertNull(frozen.snapshotId());
        assertNull(frozen.encoded());
        assertNull(frozen.artifact());
        assertEquals(intentEvidence, frozen.tameAndLinkEvidence());
        assertEquals(
                CaptureTameAndLinkTestFixtures.NOW,
                request.tameAndLinkEvidence()
                        .timedActivation().lease().checkpointedAtMs()
        );
        assertEquals(
                frozen.operationId().value(),
                request.tameAndLinkEvidence()
                        .timedActivation().lease().sessionId().value()
        );
    }

    /**
     * Protects the 2026-07-25 live failure where snapshot normalization had
     * already adopted a mixed-case role ID in lowercase before tame/link.
     */
    @Test
    void lowercaseAdoptedRoleUsesExactFrozenLiveRoleForOneSafeTransition() {
        CaptureTameAndLinkEvidence donor =
                CaptureTameAndLinkTestFixtures.evidence();
        SpawnerCaptureIntent intent = intent(
                donor, intentEvidence(donor)
        );
        SpawnerCaptureEvidenceFreezer.FrozenCapture frozen =
                freezer().freeze(intent);
        CompanionIdentity expected = donor.expectedIdentity();
        CompanionIdentity lowercase = new CompanionIdentity(
                expected.profileId(),
                expected.displayName(),
                expected.roleId().toLowerCase(java.util.Locale.ROOT),
                expected.metadataJson(),
                expected.metadataHash(),
                expected.lastKnownWorldKey(),
                expected.createdAtMs(),
                expected.updatedAtMs(),
                expected.lastActiveAtMs(),
                expected.metadataRevision()
        );

        var request = new SpawnerCaptureRequestFactory().create(
                intent.frozenContext(),
                frozen,
                profile(donor, lowercase)
        );

        assertEquals(
                "dragon_fire",
                request.tameAndLinkEvidence()
                        .expectedIdentity().roleId()
        );
        assertEquals(
                "Dragon_Fire",
                request.tameAndLinkEvidence().live().expectedRoleId()
        );
    }

    private SpawnerCaptureIntent intent(
            CaptureTameAndLinkEvidence donor,
            SpawnerTameAndLinkIntentEvidence intentEvidence
    ) {
        return new SpawnerCaptureIntent(
                CaptureTameAndLinkTestFixtures.ATTEMPT.toString(),
                CaptureTameAndLinkTestFixtures.OWNER.value(),
                "world",
                2,
                HytaleItemStackTestFixture.stack(
                        "HyDragon_Draconic_Stone",
                        new BsonDocument()
                ),
                null,
                null,
                null,
                CaptureTameAndLinkTestFixtures.PROFILE,
                CaptureTameAndLinkTestFixtures.ALIAS,
                null,
                CaptureTameAndLinkTestFixtures.OWNER,
                "Alec",
                donor.expectedIdentity().roleId(),
                CaptureTameAndLinkTestFixtures.resolution(),
                null,
                intentEvidence
        );
    }

    private SpawnerCaptureEvidenceFreezer freezer() {
        TameworkFullStateSnapshotReader snapshots =
                new TameworkFullStateSnapshotReader(
                        (reference, store, uuid, context) ->
                                new CoopResidentStateSnapshot(
                                        CaptureTameAndLinkTestFixtures.ALIAS
                                                .value(),
                                        null,
                                        -1,
                                        "Dragon_Fire",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        1.0D,
                                        -600L
                                )
                );
        return new SpawnerCaptureEvidenceFreezer(
                snapshots,
                new HytaleCapturedArtifactAdapter(
                        HytaleItemStackTestFixture::stack
                ),
                new SpawnerCaptureSnapshotMapper(),
                () -> CaptureTameAndLinkTestFixtures.NOW
        );
    }

    private CompanionProfileReadModel profile(
            CaptureTameAndLinkEvidence donor
    ) {
        return profile(donor, donor.expectedIdentity());
    }

    private CompanionProfileReadModel profile(
            CaptureTameAndLinkEvidence donor,
            CompanionIdentity identity
    ) {
        return new CompanionProfileReadModel(
                identity,
                new CompanionAlias(
                        CaptureTameAndLinkTestFixtures.ALIAS,
                        CaptureTameAndLinkTestFixtures.PROFILE,
                        1L,
                        CompanionAlias.State.CURRENT,
                        null,
                        -700L,
                        null
                ),
                donor.expectedLifecycle(),
                List.of(),
                List.of(),
                null
        );
    }

    private SpawnerTameAndLinkIntentEvidence intentEvidence(
            CaptureTameAndLinkEvidence donor
    ) {
        var live = donor.live();
        var owner = donor.finalLifecycle().ownerId();
        List<OwnerPopulationCountEvidence> ownerCounts =
                donor.ownerPopulation().increases().stream()
                        .map(increase ->
                                new OwnerPopulationCountEvidence(
                                        increase.scope(), 0, 0
                                ))
                        .toList();
        int globalLimit = donor.ownerPopulation().increases().stream()
                .filter(increase -> increase.scope().kind()
                        == OwnerPopulationScope.Kind.GLOBAL)
                .findFirst()
                .orElseThrow()
                .snapshottedLimit();
        int perWorldLimit = donor.ownerPopulation().increases().stream()
                .filter(increase -> increase.scope().kind()
                        == OwnerPopulationScope.Kind.PER_WORLD)
                .findFirst()
                .orElseThrow()
                .snapshottedLimit();
        List<PopulationGroupPolicy> policies =
                donor.populationGroups().targetPlan().reservations().stream()
                        .map(reservation -> new PopulationGroupPolicy(
                                reservation.bucket().groupId(),
                                reservation.bucket().scope(),
                                reservation.snapshottedMaxOwned(),
                                reservation.snapshottedMaxActive(),
                                reservation.policyRevision()
                        ))
                        .toList();
        List<PopulationGroupCountEvidence> groupCounts =
                donor.populationGroups().targetPlan().reservations().stream()
                        .map(reservation ->
                                new PopulationGroupCountEvidence(
                                        new PopulationGroupBucket(
                                                owner,
                                                reservation.bucket().groupId(),
                                                reservation.bucket().scope(),
                                                reservation.bucket()
                                                        .ownerWorldKey()
                                        ),
                                        new PopulationGroupCounts(
                                                0, 0, 0, 0
                                        )
                                ))
                        .toList();
        CommandRosterMembershipDraft membership =
                donor.rosterMembership();
        return new SpawnerTameAndLinkIntentEvidence(
                new TargetEvidence(
                        owner,
                        live.targetOwnerName(),
                        live.targetRoleId(),
                        donor.targetIdentity().metadataJson(),
                        live.expectedStateHash(),
                        live.targetStateHash(),
                        live.commandAccess()
                ),
                new OwnerPopulationEvidence(
                        globalLimit, perWorldLimit, ownerCounts
                ),
                new PopulationGroupEvidence(
                        donor.populationGroups().expectedAssignment(),
                        donor.populationGroups().targetPlan().target()
                                .policyRevision(),
                        policies,
                        groupCounts
                ),
                new CommandActivationEvidence(
                        donor.expectedRosterRevision(),
                        null,
                        null,
                        null,
                        null,
                        membership.slotId(),
                        membership.familyKey(),
                        membership.groupId(),
                        membership.activeForBulkCommands(),
                        membership.home(),
                        donor.timedActivation().lease().policy()
                )
        );
    }
}
