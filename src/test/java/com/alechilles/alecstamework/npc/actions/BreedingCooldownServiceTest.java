package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRegistry;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.time.TimeModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

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
    void parentCooldownMutationUsesEachOwnerModifierOnceAndLeavesChildLockUnchanged() throws Exception {
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            ComponentType<EntityStore, TameworkBreedingComponent> breedingType = new ComponentType<>();
            setField(Tamework.getInstance(), "breedingComponentType", breedingType);
            Ref<EntityStore> parentRef = store.createReference();
            Ref<EntityStore> otherParentRef = store.createReference();
            Ref<EntityStore> childRef = store.createReference();
            UUID parentUuid = UUID.fromString("30000000-0000-0000-0000-000000000001");
            UUID partnerUuid = UUID.fromString("30000000-0000-0000-0000-000000000002");
            UUID childUuid = UUID.fromString("30000000-0000-0000-0000-000000000003");
            UUID parentOwnerUuid = UUID.fromString("40000000-0000-0000-0000-000000000001");
            UUID otherParentOwnerUuid = UUID.fromString("40000000-0000-0000-0000-000000000002");
            NPCEntity parentNpc = new NPCEntity();
            parentNpc.setLegacyUUID(parentUuid);
            parentNpc.setRoleName("RoleA");
            store.put(parentRef, NPCEntity.getComponentType(), parentNpc);
            NPCEntity otherParentNpc = new NPCEntity();
            otherParentNpc.setLegacyUUID(partnerUuid);
            otherParentNpc.setRoleName("RoleB");
            store.put(otherParentRef, NPCEntity.getComponentType(), otherParentNpc);
            NPCEntity childNpc = new NPCEntity();
            childNpc.setLegacyUUID(childUuid);
            childNpc.setRoleName("Baby_Role");
            store.put(childRef, NPCEntity.getComponentType(), childNpc);
            TameworkBreedingComponent parent = new TameworkBreedingComponent(
                    "breeding-test", 80.0, 10L, true, true, 0L, null);
            TameworkBreedingComponent otherParent = new TameworkBreedingComponent(
                    "breeding-test", 75.0, 10L, true, true, 0L, null);
            TameworkBreedingComponent child = new TameworkBreedingComponent(
                    "breeding-test", 50.0, 10L, true, true, 77_000L,
                    UUID.fromString("30000000-0000-0000-0000-000000000004"),
                    66_000L, 77_000L,
                    UUID.fromString("30000000-0000-0000-0000-000000000004"),
                    88_000L);
            store.put(parentRef, breedingType, parent);
            store.put(otherParentRef, breedingType, otherParent);
            store.put(childRef, breedingType, child);
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                    TameworkOwnerComponent.getComponentType();
            store.put(parentRef, ownerType,
                    new TameworkOwnerComponent(parentOwnerUuid, "Parent Owner"));
            store.put(otherParentRef, ownerType,
                    new TameworkOwnerComponent(otherParentOwnerUuid, "Other Parent Owner"));

            Field statsInstanceField = EntityStatsModule.class.getDeclaredField("instance");
            statsInstanceField.setAccessible(true);
            Object previousStatsInstance = statsInstanceField.get(null);
            EntityStatsModule statsModule = (EntityStatsModule) unsafe().allocateInstance(
                    EntityStatsModule.class);
            Field statsTypeField = EntityStatsModule.class.getDeclaredField(
                    "entityStatMapComponentType");
            statsTypeField.setAccessible(true);
            statsTypeField.set(statsModule, new ComponentType<>());
            statsInstanceField.set(null, statsModule);
            Field timeInstanceField = TimeModule.class.getDeclaredField("instance");
            timeInstanceField.setAccessible(true);
            Object previousTimeInstance = timeInstanceField.get(null);
            TimeModule timeModule = (TimeModule) unsafe().allocateInstance(TimeModule.class);
            ResourceType<EntityStore, WorldTimeResource> worldTimeType =
                    store.getRegistry().registerResource(WorldTimeResource.class, WorldTimeResource::new);
            Field worldTimeTypeField = TimeModule.class.getDeclaredField(
                    "worldTimeResourceType");
            worldTimeTypeField.setAccessible(true);
            worldTimeTypeField.set(timeModule, worldTimeType);
            timeInstanceField.set(null, timeModule);
            WorldTimeResource worldTime = new WorldTimeResource();
            worldTime.setGameTime0(Instant.ofEpochMilli(200_000L));
            Field resourcesField = Store.class.getDeclaredField("resources");
            resourcesField.setAccessible(true);
            Resource<EntityStore>[] resources = new Resource[worldTimeType.getIndex() + 1];
            resources[worldTimeType.getIndex()] = worldTime;
            resourcesField.set(store, resources);

            HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
            registry.register(context -> parentOwnerUuid.equals(context.ownerId())
                    ? new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 0.0, 0.70)
                    : HusbandryOutcomeModifiers.identity());
            installRuntime(registry);
            try {
                BreedingCooldownService cooldownService = new BreedingCooldownService();
                cooldownService.applyParentCooldown(
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
                cooldownService.applyParentCooldown(
                        otherParentRef,
                        otherParent,
                        null,
                        parentUuid,
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
                assertFalse(otherParent.isReady());
                assertEquals(1_200_000L, otherParent.getCooldownUntilMs());
                assertEquals(1_000_000L, otherParent.getCooldownStartedAtMs());
                assertEquals(200_000L, otherParent.getCooldownDurationMs());
                assertEquals(parentUuid, otherParent.getLastPartnerUuid());
                assertEquals(900_000L, otherParent.getLastHappinessUpdateMs());
                new BreedingOffspringProgressionService().applyOffspringState(
                        childRef,
                        null,
                        null,
                        null,
                        "Baby_Role",
                        BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                        BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                        false,
                        false,
                        null,
                        200_000L,
                        null,
                        null,
                        null,
                        CompanionLifeStageService.LifecycleFamilyResolution.PLANNED_SELECTION_ONLY,
                        store
                );
                TameworkBreedingComponent initializedChild = store.getComponent(
                        childRef, breedingType);
                assertEquals(200_000L, initializedChild.getCooldownDurationMs());
                assertFalse(initializedChild.isReady());
                assertTrue(initializedChild.getLastPartnerUuid() == null);
                assertTrue(initializedChild.getManualBreedingPlayerUuid() == null);
                assertEquals(0L, initializedChild.getManualBreedingUntilMs());
            } finally {
                clearRuntime(registry);
                registry.close();
                statsInstanceField.set(null, previousStatsInstance);
                timeInstanceField.set(null, previousTimeInstance);
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

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
