package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for signed parent cooldown window construction. */
class BreedingCooldownServiceTest {
    @Test
    void negativeWorldCooldownPreservesSignedStartAndDeadline() {
        BreedingCooldownService.CooldownWindow window =
                BreedingCooldownService.resolveWindow(-3_000L, 2_000L);

        assertEquals(-1_000L, window.untilMs());
        assertEquals(-3_000L, window.startedAtMs());
        assertEquals(2_000L, window.durationMs());
    }

    @Test
    void realCooldownAvoidsZeroSentinelAndSaturatesOverflow() {
        assertEquals(1L, BreedingCooldownService.resolveWindow(-1_000L, 1_000L).untilMs());
        assertEquals(
                Long.MAX_VALUE,
                BreedingCooldownService.resolveWindow(Long.MAX_VALUE - 5L, 10L).untilMs()
        );
    }

    @Test
    void husbandryMultiplierScalesOnlyAlreadyTraitAdjustedParentDuration() {
        assertEquals(
                70_000L,
                BreedingCooldownService.applyParentOutcomeMultiplier(
                        200_000L, 0.5, 0.70
                )
        );
    }

    @Test
    void parentCooldownMutationAppliesOutcomeAndLeavesChildLockUnchanged() throws Exception {
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            ComponentType<EntityStore, TameworkBreedingComponent> breedingType = new ComponentType<>();
            setField(Tamework.getInstance(), "breedingComponentType", breedingType);
            Ref<EntityStore> parentRef = store.createReference();
            Ref<EntityStore> childRef = store.createReference();
            UUID parentUuid = UUID.fromString("30000000-0000-0000-0000-000000000001");
            UUID partnerUuid = UUID.fromString("30000000-0000-0000-0000-000000000002");
            UUID childLockOwner = UUID.fromString("30000000-0000-0000-0000-000000000003");
            NPCEntity parentNpc = new NPCEntity();
            parentNpc.setLegacyUUID(parentUuid);
            parentNpc.setRoleName("RoleA");
            store.put(parentRef, NPCEntity.getComponentType(), parentNpc);
            TameworkBreedingComponent parent = new TameworkBreedingComponent(
                    "breeding-test", 80.0, 10L, true, true, 0L, null);
            TameworkBreedingComponent child = new TameworkBreedingComponent(
                    "breeding-test", 50.0, 10L, true, true, 77_000L,
                    childLockOwner, 66_000L, 77_000L, childLockOwner, 88_000L);
            store.put(parentRef, breedingType, parent);
            store.put(childRef, breedingType, child);

            HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
            registry.register(context -> new HusbandryOutcomeModifiers(1.0, 0.0, 0.0, 0.70));
            installRuntime(registry);
            try {
                new BreedingCooldownService().applyParentCooldown(
                        parentRef,
                        parent,
                        null,
                        partnerUuid,
                        200_000L,
                        1_000_000L,
                        900_000L,
                        store,
                        null
                );

                assertFalse(parent.isReady());
                assertEquals(1_140_000L, parent.getCooldownUntilMs());
                assertEquals(1_000_000L, parent.getCooldownStartedAtMs());
                assertEquals(140_000L, parent.getCooldownDurationMs());
                assertEquals(partnerUuid, parent.getLastPartnerUuid());
                assertEquals(900_000L, parent.getLastHappinessUpdateMs());
                assertTrue(parent.getManualBreedingPlayerUuid() == null);
                assertEquals(77_000L, child.getCooldownUntilMs());
                assertEquals(66_000L, child.getCooldownStartedAtMs());
                assertEquals(77_000L, child.getCooldownDurationMs());
                assertEquals(childLockOwner, child.getManualBreedingPlayerUuid());
                assertEquals(88_000L, child.getManualBreedingUntilMs());
                assertTrue(child.isReady());
            } finally {
                clearRuntime(registry);
                registry.close();
            }
        }
    }

    private static void installRuntime(HusbandryOutcomeApi api) throws Exception {
        invokeRuntime("install", api);
    }

    private static void clearRuntime(HusbandryOutcomeApi api) throws Exception {
        invokeRuntime("clear", api);
    }

    private static void invokeRuntime(String methodName, HusbandryOutcomeApi api) throws Exception {
        Method method = HusbandryOutcomeRuntime.class.getDeclaredMethod(
                methodName, HusbandryOutcomeApi.class);
        method.setAccessible(true);
        method.invoke(null, api);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = Tamework.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
