package com.alechilles.alecstamework.vessels.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.vessels.BondedVesselTransitionPlanner;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionBondedVesselTransitionPlannerTest {
    private static final UUID BINDING_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final long CONFIG_REVISION = 7L;

    @Test
    void summonFreezesActiveItemFingerprintCooldownAndPolicyRevision() {
        BondedVesselItemFingerprintCodec fingerprints = new BondedVesselItemFingerprintCodec();
        ProductionBondedVesselTransitionPlanner planner = planner(config(BondedVesselMode.BONDED), fingerprints);

        BondedVesselTransitionPlanner.Plan plan = planner.plan(
                binding(BondedVesselBindingRecord.LifecycleState.STORED, 0L),
                request(BondedVesselTransition.SUMMON), -5_000L);

        assertEquals(BondedVesselState.ACTIVE, plan.targetState());
        assertEquals(BondedVesselProjectionStatus.PRESENT, plan.targetProjectionStatus());
        assertEquals("Draconic_Stone_Active", plan.candidateItemId());
        assertEquals(5_000L, plan.targetCooldownUntilMs());
        assertEquals(fingerprints.fingerprint(new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        "Draconic_Stone_Active", BINDING_ID, "dragon-profile", 5L,
                        "TwSpawnerConfig_Draconic_Stone", BondedVesselState.ACTIVE)),
                plan.candidateItemFingerprint());
        assertTrue(plan.policySnapshotJson().contains("\"configRevision\":7"));
        assertTrue(plan.policySnapshotJson().contains("\"transition\":\"SUMMON\""));
        assertTrue(plan.policySnapshotJson().contains("\"allowStoreInCombat\":false"));
    }

    @Test
    void storeUsesStoredProjectionAndStartsSameConfiguredCooldown() {
        ProductionBondedVesselTransitionPlanner planner = planner(
                config(BondedVesselMode.BONDED), new BondedVesselItemFingerprintCodec());

        BondedVesselTransitionPlanner.Plan plan = planner.plan(
                binding(BondedVesselBindingRecord.LifecycleState.ACTIVE, 0L),
                request(BondedVesselTransition.STORE), 25_000L);

        assertEquals(BondedVesselState.STORED, plan.targetState());
        assertEquals("Draconic_Stone_Stored", plan.candidateItemId());
        assertEquals(35_000L, plan.targetCooldownUntilMs());
    }

    @Test
    void repairPreservesExistingCooldownAndReleasePlansEmptyProjection() {
        ProductionBondedVesselTransitionPlanner planner = planner(
                config(BondedVesselMode.BONDED), new BondedVesselItemFingerprintCodec());

        BondedVesselTransitionPlanner.Plan repair = planner.plan(
                binding(BondedVesselBindingRecord.LifecycleState.DEAD, 41_000L),
                request(BondedVesselTransition.REPAIR_DEAD_TO_STORED), 50_000L);
        BondedVesselTransitionPlanner.Plan release = planner.plan(
                binding(BondedVesselBindingRecord.LifecycleState.DEAD, 41_000L),
                request(BondedVesselTransition.RELEASE), 50_000L);

        assertEquals(BondedVesselState.STORED, repair.targetState());
        assertEquals(41_000L, repair.targetCooldownUntilMs());
        assertEquals(BondedVesselState.RELEASED, release.targetState());
        assertEquals(BondedVesselProjectionStatus.MISSING, release.targetProjectionStatus());
        assertEquals("Draconic_Stone", release.candidateItemId());
        assertEquals(41_000L, release.targetCooldownUntilMs());
    }

    @Test
    void missingWrongRevisionOrDisposableConfigFailsClosed() {
        BondedVesselBindingRecord binding = binding(BondedVesselBindingRecord.LifecycleState.DEAD, 0L);
        BondedVesselTransitionRequest request = request(BondedVesselTransition.REPAIR_DEAD_TO_STORED);

        assertThrows(IllegalArgumentException.class, () -> new ProductionBondedVesselTransitionPlanner(
                (id, revision) -> Optional.empty(), new BondedVesselItemFingerprintCodec())
                .plan(binding, request, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ProductionBondedVesselTransitionPlanner(
                (id, revision) -> Optional.of(config(BondedVesselMode.BONDED, revision + 1L)),
                new BondedVesselItemFingerprintCodec()).plan(binding, request, 1L));
        assertThrows(IllegalArgumentException.class, () -> planner(
                config(BondedVesselMode.DISPOSABLE), new BondedVesselItemFingerprintCodec())
                .plan(binding, request, 1L));
    }

    private static ProductionBondedVesselTransitionPlanner planner(
            SpawnerVesselConfigView config,
            BondedVesselItemFingerprintCodec fingerprints) {
        return new ProductionBondedVesselTransitionPlanner(
                (id, revision) -> id.equals(config.configId()) && revision == config.configRevision()
                        ? Optional.of(config) : Optional.empty(),
                fingerprints);
    }

    private static SpawnerVesselConfigView config(BondedVesselMode mode) {
        return config(mode, CONFIG_REVISION);
    }

    private static SpawnerVesselConfigView config(BondedVesselMode mode, long revision) {
        return new SpawnerVesselConfigView(
                "TwSpawnerConfig_Draconic_Stone", revision, mode,
                mode == BondedVesselMode.BONDED ? "Draconic_Stone" : null,
                mode == BondedVesselMode.BONDED ? "Draconic_Stone_Stored" : null,
                "Draconic_Stone_Active", "Draconic_Stone_Damaged",
                "Draconic_Stone_Lost", "Draconic_Stone_Unavailable",
                10_000L, 12.0D, "HyDragon_Store", "SFX_HyDragon_Store", true, false);
    }

    private static BondedVesselBindingRecord binding(
            BondedVesselBindingRecord.LifecycleState state,
            long cooldownUntilMs) {
        return new BondedVesselBindingRecord(
                BINDING_ID.toString(), "dragon-profile", 4L,
                "TwSpawnerConfig_Draconic_Stone", CONFIG_REVISION, state,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT, OWNER_ID,
                9L, state == BondedVesselBindingRecord.LifecycleState.ACTIVE ? UUID.randomUUID() : null,
                null, cooldownUntilMs, itemFor(state), "{}", null, null,
                1L, 1L, 1L, 0L);
    }

    private static String itemFor(BondedVesselBindingRecord.LifecycleState state) {
        return switch (state) {
            case STORED -> "Draconic_Stone_Stored";
            case ACTIVE -> "Draconic_Stone_Active";
            case DEAD -> "Draconic_Stone_Damaged";
            default -> "Draconic_Stone_Stored";
        };
    }

    private static BondedVesselTransitionRequest request(BondedVesselTransition transition) {
        UUID expectedNpc = transition == BondedVesselTransition.STORE
                ? UUID.fromString("30000000-0000-0000-0000-000000000003") : null;
        PopulationAdmissionLocation destination = transition == BondedVesselTransition.SUMMON
                ? new PopulationAdmissionLocation("world", 0, 0) : null;
        return new BondedVesselTransitionRequest(
                "hydragon", "operation-" + transition.name().toLowerCase(), OWNER_ID, BINDING_ID,
                4L, 9L, transition,
                new BondedVesselTransitionContext(
                        sourceItem(transition), "player:" + OWNER_ID, "hotbar", 2, 15L,
                        "sha256:source", expectedNpc, destination));
    }

    private static String sourceItem(BondedVesselTransition transition) {
        return switch (transition) {
            case SUMMON -> "Draconic_Stone_Stored";
            case STORE -> "Draconic_Stone_Active";
            case REPAIR_DEAD_TO_STORED, RELEASE -> "Draconic_Stone_Damaged";
        };
    }
}
