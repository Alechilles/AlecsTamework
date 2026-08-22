package com.alechilles.alecstamework.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityParticipantView;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.CareCreditOutcomeView;
import com.alechilles.alecstamework.api.ManagedActivityView;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Behavior checks for managed Activity API V2 payload publication. */
class ManagedActivityPublisherTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_COMPANION = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");

    @Test
    void publishesMappedFeedHarvestAndBreedingPayloadsAfterCommittedOutcomes() throws Exception {
        ManagedActivityConfigRegistry managed = managedRegistry();
        List<ActivityView> published = new ArrayList<>();
        ManagedActivityPublisher publisher = new ManagedActivityPublisher(
                published::add,
                managed
        );
        UUID feedOperation = UUID.randomUUID();
        UUID harvestOperation = UUID.randomUUID();
        UUID litterId = UUID.randomUUID();
        UUID offspring = UUID.randomUUID();

        publisher.publishFeed(
                feedOperation,
                "RoleA",
                OWNER,
                COMPANION,
                Map.of(),
                null,
                new CareCreditOutcomeView(COMPANION, OWNER)
        );
        publisher.publishHarvest(
                harvestOperation,
                "RoleA",
                "Milk",
                OWNER,
                COMPANION,
                Map.of("Milk", 2),
                null
        );
        publisher.publishBreeding(
                litterId,
                "RoleA",
                OWNER,
                COMPANION,
                "RoleA",
                SECOND_OWNER,
                SECOND_COMPANION,
                List.of(offspring)
        );

        assertEquals(3, published.size());
        ManagedActivityView feed = assertInstanceOf(
                ManagedActivityView.class, published.get(0));
        assertEquals(ActivityIds.FEED, feed.header().actionId());
        assertEquals(feedOperation, feed.header().operationId());
        assertEquals(
                List.of(new ActivityParticipantView(
                        COMPANION,
                        OWNER,
                        "runeteria:husbandry",
                        "RoleA"
                )),
                feed.participants()
        );
        assertEquals("runeteria:husbandry", feed.profileId());
        assertEquals(Set.of("runeteria:family"), feed.groupIds());
        assertEquals("runeteria:feed", feed.mappedActivityId());
        assertEquals(new CareCreditOutcomeView(COMPANION, OWNER), feed.careCreditOutcome());

        ManagedActivityView harvest = assertInstanceOf(
                ManagedActivityView.class, published.get(1));
        assertEquals(ActivityIds.HARVEST, harvest.header().actionId());
        assertEquals(harvestOperation, harvest.header().operationId());
        assertEquals(Map.of("Milk", 2), harvest.itemQuantities());
        assertEquals(
                List.of(new ActivityParticipantView(
                        COMPANION,
                        OWNER,
                        "runeteria:husbandry",
                        "RoleA"
                )),
                harvest.participants()
        );
        assertNull(harvest.careCreditOutcome());

        ManagedActivityView breeding = assertInstanceOf(
                ManagedActivityView.class, published.get(2));
        assertEquals(ActivityIds.BREED_SUCCESS, breeding.header().actionId());
        assertEquals(litterId, breeding.header().operationId());
        assertEquals(
                List.of(
                        new ActivityParticipantView(
                                COMPANION,
                                OWNER,
                                "runeteria:husbandry",
                                "RoleA"
                        ),
                        new ActivityParticipantView(
                                SECOND_COMPANION,
                                SECOND_OWNER,
                                "runeteria:husbandry",
                                "RoleA"
                        )
                ),
                breeding.participants()
        );
        assertEquals(List.of(offspring), breeding.offspringIds());
    }

    private static ManagedActivityConfigRegistry managedRegistry() throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(group("group", "runeteria:family", "RoleA")), 1L
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
                    "NeedSatisfied":"runeteria:feed"
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

    private static TwPopulationGroupConfig group(String id, String groupId, String roleId)
            throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {"GroupId":"%s","RoleIds":["%s"]}
                        """.formatted(groupId, roleId)), new ExtraInfo());
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
