package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasTalent;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests self-targeted purchased-talent matching. */
class SensorTameworkHasTalentTest {

    @Test
    void matchingNpcPurchasedTalentMatchesConfiguredId() {
        TameworkTalentsComponent npcTalents = new TameworkTalentsComponent(
                "miniwyvern", 1, new String[] {"DraconicProjectile"}
        );

        assertTrue(SensorTameworkHasTalent.matchesTalent(npcTalents, "DraconicProjectile"));
    }

    @Test
    void anotherNpcsPurchasedTalentDoesNotMatchThisNpc() {
        TameworkTalentsComponent thisNpcTalents = new TameworkTalentsComponent(
                "miniwyvern", 0, new String[0]
        );
        TameworkTalentsComponent anotherNpcTalents = new TameworkTalentsComponent(
                "miniwyvern", 1, new String[] {"DraconicProjectile"}
        );

        assertFalse(SensorTameworkHasTalent.matchesTalent(thisNpcTalents, "DraconicProjectile"));
        assertTrue(SensorTameworkHasTalent.matchesTalent(anotherNpcTalents, "DraconicProjectile"));
    }

    @Test
    void evaluatingTalentlessNpcDoesNotReadAnotherNpcTalentComponent() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            ComponentType<EntityStore, TameworkTalentsComponent> talentType = registry.registerComponent(
                    TameworkTalentsComponent.class, TameworkTalentsComponent::new
            );
            Ref<EntityStore> talentlessNpc = store.addEntity(registry.newHolder(), AddReason.SPAWN);
            Holder<EntityStore> purchasedTalentNpc = registry.newHolder();
            purchasedTalentNpc.addComponent(
                    talentType,
                    new TameworkTalentsComponent("miniwyvern", 1, new String[] {"DraconicProjectile"})
            );
            Ref<EntityStore> anotherNpc = store.addEntity(purchasedTalentNpc, AddReason.SPAWN);
            BuilderSensorTameworkHasTalent builder = new BuilderSensorTameworkHasTalent().readConfig(
                    JsonParser.parseString("{\"TalentId\": \"DraconicProjectile\"}")
            );
            BuilderSupport support = new BuilderSupport(
                    new BuilderManager(), new NPCEntity(), null, new ExecutionContext(), builder, new RoleStats()
            );
            SensorTameworkHasTalent sensor = new SensorTameworkHasTalent(builder, support, talentType);

            assertFalse(sensor.matches(talentlessNpc, null, 0.0, store));
            assertTrue(sensor.matches(anotherNpc, null, 0.0, store));
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    @Test
    void missingTalentsOrBlankTalentIdFailClosed() {
        TameworkTalentsComponent npcTalents = new TameworkTalentsComponent(
                "miniwyvern", 1, new String[] {"DraconicProjectile"}
        );

        assertFalse(SensorTameworkHasTalent.matchesTalent(null, "DraconicProjectile"));
        assertFalse(SensorTameworkHasTalent.matchesTalent(npcTalents, " "));
    }
}
