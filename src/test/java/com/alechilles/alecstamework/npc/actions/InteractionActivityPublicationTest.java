package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.ManagedActivityView;
import com.alechilles.alecstamework.api.TameActivityView;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
import com.alechilles.alecstamework.npc.progression.CompanionXpTransition;
import com.hypixel.hytale.codec.ExtraInfo;
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
    void tamePublicationUsesFinalOwnerTamedStateAndRole() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        ActivityRuntime.install(published::add, managedRegistry());

        InteractionExecutor.publishTameIfCommitted(
                UUID.randomUUID(), true, OWNER, OWNER, true,
                "RoleB", COMPANION);
        InteractionExecutor.publishTameIfCommitted(
                UUID.randomUUID(), true, OWNER, OWNER, false,
                "RoleA", COMPANION);
        InteractionExecutor.publishTameIfCommitted(
                UUID.randomUUID(), true, OWNER, UUID.randomUUID(), true,
                "RoleA", COMPANION);
        InteractionExecutor.publishTameIfCommitted(
                UUID.randomUUID(), false, OWNER, OWNER, true,
                "RoleA", COMPANION);

        assertEquals(1, published.size());
        TameActivityView activity = assertInstanceOf(
                TameActivityView.class, published.getFirst());
        assertEquals("RoleB", activity.roleId());
        assertEquals(OWNER, activity.ownerId());
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
