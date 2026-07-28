package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.bonded
        .BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression coverage for wild-to-tamed bonded capture role authority. */
class BondedCompanionCaptureTamedRoleTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");

    @Test
    void nordicDrakeRequestAndSnapshotUseMappedTamedRole() {
        assertMappedCaptureRole("NordicDrake", "Tamed_NordicDrake");
    }

    @Test
    void hydraRequestAndSnapshotUseMappedTamedRole() {
        assertMappedCaptureRole("Hydra", "Tamed_Hydra");
    }

    @Test
    void missingTamedRoleOverrideFailsClosed() {
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .captureTamesTarget(true)
                .build();

        assertNull(BondedCompanionCaptureRoleResolver.resolve(
                config, List.of(family("Tamed_NordicDrake")),
                "NordicDrake"));
    }

    @Test
    void mappedRoleOutsideBondedPolicyFailsClosed() {
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .captureTamesTarget(true)
                .captureTamedRoleOverrides(Map.of(
                        "NordicDrake", "Tamed_NordicDrake"))
                .build();

        assertNull(BondedCompanionCaptureRoleResolver.resolve(
                config, List.of(family("Tamed_Hydra")), "NordicDrake"));
    }

    @Test
    void ordinaryBondedCaptureKeepsItsSourceRole() {
        ItemFeatureConfig config = ItemFeatureConfig.builder().build();

        var resolved = BondedCompanionCaptureRoleResolver.resolve(
                config, List.of(family("Tamed_Chicken")), "Tamed_Chicken");

        assertNotNull(resolved);
        assertEquals("Tamed_Chicken", resolved.roleId());
    }

    private static void assertMappedCaptureRole(
            String sourceRole,
            String tamedRole
    ) {
        ItemFeatureConfig config = ItemFeatureConfig.builder()
                .captureTamesTarget(true)
                .captureTamedRoleOverrides(Map.of(sourceRole, tamedRole))
                .build();
        String authoritativeRole = config.resolveCaptureTamedRole(sourceRole);
        var resolved = BondedCompanionCaptureRoleResolver.resolve(
                config, List.of(family(tamedRole)), sourceRole);
        assertNotNull(resolved);
        assertEquals(tamedRole, resolved.roleId());
        assertEquals("hydragon:full_dragons", resolved.family().familyId());
        BondedCompanionCaptureIntent intent =
                SpawnerCaptureIntentFactory.freezeBonded(
                        new SpawnerCaptureIntentFactory.FrozenBondedCapture(
                                "spawner-bonded-capture:v1", "attempt", OWNER,
                                "world", 2, "fingerprint", SOURCE,
                                authoritativeRole, "hydragon:dragon_horn", 4L,
                                snapshot(sourceRole), null, true, true, true,
                                true, true, true
                        )
                );

        assertEquals(tamedRole, intent.roleId());
        assertEquals(tamedRole, intent.snapshot().fullState().roleId());
    }

    private static BondedCompanionRosterRegistry.RosterDefinition family(
            String allowedRole
    ) {
        return new BondedCompanionRosterRegistry.RosterDefinition(
                "FullDragons", 100, "hydragon:dragon_horn",
                "hydragon:full_dragons", Set.of(allowedRole), 0, 1,
                600L, 300L, null,
                new BondedCompanionRosterRegistry.FeatureFlags(
                        true, false, true, true, true)
        );
    }

    private static BondedCompanionSnapshot snapshot(String roleId) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                SOURCE, null, -1, roleId, null, null, null, null,
                null, null, null, null, null, null, null, null, 1.0D, 10L
        ), Map.of());
    }
}
