package com.alechilles.alecstamework.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.RevivalActivityView;
import com.alechilles.alecstamework.api.SummoningActivityView;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Behavior checks for post-commit lifecycle activity payloads. */
class LifecycleActivityPublisherTest {
    private static final UUID OPERATION = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");

    @Test
    void publishesRevivalAndSummoningFromCommittedEvidence() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        LifecycleActivityPublisher publisher = new LifecycleActivityPublisher(
                published::add, managedRegistry());

        publisher.publishRevival(
                OPERATION, OWNER, OWNER, COMPANION, "RoleA", "profile-a",
                "paid_command", "active", "settled", true, -3_000L);
        publisher.publishSummoning(
                OPERATION, ActivityIds.SUMMON_SUCCESS, OWNER, "profile-a",
                "family-a", COMPANION, "summon_started", 5_000L,
                -2_000L);
        publisher.publishSummoning(
                UUID.randomUUID(), ActivityIds.RECALL, OWNER, "profile-a",
                "family-a", COMPANION, "stored", null, -1_000L);

        RevivalActivityView revival = assertInstanceOf(
                RevivalActivityView.class, published.get(0));
        assertEquals(OPERATION, revival.header().operationId());
        assertEquals(ActivityIds.REVIVE_SUCCESS, revival.header().actionId());
        assertEquals("paid_command", revival.revivalSource());
        assertEquals("RoleA", revival.roleId());
        assertEquals(java.util.Set.of("runeteria:family"), revival.groupIds());
        assertEquals("settled", revival.paymentOutcome());
        assertTrue(revival.recovered());
        assertEquals(Instant.ofEpochMilli(-3_000L),
                revival.header().occurredAt());

        SummoningActivityView summon = assertInstanceOf(
                SummoningActivityView.class, published.get(1));
        assertEquals(ActivityIds.SUMMON_SUCCESS, summon.header().actionId());
        assertEquals(5_000L, summon.expiresAtMs());
        assertEquals(Instant.ofEpochMilli(-2_000L),
                summon.header().occurredAt());
        SummoningActivityView recall = assertInstanceOf(
                SummoningActivityView.class, published.get(2));
        assertEquals(ActivityIds.RECALL, recall.header().actionId());
        assertNull(recall.expiresAtMs());
        assertEquals(Instant.ofEpochMilli(-1_000L),
                recall.header().occurredAt());
    }

    private static ManagedActivityConfigRegistry managedRegistry() throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(group("group", "runeteria:family", "RoleA")), 1L
        ).applied());
        ManagedActivityConfigRegistry managed = new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig profile = decode("husbandry", """
                {
                  "ProfileId":"runeteria:husbandry",
                  "ProviderId":"runeteria:provider",
                  "ProviderContractVersion":1,
                  "RequiredCapabilities":["ACTIVITY_FEED_V2"],
                  "Domains":[{"DomainId":"runeteria:owned","Owned":true}],
                  "Families":[{"GroupId":"runeteria:family","GateKey":"runeteria:gate","Weight":1}],
                  "Activities":{
                    "Feed":"runeteria:feed",
                    "HarvestContexts":{"Milk":"runeteria:milk"},
                    "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                    "BreedingSuccess":"runeteria:breed",
                    "TameSuccess":"runeteria:tame_success",
                    "NeedSatisfied":"runeteria:feed"
                  }
                }
                """);
        ManagedActivityConfigRegistry.ReloadResult reload = managed.replace(
                List.of(profile), 1L);
        assertTrue(reload.applied(), reload.error());
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
            String roleId
    ) throws Exception {
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
