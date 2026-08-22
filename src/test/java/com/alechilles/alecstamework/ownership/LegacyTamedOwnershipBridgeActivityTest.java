package com.alechilles.alecstamework.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.TameActivityView;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Behavior check for the newly claimed legacy-tame publication seam. */
class LegacyTamedOwnershipBridgeActivityTest {
    @AfterEach
    void clearRuntime() {
        ActivityRuntime.clear();
    }

    @Test
    void claimedLegacyTamePublishesItsResolvedIdentity() throws Exception {
        List<ActivityView> published = new ArrayList<>();
        ActivityRuntime.install(published::add, managedRegistry());
        UUID owner = UUID.randomUUID();
        UUID companion = UUID.randomUUID();

        LegacyTamedOwnershipBridge.publishClaimedTame(
                owner, companion, "RoleA");

        TameActivityView activity = assertInstanceOf(
                TameActivityView.class, published.getFirst());
        assertEquals(owner, activity.ownerId());
        assertEquals(companion, activity.companionId());
        assertEquals("RoleA", activity.roleId());
    }

    private static ManagedActivityConfigRegistry managedRegistry() throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(List.of(group()), 1L).applied());
        ManagedActivityConfigRegistry managed = new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig profile = TwManagedActivityConfig.CODEC.decode(
                BsonDocument.parse("""
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
                        """), new ExtraInfo());
        set(profile, "id", "husbandry");
        assertTrue(managed.replace(List.of(profile), 1L).applied());
        return managed;
    }

    private static TwPopulationGroupConfig group() throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {"GroupId":"runeteria:family","RoleIds":["RoleA"]}
                        """), new ExtraInfo());
        set(config, "id", "group");
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
