package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.SuccessfulActivityView;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Behavior checks for the internal successful-activity publication seam. */
class SuccessfulActivityRuntimeTest {
    private static final UUID OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void publishesManagedActivitiesAndSkipsUnmanagedOrMissingMappings()
            throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(group("group", "runeteria:family", "RoleA")),
                1L
        ).applied());
        ManagedActivityConfigRegistry managed =
                new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig profile = decode("husbandry", """
                {
                  "ProfileId": "runeteria:husbandry",
                  "ProviderId": "runeteria:provider",
                  "ProviderContractVersion": 1,
                  "RequiredCapabilities": ["PROFILES"],
                  "Domains": [
                    {"DomainId":"runeteria:owned", "Owned":true}
                  ],
                  "Families": [
                    {"GroupId":"runeteria:family", "GateKey":"runeteria:gate", "Weight":1}
                  ],
                  "Activities": {
                    "Feed":"runeteria:feed",
                    "HarvestContexts":{"Milk":"runeteria:milk"},
                    "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                    "BreedingSuccess":"runeteria:breed"
                  }
                }
                """);
        assertTrue(managed.replace(List.of(profile), 1L).applied());

        List<SuccessfulActivityView> published = new ArrayList<>();
        SuccessfulActivityRuntime.install(published::add, managed);
        try {
            SuccessfulActivityRuntime.publishFeed(
                    UUID.randomUUID(), "RoleA", OWNER, COMPANION
            );
            SuccessfulActivityRuntime.publishHarvest(
                    UUID.randomUUID(), "RoleA", "Milk", OWNER, COMPANION
            );
            UUID litterId = UUID.randomUUID();
            SuccessfulActivityRuntime.publishBreeding(
                    litterId, "RoleA", OWNER, COMPANION
            );

            SuccessfulActivityRuntime.publishFeed(
                    UUID.randomUUID(), "UnknownRole", OWNER, COMPANION
            );
            SuccessfulActivityRuntime.publishHarvest(
                    UUID.randomUUID(), "RoleA", "UnknownContext", OWNER, COMPANION
            );
            SuccessfulActivityRuntime.publishFeed(
                    UUID.randomUUID(), "RoleA", null, COMPANION
            );

            assertEquals(
                    List.of("runeteria:feed", "runeteria:milk", "runeteria:breed"),
                    published.stream().map(SuccessfulActivityView::activityId).toList()
            );
            assertEquals(litterId, published.get(2).operationId());
            assertEquals(OWNER, published.get(0).ownerId());
            assertEquals(COMPANION, published.get(0).companionId());
            assertEquals("runeteria:family", published.get(0).groupIds().iterator().next());
        } finally {
            SuccessfulActivityRuntime.clear();
        }
    }

    private static TwManagedActivityConfig decode(String id, String json)
            throws Exception {
        TwManagedActivityConfig config = TwManagedActivityConfig.CODEC.decode(
                BsonDocument.parse(json), new ExtraInfo()
        );
        set(config, "id", id);
        return config;
    }

    private static TwPopulationGroupConfig group(
            String id,
            String groupId,
            String roleId
    ) throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "GroupId":"%s",
                          "RoleIds":["%s"]
                        }
                        """.formatted(groupId, roleId)),
                new ExtraInfo()
        );
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

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
