package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionWorld;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.diagnostics.BondedCompanionDiagnosticContributor;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers health normalization through the public bonded summon path. */
class BondedCompanionSummonHealthTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID NPC = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final String ROSTER = "test:roster";
    private static final String FAMILY = "family:wolf";
    private static final String ROLE = "role:wolf";
    private static final String PROFILE = "profile-a";

    @TempDir
    Path tempDir;

    @Test
    void publicSummonSpawnsStoredCompanionAtFullHealth() throws Exception {
        Path database = tempDir.resolve("summon-health.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -1_000L)
                .initialize().availability().available());
        var store = new SqliteBondedCompanionDatabase(database);
        BondedCompanionSnapshot injured = snapshot();
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(provisionOperation(), profile(injured)).code());

        BondedCompanionRosterRegistry rosters = rosterRegistry();
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        var durability = new SqliteBondedCompanionProjectionDurability(database);
        CapturingWorld world = new CapturingWorld();
        var cleanup = new BondedCompanionProjectionCleanupService(
                ignored -> BondedCompanionProjectionCleanupService.Outcome
                        .ALREADY_MISSING);
        var projections = new BondedCompanionProjectionService(
                new BondedCompanionStorePlanner(store, rosters), durability,
                world, cleanup, () -> "lease-a", () -> NPC);
        var changes = new BondedCompanionChangePublisher(null);
        var diagnostics = new BondedCompanionDiagnosticContributor(
                BondedCompanionPersistenceReadiness::ready,
                BondedCompanionStoreDiagnostics::empty,
                BondedCompanionSchemaManager.VERSION);
        var operations = new BondedCompanionCoreApiOperations(
                store, rosters, policies,
                new BondedCompanionTransitionService(policies), projections,
                changes, diagnostics, () -> -500L);
        var api = new BondedCompanionApiFacade(
                BondedCompanionPersistenceReadiness::ready,
                store, changes, diagnostics, operations);

        try {
            var result = api.summon(action()).join();

            assertEquals(BondedCompanionResultCode.SUCCESS, result.code());
            BondedCompanionProjectionService.SpawnPlan plan = world.plan.get();
            assertNotNull(plan);
            var health = plan.snapshot().fullState();
            assertEquals(100.0D, health.healthPercent());
            assertEquals(400.0D, health.currentHealth());
            assertEquals(400.0D, health.maximumHealth());
        } finally {
            api.close();
            changes.close();
        }
    }

    private BondedCompanionActionRequest action() {
        return new BondedCompanionActionRequest(
                "test", "summon-health", OWNER, ROSTER, PROFILE, 0L,
                "world-a", new BondedCompanionActionContext(
                new BondedCompanionPlacement(
                        "world-a", 0, 0, 0, 0, 0, 0), null));
    }

    private BondedCompanionRecord.Profile profile(
            BondedCompanionSnapshot snapshot
    ) {
        String encoded = new BondedCompanionSnapshotCodec().encode(snapshot);
        return new BondedCompanionRecord.Profile(
                PROFILE, OWNER, ROSTER, FAMILY, ROLE,
                BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of(encoded.getBytes(
                        StandardCharsets.UTF_8)),
                -1_000L, -1_000L, Map.of(), "Wolf", "Wolf", null,
                null, 0L, 0L, null, null);
    }

    private BondedCompanionSnapshot snapshot() {
        return BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        NPC, null, -1, ROLE, null,
                        new TameworkOwnerComponent(OWNER, "Owner"),
                        new TameworkTamedComponent(true), null, null, null,
                        null, null, null, null, null, null,
                        25.0D, 400.0D, 6.25D, -1_000L),
                Map.of());
    }

    private BondedCompanionOperation provisionOperation() {
        return new BondedCompanionOperation(
                "test", "provision", "a".repeat(64), OWNER, ROSTER,
                PROFILE, BondedCompanionOperation.Type.PROVISION,
                -1_000L, 10_000L);
    }

    private BondedCompanionRosterRegistry rosterRegistry() throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "%s",
                                  "FamilyId": "%s",
                                  "AllowedRoles": ["%s"],
                                  "MaximumOwned": 4,
                                  "MaximumActive": 1,
                                  "Features": {"Summon": true}
                                }
                                """.formatted(ROSTER, FAMILY, ROLE)),
                        new ExtraInfo());
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "WolfFamily");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        BondedCompanionRosterRegistry.ReloadResult loaded =
                registry.replace(List.of(config), 1L);
        assertTrue(loaded.applied(), loaded.error());
        return registry;
    }

    private static final class CapturingWorld
            implements BondedCompanionProjectionWorld {
        private final AtomicReference<BondedCompanionProjectionService.SpawnPlan>
                plan = new AtomicReference<>();

        @Override
        public BondedCompanionProjectionService.SpawnResult spawn(
                BondedCompanionProjectionService.SpawnPlan spawnPlan
        ) {
            plan.set(spawnPlan);
            return BondedCompanionProjectionService.SpawnResult.spawned(
                    spawnPlan.lease().liveNpcUuid());
        }

        @Override
        public BondedCompanionProjectionValidator.Projection readExact(
                BondedCompanionProjectionValidator.LeaseExpectation lease
        ) {
            return null;
        }
    }
}
