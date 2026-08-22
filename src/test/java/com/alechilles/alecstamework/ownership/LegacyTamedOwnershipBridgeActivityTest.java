package com.alechilles.alecstamework.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.TameActivityView;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

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
        try (SimpleClaimsDamageHytaleFixture.HytaleModuleScope ignored =
                     SimpleClaimsDamageHytaleFixture.HytaleModuleScope.install()) {
            ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                    new ComponentType<>();
            ComponentType<EntityStore, UUIDComponent> uuidType = new ComponentType<>();
            set(Tamework.getInstance(), "tamedComponentType", tamedType);
            componentTypes().put(UUIDComponent.class, uuidType);
            set(entityModule(), "uuidComponentType", uuidType);
            try (TestEntityComponentStore store = new TestEntityComponentStore(
                    new EntityStore(null))) {
                Ref<EntityStore> npcRef = store.createReference();
                NPCEntity npc = new NPCEntity();
                npc.setRoleName("Tamed_RoleA");
                store.put(npcRef, NPCEntity.getComponentType(), npc);
                store.put(npcRef, uuidType, new UUIDComponent(companion));
                Player player = (Player) unsafe().allocateInstance(Player.class);
                player.setLegacyUUID(owner);

                LegacyTamedOwnershipBridge.ClaimResult result =
                        LegacyTamedOwnershipBridge.claimForPlayerIfEligible(
                                npcRef, store, player);

                assertTrue(result.isClaimed());
                assertEquals(owner, store.getComponent(
                        npcRef, TameworkOwnerComponent.getComponentType()).getOwnerId());
                assertTrue(store.getComponent(npcRef, tamedType).isTamed());
            }
        }

        TameActivityView activity = assertInstanceOf(
                TameActivityView.class, published.getFirst());
        assertEquals(owner, activity.ownerId());
        assertEquals(companion, activity.companionId());
        assertEquals("Tamed_RoleA", activity.roleId());
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
                        {"GroupId":"runeteria:family","RoleIds":["Tamed_RoleA"]}
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
}
