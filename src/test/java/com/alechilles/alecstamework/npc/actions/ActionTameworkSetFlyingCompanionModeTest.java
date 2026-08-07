package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTameworkSetFlyingCompanionModeTest {

    @Test
    void fallModeImmediatelySwitchesTheNpcToItsWalkController() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(
                     (EntityStore) unsafe().allocateInstance(EntityStore.class))) {
            NPCEntity npc = new NPCEntity();
            Ref<EntityStore> npcRef = store.createReference();
            store.put(npcRef, NPCEntity.getComponentType(), npc);

            BuilderActionTameworkSetFlyingCompanionMode builder =
                    new BuilderActionTameworkSetFlyingCompanionMode().readConfig(
                            JsonParser.parseString("{\"Mode\":\"Fall\"}"));
            BuilderSupport support = new BuilderSupport(
                    new BuilderManager(), npc, null, new ExecutionContext(), builder, new RoleStats());
            ActionTameworkSetFlyingCompanionMode action =
                    new ActionTameworkSetFlyingCompanionMode(builder, support);
            RecordingRole role = (RecordingRole) unsafe().allocateInstance(RecordingRole.class);

            assertTrue(action.execute(npcRef, role, null, 0.0, store));
            assertEquals("Walk", role.requestedController);
        }
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class RecordingRole extends Role {
        private String requestedController;

        private RecordingRole() {
            super((BuilderRole) null, (BuilderSupport) null);
        }

        @Override
        public boolean setActiveMotionController(Ref<EntityStore> ref,
                                                 NPCEntity npcComponent,
                                                 String name,
                                                 ComponentAccessor<EntityStore> componentAccessor) {
            requestedController = name;
            return true;
        }
    }
}
