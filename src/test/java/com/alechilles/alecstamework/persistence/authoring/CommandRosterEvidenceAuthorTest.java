package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Canonical slot and role-context regressions for roster evidence authoring. */
class CommandRosterEvidenceAuthorTest {
    private static final UUID OWNER_UUID = UUID.fromString(
            "82000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = new OwnerId(OWNER_UUID);
    private static final ProfileId PROFILE = ProfileId.parse(
            "82000000-0000-0000-0000-000000000002"
    );
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "primary");
    private static final CommandRosterSlotId EXISTING_SLOT =
            CommandRosterSlotId.parse(
                    "82000000-0000-0000-0000-000000000003"
            );
    private static final CommandRosterSlotId CANDIDATE_SLOT =
            CommandRosterSlotId.parse(
                    "82000000-0000-0000-0000-000000000004"
            );

    @Test
    void existingUpsertAndRemoveAlwaysRetainCanonicalSlot() {
        CompanionProfileReadModel profile = profile();
        CommandRosterMembership membership = membership();
        CommandRoster roster = new CommandRoster(
                FAMILY, 7, List.of(membership), -1_000, -800
        );
        RecordingLiveEvidence live = new RecordingLiveEvidence();
        CommandRosterEvidenceAuthor author =
                new CommandRosterEvidenceAuthor(
                        new StubQueries(profile, roster, membership),
                        live
                );

        for (CommandRosterMembershipRequest.Action action
                : CommandRosterMembershipRequest.Action.values()) {
            var prepared = author.prepare(
                    request(action), action
            ).toCompletableFuture().join();

            assertNotNull(prepared);
            assertEquals(EXISTING_SLOT, prepared.request().slotId());
            assertEquals(
                    membership.membershipRevision(),
                    prepared.request().expectedMembershipRevision()
            );
            assertNotNull(prepared.previousMembership());
            assertEquals("test-role", live.intent.expectedRoleId());
            assertSame(action, live.intent.action());
        }
    }

    private CommandFamilyRosterMutationRequest request(
            CommandRosterMembershipRequest.Action action
    ) {
        return new CommandFamilyRosterMutationRequest(
                "test",
                "existing-" + action.name().toLowerCase(),
                null,
                OWNER_UUID,
                FAMILY.familyId(),
                PROFILE.toString(),
                "test-config",
                "test-item",
                CommandFamilyRosterMemberState.ACTIVE,
                "companions",
                true,
                null,
                7,
                3
        );
    }

    private CompanionProfileReadModel profile() {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Test",
                "test-role",
                null,
                null,
                "world",
                -1_000,
                -900,
                -800,
                3
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        "82000000-0000-0000-0000-000000000005",
                        "world"
                ),
                new LifecycleRevision(2),
                null,
                -700,
                ReconciliationGeneration.INITIAL,
                null,
                "world"
        );
        return new CompanionProfileReadModel(
                identity, null, lifecycle, List.of(), List.of(), null
        );
    }

    private CommandRosterMembership membership() {
        return new CommandRosterMembership(
                EXISTING_SLOT,
                FAMILY,
                PROFILE,
                4,
                "companions",
                true,
                null,
                -1_000,
                -800
        );
    }

    private static final class RecordingLiveEvidence
            implements ReplacementFeatureLiveEvidenceSource {
        private RosterAccessIntent intent;

        @Override
        public CompletionStage<RosterAccess> freezeRosterAccess(
                RosterAccessIntent intent
        ) {
            this.intent = intent;
            return CompletableFuture.completedFuture(new RosterAccess(
                    OWNER_UUID,
                    FAMILY.familyId(),
                    "test-config",
                    "test-item",
                    CANDIDATE_SLOT,
                    -600
            ));
        }

        @Override
        public CompletionStage<TimedWorldEvidence> freezeTimedWorld(
                TimedWorldIntent intent
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<ProvisioningWorldEvidence>
        freezeProvisioningWorld(ProvisioningWorldIntent intent) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<PaidInventoryEvidence> freezePaidInventory(
                PaidInventoryIntent intent
        ) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class StubQueries
            implements ReplacementFeatureEvidenceQueries {
        private final CompanionProfileReadModel profile;
        private final CommandRoster roster;
        private final CommandRosterMembership membership;

        private StubQueries(
                CompanionProfileReadModel profile,
                CommandRoster roster,
                CommandRosterMembership membership
        ) {
            this.profile = profile;
            this.roster = roster;
            this.membership = membership;
        }

        @Override
        public CompletionStage<PersistenceReadResult<
                CompanionProfileReadModel>> findProfile(ProfileId ignored) {
            return found(profile);
        }

        @Override
        public CompletionStage<PersistenceReadResult<CommandRoster>>
        findRoster(CommandFamilyKey ignored) {
            return found(roster);
        }

        @Override
        public CompletionStage<PersistenceReadResult<
                CommandRosterMembership>> findMembership(ProfileId ignored) {
            return found(membership);
        }

        @Override
        public CompletionStage<PersistenceReadResult<TimedSummonLease>>
        findTimedLease(ProfileId ignored) {
            return absent();
        }

        @Override
        public CompletionStage<PersistenceReadResult<ProvisioningRecord>>
        findProvisioning(ProfileId ignored) {
            return absent();
        }

        @Override
        public CompletionStage<PersistenceReadResult<
                List<PopulationGroupAssignment>>>
        findPopulationAssignments() {
            return absent();
        }

        @Override
        public CompletionStage<PersistenceReadResult<
                PublicOperationEvidence>> findOperation(
                OperationKind ignoredKind,
                IdempotencyKey ignoredKey
        ) {
            return absent();
        }

        private <T> CompletionStage<PersistenceReadResult<T>> found(T value) {
            return CompletableFuture.completedFuture(
                    PersistenceReadResult.found(value, 0)
            );
        }

        private <T> CompletionStage<PersistenceReadResult<T>> absent() {
            return CompletableFuture.completedFuture(
                    PersistenceReadResult.absent()
            );
        }
    }
}
