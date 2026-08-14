package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.AddReason;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionProgressionBootstrapOnLoadSystemTest {
    @Test
    void untamedNpcKeepsTraitRepairWhenLoadBootstrapIsConsolidated() {
        CompanionProgressionBootstrapOnLoadSystem.LoadDecision decision =
                CompanionProgressionBootstrapOnLoadSystem.LoadDecision.classify(
                        false,
                        CompanionTraitBootstrapPlan.FULL_REPAIR,
                        true,
                        false
                );

        assertEquals(CompanionTraitBootstrapPlan.FULL_REPAIR, decision.traitPlan());
        assertFalse(decision.progressionRepair());
        assertTrue(decision.requiresWork());
    }

    @Test
    void queuesAttachmentRepairForLoadedUntamedNpcWithMigrationConfig() {
        assertTrue(CompanionProgressionBootstrapOnLoadSystem.shouldRunAttachmentLoadBootstrap(
                AddReason.LOAD,
                null,
                false,
                true
        ));
    }

    @Test
    void queuesAttachmentRepairForLoadedUntamedNpcWithStoredAttachments() {
        assertTrue(CompanionProgressionBootstrapOnLoadSystem.shouldRunAttachmentLoadBootstrap(
                AddReason.LOAD,
                null,
                true,
                false
        ));
    }

    @Test
    void doesNotQueueAttachmentRepairForFreshSpawnWithoutStoredOrMigrationState() {
        assertFalse(CompanionProgressionBootstrapOnLoadSystem.shouldRunAttachmentLoadBootstrap(
                AddReason.SPAWN,
                new TameworkTamedComponent(true),
                false,
                true
        ));
    }

    @Test
    void negativeNeedsTimestampsDoNotRequireBootstrap() {
        TameworkNeedsComponent needs = new TameworkNeedsComponent(
                "Needs_Config",
                50.0,
                50.0,
                0.0,
                -120_000L,
                -119_000L
        );

        assertFalse(CompanionProgressionBootstrapOnLoadSystem.needsTimestampsRequireBootstrap(needs));
    }

    @Test
    void juvenileLifecycleRoleRequiresLifeStageBootstrapIndependentFromTraits() throws Exception {
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoleId", "Tamed_Cow");
        setField(family, "babyRoleId", "Tamed_Cow_Calf");

        assertTrue(CompanionProgressionBootstrapOnLoadSystem.isJuvenileLifecycleRole("Tamed_Cow_Calf", family));
        assertTrue(CompanionProgressionBootstrapOnLoadSystem.isJuvenileLifecycleRole(
                "example:Tamed_Cow_Calf",
                family
        ));
        assertFalse(CompanionProgressionBootstrapOnLoadSystem.isJuvenileLifecycleRole("Tamed_Cow", family));
    }

    @Test
    void juvenileLifecycleRoleChecksLineSpecificBabyAndAdolescentRoles() throws Exception {
        TwBreedingConfig.RoleLine line = new TwBreedingConfig.RoleLine();
        setField(line, "adultRoleId", "Tamed_Deer_Doe");
        setField(line, "babyRoleId", "Tamed_Deer_Fawn");
        setField(line, "adolescentRoleId", "Tamed_Deer_Yearling");
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "lines", new TwBreedingConfig.RoleLine[] { line });

        assertTrue(CompanionProgressionBootstrapOnLoadSystem.isJuvenileLifecycleRole("Tamed_Deer_Fawn", family));
        assertTrue(CompanionProgressionBootstrapOnLoadSystem.isJuvenileLifecycleRole("Tamed_Deer_Yearling", family));
        assertFalse(CompanionProgressionBootstrapOnLoadSystem.isJuvenileLifecycleRole("Tamed_Deer_Doe", family));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
