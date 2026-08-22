package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.ManagedActivityView;
import com.alechilles.alecstamework.api.TameActivityView;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
import com.alechilles.alecstamework.npc.progression.CompanionXpTransition;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Behavior checks for container-only harvest activity publication. */
class InteractionActivityPublicationTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearRuntime() {
        ActivityRuntime.clear();
    }

    @Test
    void publishesOneAppliedContainerHarvestWithOutputAndXpButNotFailedResult() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        ActivityRuntime.install(published::add, managedRegistry());
        InteractionExecutor executor = new InteractionExecutor(null, null);
        UUID appliedOperation = UUID.randomUUID();
        CompanionXpTransition transition = harvestTransition();
        AwardResult award = new AwardResult(true, 2.0, 1, 2, 5.0, transition);
        AtomicInteger awards = new AtomicInteger();

        executor.publishContainerHarvest(
                appliedOperation,
                "RoleA",
                "Milk",
                OWNER,
                COMPANION,
                new TameworkInteractEffects.HarvestContainerOutcome(
                        TameworkInteractEffects.HarvestContainerResult.APPLIED,
                        false,
                        Map.of("Container_Bucket_State_Filled_Milk", 1)
                ),
                false,
                () -> {
                    awards.incrementAndGet();
                    return award;
                }
        );
        executor.publishContainerHarvest(
                UUID.randomUUID(),
                "RoleA",
                "Milk",
                OWNER,
                COMPANION,
                new TameworkInteractEffects.HarvestContainerOutcome(
                        TameworkInteractEffects.HarvestContainerResult.FAILED,
                        false,
                        Map.of("Container_Bucket_State_Filled_Milk", 1)
                ),
                false,
                () -> {
                    awards.incrementAndGet();
                    return award;
                }
        );
        executor.publishContainerHarvest(
                UUID.randomUUID(),
                "RoleA",
                "Milk",
                OWNER,
                COMPANION,
                new TameworkInteractEffects.HarvestContainerOutcome(
                        TameworkInteractEffects.HarvestContainerResult.APPLIED,
                        false,
                        Map.of("Container_Bucket_State_Filled_Milk", 1)
                ),
                true,
                () -> {
                    awards.incrementAndGet();
                    return award;
                }
        );

        assertEquals(1, published.size());
        assertEquals(1, awards.get());
        ManagedActivityView activity = assertInstanceOf(
                ManagedActivityView.class, published.get(0));
        assertEquals(appliedOperation, activity.header().operationId());
        assertEquals(Map.of("Container_Bucket_State_Filled_Milk", 1),
                activity.itemQuantities());
        assertEquals(transition.toOutcomeView(), activity.companionXpOutcome());
        assertNull(activity.careCreditOutcome());
    }

    @Test
    void tameInteractionPublishesOnlyAfterAuthorityCommitsOwnershipAndTamedState()
            throws Exception {
        List<ActivityView> published = new ArrayList<>();
        ActivityRuntime.install(published::add, managedRegistry());
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install()) {
            ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                    new ComponentType<>();
            ComponentType<EntityStore, UUIDComponent> uuidType = new ComponentType<>();
            set(Tamework.getInstance(), "tamedComponentType", tamedType);
            componentTypes().put(UUIDComponent.class, uuidType);
            set(entityModule(), "uuidComponentType", uuidType);
            Field tranquilizerIndex = InteractionStateEffects.class.getDeclaredField(
                    "tranquilizerEffectIndex");
            tranquilizerIndex.setAccessible(true);
            int previousTranquilizerIndex = tranquilizerIndex.getInt(null);
            tranquilizerIndex.setInt(null, -1);
            try (TestEntityComponentStore store = new TestEntityComponentStore(
                    new EntityStore(null))) {
                Ref<EntityStore> npcRef = store.createReference();
                NPCEntity npc = new NPCEntity();
                store.put(npcRef, NPCEntity.getComponentType(), npc);
                store.put(npcRef, uuidType, new UUIDComponent(COMPANION));
                Player player = (Player) unsafe().allocateInstance(Player.class);
                player.setLegacyUUID(OWNER);
                InteractionExecutor executor = newExecutor();
                TwInteractionConfig.TameInteraction tame =
                        new TwInteractionConfig.TameInteraction();
                set(tame, "role", "Missing_Role");
                Role liveRole = (Role) unsafe().allocateInstance(Role.class);
                set(liveRole, "roleName", "RoleA");

                assertTrue(executor.applyInteraction(
                        new ActionTameworkInteract.ResolvedInteraction(
                                "test", tame, 0, 0, null),
                        npcRef, liveRole, null, store, player, null));

                TameworkOwnerComponent owner = store.getComponent(
                        npcRef, TameworkOwnerComponent.getComponentType());
                assertEquals(OWNER, owner.getOwnerId());
                assertTrue(store.getComponent(npcRef, tamedType).isTamed());
            } finally {
                tranquilizerIndex.setInt(null, previousTranquilizerIndex);
            }
        }

        assertEquals(1, published.size());
        TameActivityView activity = assertInstanceOf(
                TameActivityView.class, published.getFirst());
        assertEquals("RoleA", activity.roleId());
        assertEquals(OWNER, activity.ownerId());
        assertEquals(COMPANION, activity.companionId());
    }

    private static InteractionExecutor newExecutor() throws Exception {
        ActionTameworkInteract owner = (ActionTameworkInteract) unsafe()
                .allocateInstance(ActionTameworkInteract.class);
        InteractionParamResolver resolver = new InteractionParamResolver(
                null, null, null);
        InteractionParamAccess params = new InteractionParamAccess(
                resolver, false, null, null, null,
                "LovedItems", "IsHarvestable", "IsMountable");
        InteractionResolution resolution = new InteractionResolution(
                params,
                new InteractionConfigResolver(
                        null, params, "InteractionConfigId"));
        set(owner, "resolution", resolution);
        return new InteractionExecutor(
                new TameworkInteractEffects(owner, null),
                new InteractionFeedHelper(params));
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, ComponentType<EntityStore, ?>> componentTypes()
            throws Exception {
        Field types = EntityModule.class.getDeclaredField("classToComponentType");
        types.setAccessible(true);
        return (Map<Class<?>, ComponentType<EntityStore, ?>>) types.get(entityModule());
    }

    private static Object entityModule() throws Exception {
        Field instance = EntityModule.class.getDeclaredField("instance");
        instance.setAccessible(true);
        return instance.get(null);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static CompanionXpTransition harvestTransition() {
        return new CompanionXpTransition(
                COMPANION,
                OWNER,
                Set.of(),
                "RoleA",
                "runeteria:levels",
                CompanionXpSource.HARVEST,
                2.0,
                1,
                2,
                3.0,
                5.0,
                0.0,
                2.0,
                10.0,
                20,
                false,
                true,
                1L,
                1L
        );
    }

    private static ManagedActivityConfigRegistry managedRegistry() throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(group(
                        "group", "runeteria:family", "RoleA", "RoleB")), 1L
        ).applied());
        ManagedActivityConfigRegistry managed = new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig profile = decode("husbandry", """
                {
                  "ProfileId": "runeteria:husbandry",
                  "ProviderId": "runeteria:provider",
                  "ProviderContractVersion": 1,
                  "RequiredCapabilities": ["ACTIVITY_FEED_V2"],
                  "Domains": [{"DomainId":"runeteria:owned", "Owned":true}],
                  "Families": [{"GroupId":"runeteria:family", "GateKey":"runeteria:gate", "Weight":1}],
                  "Activities": {
                    "Feed":"runeteria:feed",
                    "HarvestContexts":{"Milk":"runeteria:milk"},
                    "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                    "BreedingSuccess":"runeteria:breed",
                    "TameSuccess":"runeteria:tame_success",
                    "NeedSatisfied":"runeteria:need_satisfied"
                  }
                }
                """);
        assertTrue(managed.replace(List.of(profile), 1L).applied());
        return managed;
    }

    private static TwManagedActivityConfig decode(String id, String json) throws Exception {
        TwManagedActivityConfig config = TwManagedActivityConfig.CODEC.decode(
                BsonDocument.parse(json), new ExtraInfo());
        set(config, "id", id);
        return config;
    }

    private static TwPopulationGroupConfig group(
            String id,
            String groupId,
            String... roleIds
    )
            throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {"GroupId":"%s","RoleIds":%s}
                        """.formatted(
                        groupId,
                        java.util.Arrays.stream(roleIds)
                                .map(roleId -> "\"" + roleId + "\"")
                                .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                )), new ExtraInfo());
        set(config, "id", id);
        set(config, "limits", new TwPopulationGroupConfig.LimitSettings());
        set(field(config, "limits"), "scope", PopulationGroupScope.GLOBAL);
        return config;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
