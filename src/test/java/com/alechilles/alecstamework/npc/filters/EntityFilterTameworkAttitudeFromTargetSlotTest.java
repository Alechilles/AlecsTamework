package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import java.lang.reflect.Field;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityFilterTameworkAttitudeFromTargetSlotTest {

    @Test
    void playerSourceUsesCandidateNpcAttitudeWithoutTickFailure() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             NpcPluginScope npcPlugin = NpcPluginScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(
                     allocate(EntityStore.class))) {
            Ref<EntityStore> companionRef = store.createReference();
            Ref<EntityStore> ownerRef = store.createReference();
            Ref<EntityStore> candidateRef = store.createReference();
            MarkedEntitySupport markedTargets = new MarkedEntitySupport();
            setObject(markedTargets, MarkedEntitySupport.class, "entityTargets",
                    new Ref<?>[] {ownerRef});
            store.put(companionRef, npcPlugin.markedEntityType, markedTargets);

            DirectionalWorldSupport candidateAttitude = allocate(DirectionalWorldSupport.class);
            candidateAttitude.source = candidateRef;
            candidateAttitude.target = ownerRef;
            Role candidateRole = allocate(Role.class);
            NPCEntity candidateNpc = new NPCEntity();
            setObject(candidateNpc, NPCEntity.class, "role", candidateRole);
            store.put(candidateRef, NPCEntity.getComponentType(), candidateNpc);
            store.put(companionRef, npcPlugin.worldSupportType, candidateAttitude);
            store.put(candidateRef, npcPlugin.worldSupportType, candidateAttitude);

            EntityFilterTameworkAttitudeFromTargetSlot filter =
                    allocate(EntityFilterTameworkAttitudeFromTargetSlot.class);
            setInt(filter, EntityFilterTameworkAttitudeFromTargetSlot.class,
                    "sourceTargetSlot", 0);
            setObject(filter, EntityFilterTameworkAttitudeFromTargetSlot.class,
                    "attitudes", EnumSet.of(Attitude.HOSTILE));
            setBoolean(filter, EntityFilterTameworkAttitudeFromTargetSlot.class,
                    "useSelfWhenSourceMissing", false);
            Role role = allocate(Role.class);

            boolean matches = assertDoesNotThrow(
                    () -> filter.matchesEntity(companionRef, candidateRef, role, store));

            assertTrue(matches);
        }
    }

    private static final class DirectionalWorldSupport extends WorldSupport {
        private Ref<EntityStore> source;
        private Ref<EntityStore> target;

        private DirectionalWorldSupport() {
            super((com.hypixel.hytale.server.npc.asset.builder.SupportConfigBuilder<?>) null,
                    (BuilderSupport) null);
        }

        @Override
        public Attitude getAttitude(Ref<EntityStore> sourceRef,
                                    Ref<EntityStore> targetRef,
                                    com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor) {
            if (sourceRef != source || targetRef != target) {
                throw new NullPointerException("Attitude source does not own this WorldSupport");
            }
            return Attitude.HOSTILE;
        }
    }

    private static final class NpcPluginScope implements AutoCloseable {
        private final NPCPlugin previous;
        private final ComponentType<EntityStore, MarkedEntitySupport> markedEntityType =
                new ComponentType<>();
        private final ComponentType<EntityStore, WorldSupport> worldSupportType =
                new ComponentType<>();

        private NpcPluginScope(NPCPlugin previous) {
            this.previous = previous;
        }

        private static NpcPluginScope install() throws Exception {
            Field instance = staticField(NPCPlugin.class, "instance");
            NpcPluginScope scope = new NpcPluginScope((NPCPlugin) instance.get(null));
            NPCPlugin plugin = allocate(NPCPlugin.class);
            setObject(plugin, NPCPlugin.class, "markedEntitySupportComponentType",
                    scope.markedEntityType);
            setObject(plugin, NPCPlugin.class, "worldSupportComponentType",
                    scope.worldSupportType);
            instance.set(null, plugin);
            return scope;
        }

        @Override
        public void close() throws Exception {
            staticField(NPCPlugin.class, "instance").set(null, previous);
        }
    }

    private static void setObject(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static void setInt(Object target, Class<?> owner, String name, int value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putInt(target, unsafe().objectFieldOffset(field), value);
    }

    private static void setBoolean(Object target, Class<?> owner, String name, boolean value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putBoolean(target, unsafe().objectFieldOffset(field), value);
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(unsafe().allocateInstance(type));
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
