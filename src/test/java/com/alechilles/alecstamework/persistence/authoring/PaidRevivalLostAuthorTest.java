package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence
        .TameworkDormantSnapshotFactsReader;
import com.alechilles.alecstamework.items.persistence
        .TameworkRestorationSnapshotResolver;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/** LOST quote and commit evidence regressions for paid revival authors. */
class PaidRevivalLostAuthorTest {
    private static final UUID OWNER_UUID = UUID.fromString(
            "81000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = new OwnerId(OWNER_UUID);
    private static final ProfileId PROFILE = ProfileId.parse(
            "81000000-0000-0000-0000-000000000002"
    );
    private static final NpcAlias SOURCE = NpcAlias.parse(
            "81000000-0000-0000-0000-000000000003"
    );
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "primary");
    private static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "81000000-0000-0000-0000-000000000004"
            );
    private static final LifecycleRevision REVISION =
            new LifecycleRevision(5);
    private static final long NOW = -400L;

    @Test
    void lostQuoteUsesExactFiveFieldCostViewWithoutDeathCooldown() {
        Fixture fixture = fixture(
                List.of(new RevivalCostItem("life-essence", 3)),
                List.of(
                        new ReplacementFeatureLiveEvidenceSource
                                .PaidCostAvailability(
                                "life-essence",
                                1,
                                " Life Essence ",
                                " icons/life "
                        )
                )
        );
        var quote = fixture.quotes().quote(
                new PaidCommandRevivalQuoteRequest(
                        OWNER_UUID, PROFILE.toString(), FAMILY.familyId()
                )
        ).toCompletableFuture().join();

        assertNotNull(quote);
        assertEquals(
                com.alechilles.alecstamework.api.PaidCommandRevivalQuote
                        .Status.INSUFFICIENT_COST,
                quote.status()
        );
        assertEquals(0L, quote.cooldownRemainingMs());
        assertEquals(1, quote.costs().size());
        var cost = quote.costs().get(0);
        assertEquals("life-essence", cost.itemId());
        assertEquals(3, cost.requiredQuantity());
        assertEquals(1, cost.ownedQuantity());
        assertEquals("Life Essence", cost.localizedName());
        assertEquals("icons/life", cost.iconAssetId());
    }

    @Test
    void lostCommitAuthorsOneValidExistingPaidRevivalRequest() {
        Fixture fixture = fixture(List.of(), List.of());
        var prepared = fixture.requests().prepare(
                new PaidCommandRevivalRequest(
                        "test",
                        "lost-revival",
                        OWNER_UUID,
                        PROFILE.toString(),
                        FAMILY.familyId()
                )
        ).toCompletableFuture().join();

        assertNotNull(prepared);
        assertEquals(
                LifecycleState.LOST,
                prepared.request().groupAdmission().before().state()
        );
        assertEquals(
                TameworkSnapshotCodecs.LOST,
                prepared.request().sourceSnapshot().kind()
        );
        assertEquals(LifecycleState.ACTIVE,
                prepared.request().groupAdmission().after().state());
        assertNull(prepared.request().timedActivation());
        assertEquals(NOW, prepared.request().requestedAtMs());
    }

    @Test
    void computedDeathDeadlineAtZeroIsNotTheLostSentinel() {
        var death = new TameworkDormantSnapshotFactsReader.Facts(
                LifecycleState.DEAD_REVIVABLE,
                -10,
                0,
                null,
                null
        );
        var lost = new TameworkDormantSnapshotFactsReader.Facts(
                LifecycleState.LOST,
                -10,
                0,
                null,
                null
        );

        assertEquals(0L, PaidRevivalDormantSource.availableAt(death, 10));
        assertNull(PaidRevivalDormantSource.availableAt(lost, 10));
    }

    private Fixture fixture(
            List<RevivalCostItem> costs,
            List<ReplacementFeatureLiveEvidenceSource.PaidCostAvailability>
                    available
    ) {
        SnapshotCodecRegistry codecs = TameworkSnapshotCodecs.create();
        CompanionSnapshot source = lostSnapshot(codecs);
        CompanionProfileReadModel profile = profile(source);
        CommandRosterMembership membership = new CommandRosterMembership(
                SLOT,
                FAMILY,
                PROFILE,
                1,
                null,
                true,
                null,
                -1_000,
                -900
        );
        PopulationGroupAssignment assignment =
                new PopulationGroupAssignment(
                        PROFILE,
                        "test-role",
                        List.of(),
                        0,
                        0,
                        REVISION,
                        1,
                        -800
                );
        StubQueries queries = new StubQueries(
                profile, membership, assignment
        );
        var policy = new ReplacementFeaturePolicySource.RolePolicySnapshot(
                "test-role",
                "test-config",
                "revision",
                0,
                0,
                false,
                new TimedSummonPolicy(
                        null, null, 0, 0, false, List.of()
                ),
                true,
                600_000,
                costs,
                "insufficient"
        );
        ReplacementFeaturePolicySource policies = ignored -> policy;
        StubLiveEvidence live = new StubLiveEvidence(available);
        return new Fixture(
                new PaidRevivalQuoteAuthor(
                        queries,
                        policies,
                        live,
                        new TameworkDormantSnapshotFactsReader(codecs)
                ),
                new PaidRevivalRequestAuthor(
                        queries,
                        new PopulationGroupConfigRegistry(),
                        policies,
                        live,
                        new TameworkDormantSnapshotFactsReader(codecs),
                        new TameworkRestorationSnapshotResolver(codecs)
                )
        );
    }

    private CompanionSnapshot lostSnapshot(SnapshotCodecRegistry codecs) {
        SnapshotCodecRegistry.EncodedSnapshot encoded = codecs.encode(
                TameworkSnapshotCodecs.LOST,
                2,
                CoopResidentStateSnapshot.class,
                new CoopResidentStateSnapshot(
                        SOURCE.value(),
                        null,
                        -1,
                        "test-role",
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
                        1.0,
                        -600
                )
        );
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                REVISION,
                true,
                -600
        );
    }

    private CompanionProfileReadModel profile(CompanionSnapshot source) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Lost companion",
                "test-role",
                null,
                null,
                "world",
                -1_000,
                -900,
                -800,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.LOST,
                LifecycleLocation.none(),
                REVISION,
                null,
                -600,
                ReconciliationGeneration.INITIAL,
                null,
                "world"
        );
        return new CompanionProfileReadModel(
                identity, null, lifecycle, List.of(), List.of(source), null
        );
    }

    private record Fixture(
            PaidRevivalQuoteAuthor quotes,
            PaidRevivalRequestAuthor requests
    ) {
    }

    private static final class StubQueries
            implements ReplacementFeatureEvidenceQueries {
        private final CompanionProfileReadModel profile;
        private final CommandRosterMembership membership;
        private final PopulationGroupAssignment assignment;

        private StubQueries(
                CompanionProfileReadModel profile,
                CommandRosterMembership membership,
                PopulationGroupAssignment assignment
        ) {
            this.profile = profile;
            this.membership = membership;
            this.assignment = assignment;
        }

        @Override
        public CompletionStage<PersistenceReadResult<
                CompanionProfileReadModel>> findProfile(ProfileId ignored) {
            return found(profile);
        }

        @Override
        public CompletionStage<PersistenceReadResult<CommandRoster>>
        findRoster(CommandFamilyKey ignored) {
            return absent();
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
            return found(List.of(assignment));
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

    private static final class StubLiveEvidence
            implements ReplacementFeatureLiveEvidenceSource {
        private final List<PaidCostAvailability> costs;

        private StubLiveEvidence(List<PaidCostAvailability> costs) {
            this.costs = List.copyOf(costs);
        }

        @Override
        public CompletionStage<RosterAccess> freezeRosterAccess(
                RosterAccessIntent intent
        ) {
            return CompletableFuture.completedFuture(null);
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
            return CompletableFuture.completedFuture(
                    new PaidInventoryEvidence(
                            OWNER_UUID,
                            costs,
                            List.of(),
                            new CompanionSpawnPlacement(
                                    "world", 1, 2, 3, 0, 0, 0
                            ),
                            NOW
                    )
            );
        }
    }
}
