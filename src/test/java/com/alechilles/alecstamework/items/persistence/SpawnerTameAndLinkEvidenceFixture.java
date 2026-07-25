package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureCommandAccessEvidence;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.CommandActivationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationCountEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupCountEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.TargetEvidence;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic inputs shared by focused tame/link authoring tests. */
final class SpawnerTameAndLinkEvidenceFixture {
    static final ProfileId PROFILE = ProfileId.parse(
            "91000000-0000-0000-0000-000000000001"
    );
    static final ProfileId OTHER_PROFILE = ProfileId.parse(
            "91000000-0000-0000-0000-000000000002"
    );
    static final OwnerId OWNER = new OwnerId(UUID.fromString(
            "91000000-0000-0000-0000-000000000003"
    ));
    static final OperationId OPERATION = new OperationId(
            UUID.fromString("91000000-0000-0000-0000-000000000004")
    );
    static final CommandRosterSlotId SLOT = CommandRosterSlotId.parse(
            "91000000-0000-0000-0000-000000000005"
    );
    static final CommandRosterSlotId OTHER_SLOT =
            CommandRosterSlotId.parse(
                    "91000000-0000-0000-0000-000000000006"
            );
    static final long REQUESTED_AT = -500L;
    static final long POLICY_REVISION = 12L;
    static final String WORLD = "flat_world";
    static final String ALIAS =
            "91000000-0000-0000-0000-000000000007";
    static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "dragon_command");
    static final CaptureCommandAccessEvidence ACCESS =
            new CaptureCommandAccessEvidence(
                    "miniwyvern-command",
                    9L,
                    FAMILY.familyId(),
                    List.of("miniwyvern_whistle")
            );
    static final PopulationGroupPolicy GLOBAL_POLICY =
            new PopulationGroupPolicy(
                    "bonded", PopulationGroupScope.GLOBAL,
                    4, 3, POLICY_REVISION
            );
    static final PopulationGroupPolicy WORLD_POLICY =
            new PopulationGroupPolicy(
                    "wyvern", PopulationGroupScope.PER_WORLD,
                    2, 2, POLICY_REVISION
            );
    static final TimedSummonPolicy TIMED_POLICY =
            new TimedSummonPolicy(
                    "miniwyvern-timed",
                    8L,
                    600_000L,
                    30_000L,
                    true,
                    List.of(60_000L, 10_000L)
            );

    SpawnerTameAndLinkEvidenceInput baseInput() {
        return new SpawnerTameAndLinkEvidenceInput(
                OPERATION,
                REQUESTED_AT,
                identity(),
                lifecycle(),
                baseIntentEvidence()
        );
    }

    SpawnerTameAndLinkIntentEvidence baseIntentEvidence() {
        return new SpawnerTameAndLinkIntentEvidence(
                new TargetEvidence(
                        OWNER,
                        "Alec",
                        "tamed_miniwyvern",
                        "{\"tamed\":true}",
                        Sha256Hash.ofUtf8("wild-live"),
                        Sha256Hash.ofUtf8("tamed-live"),
                        ACCESS
                ),
                baseOwnerPopulation(),
                baseGroups(),
                command(null, null, null, null)
        );
    }

    CompanionIdentity identity() {
        return new CompanionIdentity(
                PROFILE,
                "Wild wyvern",
                "wild_miniwyvern",
                "{}",
                Sha256Hash.ofUtf8("{}"),
                WORLD,
                -2_000L,
                -1_000L,
                -900L,
                4L
        );
    }

    CompanionLifecycle lifecycle() {
        return new CompanionLifecycle(
                PROFILE,
                null,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(ALIAS, WORLD),
                new LifecycleRevision(7L),
                null,
                -800L,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
    }

    OwnerPopulationEvidence baseOwnerPopulation() {
        return new OwnerPopulationEvidence(
                4,
                2,
                List.of(
                        ownerCount(
                                OwnerPopulationScope.global(OWNER), 1, 1
                        ),
                        ownerCount(
                                OwnerPopulationScope.perWorld(OWNER, WORLD),
                                0, 1
                        )
                )
        );
    }

    PopulationGroupEvidence baseGroups() {
        return new PopulationGroupEvidence(
                null,
                POLICY_REVISION,
                List.of(WORLD_POLICY, GLOBAL_POLICY),
                List.of(
                        groupCount(
                                globalBucket(),
                                new PopulationGroupCounts(1, 1, 1, 0)
                        ),
                        groupCount(
                                worldBucket(),
                                new PopulationGroupCounts(0, 0, 1, 0)
                        )
                )
        );
    }

    CommandActivationEvidence command(
            CommandRoster currentRoster,
            CommandRosterMembership existingProfile,
            CommandRosterMembership existingSlot,
            TimedSummonLease existingLease
    ) {
        return new CommandActivationEvidence(
                currentRoster == null ? 0L : currentRoster.rosterRevision(),
                currentRoster,
                existingProfile,
                existingSlot,
                existingLease,
                SLOT,
                FAMILY,
                "bonded",
                true,
                null,
                TIMED_POLICY
        );
    }

    CommandRoster roster(List<CommandRosterMembership> memberships) {
        return new CommandRoster(
                FAMILY, 4L, memberships, -2_000L, -1_000L
        );
    }

    CommandRosterMembership membership(
            ProfileId profileId,
            CommandRosterSlotId slotId
    ) {
        return new CommandRosterMembership(
                slotId,
                FAMILY,
                profileId,
                1L,
                null,
                true,
                null,
                -2_000L,
                -1_000L
        );
    }

    TimedSummonLease dormantLease() {
        return new TimedSummonLease(
                PROFILE,
                1L,
                null,
                null,
                null,
                TIMED_POLICY,
                Set.of(),
                null,
                -2_000L,
                -1_000L
        );
    }

    PopulationGroupAssignment currentAssignment(
            long metadataRevision,
            LifecycleRevision lifecycleRevision
    ) {
        return new PopulationGroupAssignment(
                PROFILE,
                "wild_miniwyvern",
                List.of(),
                POLICY_REVISION,
                metadataRevision,
                lifecycleRevision,
                1L,
                -700L
        );
    }

    PopulationGroupBucket globalBucket() {
        return new PopulationGroupBucket(
                OWNER, "bonded", PopulationGroupScope.GLOBAL, null
        );
    }

    PopulationGroupBucket worldBucket() {
        return new PopulationGroupBucket(
                OWNER, "wyvern", PopulationGroupScope.PER_WORLD, WORLD
        );
    }

    OwnerPopulationCountEvidence ownerCount(
            OwnerPopulationScope scope,
            long committed,
            long pending
    ) {
        return new OwnerPopulationCountEvidence(
                scope, committed, pending
        );
    }

    PopulationGroupCountEvidence groupCount(
            PopulationGroupBucket bucket,
            PopulationGroupCounts counts
    ) {
        return new PopulationGroupCountEvidence(bucket, counts);
    }

    SpawnerTameAndLinkEvidenceInput withLifecycle(
            CompanionLifecycle changed
    ) {
        return new SpawnerTameAndLinkEvidenceInput(
                OPERATION,
                REQUESTED_AT,
                identity(),
                changed,
                baseIntentEvidence()
        );
    }

    SpawnerTameAndLinkEvidenceInput withOwnerPopulation(
            OwnerPopulationEvidence ownerPopulation
    ) {
        SpawnerTameAndLinkIntentEvidence base = baseIntentEvidence();
        return withIntent(new SpawnerTameAndLinkIntentEvidence(
                base.target(), ownerPopulation, base.groups(), base.command()
        ));
    }

    SpawnerTameAndLinkEvidenceInput withGroups(
            PopulationGroupEvidence groups
    ) {
        SpawnerTameAndLinkIntentEvidence base = baseIntentEvidence();
        return withIntent(new SpawnerTameAndLinkIntentEvidence(
                base.target(), base.ownerPopulation(), groups, base.command()
        ));
    }

    SpawnerTameAndLinkEvidenceInput withCommand(
            CommandActivationEvidence command
    ) {
        SpawnerTameAndLinkIntentEvidence base = baseIntentEvidence();
        return withIntent(new SpawnerTameAndLinkIntentEvidence(
                base.target(), base.ownerPopulation(), base.groups(), command
        ));
    }

    private SpawnerTameAndLinkEvidenceInput withIntent(
            SpawnerTameAndLinkIntentEvidence intent
    ) {
        return new SpawnerTameAndLinkEvidenceInput(
                OPERATION, REQUESTED_AT, identity(), lifecycle(), intent
        );
    }
}
