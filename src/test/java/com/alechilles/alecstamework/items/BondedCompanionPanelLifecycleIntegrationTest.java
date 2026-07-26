package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.*;
import com.alechilles.alecstamework.companion.bonded.*;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.persistence.adapter.sqlite.*;
import com.alechilles.alecstamework.persistence.bonded.*;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite/core lifecycle coverage for the profile-first panel boundary. */
class BondedCompanionPanelLifecycleIntegrationTest {
    private static final UUID OWNER = UUID.fromString(
            "73000000-0000-0000-0000-000000000001");
    private static final String ROSTER = "hydragon:dragons";
    private static final String ROLE = "Bonded_Miniwyvern_Storm";

    @TempDir Path temporaryDirectory;

    @Test
    void capturedProfileSurvivesRelogAndPanelActionsCompleteFullLifecycle()
            throws Exception {
        Path database = temporaryDirectory.resolve("bonded.db");
        BondedCompanionRosterRegistry rosters = registry();
        AtomicLong clock = new AtomicLong(10_000L);
        TestWorld world = new TestWorld();
        Harness captureRuntime = harness(database, rosters, clock, world);
        BondedCompanionCaptureIntent capture = captureIntent(
                UUID.fromString("73000000-0000-0000-0000-000000000002"),
                "Nimbus", "Miniwyvern");

        assertEquals(BondedCompanionCaptureAuthor.PersistenceOutcome.APPLIED,
                captureRuntime.capture.store(capture));
        captureRuntime.close();

        Harness runtime = harness(database, rosters, clock, world);
        BondedCompanionProfileView stored = runtime.api.list(OWNER, ROSTER)
                .join().value().getFirst();
        assertEquals("Miniwyvern", stored.species());
        assertEquals("Miniwyvern Storm",
                stored.snapshotPresentationData().get("rolePresentation"));
        assertFalse(stored.species().contains("Bonded_"));

        TestInventory inventory = new TestInventory(
                Map.of("Ingredient_Life_Essence", 2));
        BondedCompanionActionContext context = new BondedCompanionActionContext(
                new CompanionSpawnPlacement(
                        "world-a", 10D, 64D, 12D, 0F, 1F, 0F), inventory);
        BondedCompanionPanelActionService actions =
                new BondedCompanionPanelActionService(() -> runtime.api);
        var storedRow = BondedCompanionPanelFeaturePresentationSource.presentation(
                stored, clock.get(), null, context, "world-a");

        assertTrue(actions.perform(
                BondedCompanionPanelActionService.Action.SUMMON, OWNER,
                "world-a", context, storedRow).applied());
        assertEquals(context.summonPlacement(), world.lastPlacement);

        BondedCompanionProfileView active = runtime.api.list(OWNER, ROSTER)
                .join().value().getFirst();
        var activeRow = BondedCompanionPanelFeaturePresentationSource.presentation(
                active, clock.get(), null, context, "world-a");
        assertTrue(actions.perform(
                BondedCompanionPanelActionService.Action.STORE, OWNER,
                "world-a", context, activeRow).applied());

        BondedCompanionProfileView storedAgain = runtime.api.list(OWNER, ROSTER)
                .join().value().getFirst();
        var storedAgainRow = BondedCompanionPanelFeaturePresentationSource.presentation(
                storedAgain, clock.get(), null, context, "world-a");
        assertTrue(actions.perform(
                BondedCompanionPanelActionService.Action.SUMMON, OWNER,
                "world-a", context, storedAgainRow).applied());

        var lease = runtime.durability.activeLeases(8).getFirst();
        assertEquals(BondedCompanionProjectionService.ReconcileStatus.DEAD,
                runtime.projections.confirmDeath(
                        lease, world.readExact(lease), clock.incrementAndGet())
                        .status());
        BondedCompanionProfileView dead = runtime.api.list(OWNER, ROSTER)
                .join().value().getFirst();
        BondedCompanionReviveQuote quote = runtime.api.quoteRevive(action(
                "quote", dead, context)).join().value();
        assertTrue(quote.affordable());

        var deadRow = BondedCompanionPanelFeaturePresentationSource.presentation(
                dead, clock.get(), quote, context, "world-a");
        assertTrue(actions.perform(
                BondedCompanionPanelActionService.Action.REVIVE, OWNER,
                "world-a", context, deadRow).applied());
        assertEquals(0, inventory.availableQuantity(
                "Ingredient_Life_Essence"));
        assertEquals(BondedCompanionState.STORED,
                runtime.api.list(OWNER, ROSTER).join().value().getFirst().state());
        runtime.close();
    }

    @Test
    void policyCapacityAndLeaseWorldDisablePanelActions() throws Exception {
        Path database = temporaryDirectory.resolve("capacity.db");
        BondedCompanionRosterRegistry rosters = registry();
        AtomicLong clock = new AtomicLong(20_000L);
        TestWorld world = new TestWorld();
        Harness runtime = harness(database, rosters, clock, world);
        runtime.capture.store(captureIntent(
                UUID.fromString("73000000-0000-0000-0000-000000000003"),
                "Nimbus", "Miniwyvern"));
        runtime.capture.store(captureIntent(
                UUID.fromString("73000000-0000-0000-0000-000000000004"),
                "Cirrus", "Miniwyvern"));
        List<BondedCompanionProfileView> profiles = runtime.api
                .list(OWNER, ROSTER).join().value();
        BondedCompanionActionContext context = new BondedCompanionActionContext(
                new CompanionSpawnPlacement(
                        "world-a", 1D, 2D, 3D, 0F, 0F, 0F),
                new TestInventory(Map.of()));
        BondedCompanionProfileView first = profiles.getFirst();
        assertTrue(runtime.api.summon(action("summon", first, context))
                .join().successful());

        List<BondedCompanionProfileView> after = runtime.api
                .list(OWNER, ROSTER).join().value();
        BondedCompanionProfileView stillStored = after.stream()
                .filter(value -> value.state() == BondedCompanionState.STORED)
                .findFirst().orElseThrow();
        BondedCompanionProfileView active = after.stream()
                .filter(value -> value.state() == BondedCompanionState.ACTIVE)
                .findFirst().orElseThrow();
        assertFalse(stillStored.summonAvailable());
        assertFalse(BondedCompanionPanelFeaturePresentationSource.presentation(
                active, clock.get(), null, context, "world-b")
                .status().actionEnabled());
        runtime.close();
    }

    private Harness harness(Path database, BondedCompanionRosterRegistry rosters,
                            AtomicLong clock, TestWorld world) {
        assertTrue(new BondedCompanionSchemaManager(database, clock::get)
                .initialize().availability().available());
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(database);
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(database);
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        BondedCompanionTransitionService transitions =
                new BondedCompanionTransitionService(policies);
        BondedCompanionProjectionCleanupService cleanup =
                new BondedCompanionProjectionCleanupService(world);
        BondedCompanionProjectionService projections =
                new BondedCompanionProjectionService(
                        durability, world, cleanup,
                        () -> UUID.randomUUID().toString(), UUID::randomUUID);
        BondedCompanionChangePublisher changes =
                new BondedCompanionChangePublisher(null);
        BondedCompanionDiagnosticContributor diagnostics =
                new BondedCompanionDiagnosticContributor(
                        BondedCompanionPersistenceReadiness::ready,
                        store::diagnostics, BondedCompanionSchemaManager.VERSION);
        BondedCompanionCoreApiOperations operations =
                new BondedCompanionCoreApiOperations(
                        store, rosters, policies, transitions, projections,
                        changes, diagnostics, clock::get);
        BondedCompanionApiFacade api = new BondedCompanionApiFacade(
                BondedCompanionPersistenceReadiness::ready,
                store, changes, diagnostics, operations);
        SqliteBondedCompanionCapturePersistenceAdapter capture =
                new SqliteBondedCompanionCapturePersistenceAdapter(
                        rosters, transitions, store, store, durability, cleanup);
        return new Harness(api, capture, durability, projections, changes);
    }

    private BondedCompanionCaptureIntent captureIntent(
            UUID source, String name, String species) {
        TameworkLifeStageComponent life = new TameworkLifeStageComponent();
        life.setGender("Female");
        BondedCompanionSnapshot snapshot = BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        source, null, -1, ROLE, null, null, null,
                        new TameworkNpcNameComponent(
                                name, OWNER, 1L,
                                TameworkNpcNameComponent.NameSource.Player),
                        null, null, null, null, null, null, life, null,
                        75D, 10_000L), Map.of());
        return new BondedCompanionCaptureIntent(
                "test-capture", source.toString(), OWNER, "world-a", 0,
                "fingerprint-" + source, source, ROLE, species, ROSTER, 1L,
                snapshot, null, true, true, true, true, true, true);
    }

    private BondedCompanionActionRequest action(
            String key, BondedCompanionProfileView profile,
            BondedCompanionActionContext context) {
        return new BondedCompanionActionRequest(
                "test-panel", key + ":" + profile.revision(), OWNER,
                ROSTER, profile.profileId(), profile.revision(), "world-a",
                context);
    }

    private BondedCompanionRosterRegistry registry() throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:dragons",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["Bonded_Miniwyvern_Storm"],
                                  "MaximumOwned": 4,
                                  "MaximumActive": 1,
                                  "SessionDurationSeconds": 600,
                                  "SummonCooldownSeconds": 0,
                                  "RevivePrice": {
                                    "ItemId": "Ingredient_Life_Essence",
                                    "Quantity": 2
                                  },
                                  "Features": {
                                    "Capture": true,
                                    "Provision": true,
                                    "Summon": true,
                                    "Dismiss": true,
                                    "Revive": true
                                  }
                                }
                                """), new ExtraInfo());
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "HydragonDragons");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(config), 1L).applied());
        return registry;
    }

    private record Harness(
            BondedCompanionApiFacade api,
            SqliteBondedCompanionCapturePersistenceAdapter capture,
            SqliteBondedCompanionProjectionDurability durability,
            BondedCompanionProjectionService projections,
            BondedCompanionChangePublisher changes) {
        void close() { api.close(); changes.close(); }
    }

    private static final class TestWorld implements
            BondedCompanionProjectionService.World,
            BondedCompanionProjectionCleanupService.WorldGateway {
        private final Map<UUID, BondedCompanionProjectionValidator.Projection>
                projections = new HashMap<>();
        private CompanionSpawnPlacement lastPlacement;

        @Override
        public BondedCompanionProjectionService.SpawnResult spawn(
                BondedCompanionProjectionService.SpawnPlan plan) {
            lastPlacement = plan.placement();
            UUID uuid = plan.lease().liveNpcUuid();
            projections.put(uuid, new BondedCompanionProjectionValidator.Projection(
                    uuid, plan.lease().worldKey(), plan.marker(), plan.snapshot()));
            return BondedCompanionProjectionService.SpawnResult.spawned(uuid);
        }

        @Override
        public BondedCompanionProjectionValidator.Projection readExact(
                BondedCompanionProjectionValidator.LeaseExpectation lease) {
            return projections.get(lease.liveNpcUuid());
        }

        @Override
        public BondedCompanionProjectionCleanupService.Outcome removeIfExact(
                BondedCompanionProjectionCleanupService.CleanupIntent intent) {
            return projections.remove(intent.targetNpcUuid()) == null
                    ? BondedCompanionProjectionCleanupService.Outcome.ALREADY_MISSING
                    : BondedCompanionProjectionCleanupService.Outcome.REMOVED;
        }
    }

    private static final class TestInventory implements
            BondedCompanionActionContext.Inventory {
        private final Map<String, Integer> quantities;

        private TestInventory(Map<String, Integer> quantities) {
            this.quantities = new HashMap<>(quantities);
        }

        @Override public int availableQuantity(String itemId) {
            return quantities.getOrDefault(itemId, 0);
        }

        @Override public boolean consumeExact(String itemId, int quantity) {
            int available = availableQuantity(itemId);
            if (available < quantity) return false;
            quantities.put(itemId, available - quantity);
            return true;
        }
    }
}
