package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Regression for preserving owned tames even when no command item is linked. */
class CommandUnlinkedOwnedSnapshotTest {
    private static final UUID OWNER = UUID.fromString(
            "94000000-0000-0000-0000-000000000001");
    private static final UUID OWNED_NPC = UUID.fromString(
            "94000000-0000-0000-0000-000000000002");
    private static final UUID WILD_NPC = UUID.fromString(
            "94000000-0000-0000-0000-000000000003");

    @Test
    void capturesOwnedTameWithoutCommandLinksButSkipsWildUnlinkedNpc() throws Exception {
        try (SnapshotScope scope = SnapshotScope.install()) {
            Ref<EntityStore> ownedRef = scope.store.createReference();
            NPCEntity ownedNpc = npc(OWNED_NPC, "Tamed_Pig");
            scope.store.put(ownedRef, scope.npcType, ownedNpc);
            scope.store.put(ownedRef, scope.ownerType,
                    new TameworkOwnerComponent(OWNER, "Owner"));
            scope.store.put(ownedRef, scope.tamedType, new TameworkTamedComponent(true));
            scope.store.put(ownedRef, scope.transformType, new TransformComponent());

            Ref<EntityStore> wildRef = scope.store.createReference();
            scope.store.put(wildRef, scope.npcType, npc(WILD_NPC, "Boar"));
            scope.store.put(wildRef, scope.tamedType, new TameworkTamedComponent(false));
            scope.store.put(wildRef, scope.transformType, new TransformComponent());

            CommandLiveNpcSnapshotFactory factory = new CommandLiveNpcSnapshotFactory();
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot ownedSnapshot =
                    factory.capture(ownedRef, scope.store, ownedNpc, null);
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot wildSnapshot =
                    factory.capture(wildRef, scope.store,
                            scope.store.getComponent(wildRef, scope.npcType), null);

            assertNotNull(ownedSnapshot,
                    "An owned tame must produce a durable profile observation without links.");
            assertEquals(OWNED_NPC, ownedSnapshot.npcUuid());
            assertEquals(OWNER, ownedSnapshot.ownerId());
            assertTrue(ownedSnapshot.tamed());
            assertArrayEquals(new String[0], ownedSnapshot.toolIds());
            assertNull(wildSnapshot,
                    "A wild, unowned NPC must remain outside routine profile snapshots.");
        }
    }

    private static NPCEntity npc(UUID uuid, String roleId) {
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(uuid);
        npc.setRoleName(roleId);
        return npc;
    }

    @Test
    void doesNotAdoptDisposableBondedProjectionAsGenericCompanion() throws Exception {
        try (SnapshotScope scope = SnapshotScope.install()) {
            Ref<EntityStore> reference = scope.store.createReference();
            NPCEntity npc = npc(OWNED_NPC, "Tamed_Pig");
            scope.store.put(reference, scope.npcType, npc);
            scope.store.put(reference, scope.ownerType,
                    new TameworkOwnerComponent(OWNER, "Owner"));
            scope.store.put(reference, scope.tamedType, new TameworkTamedComponent(true));
            scope.store.put(reference, scope.projectionType,
                    new TameworkProjectionIdentityComponent(
                            UUID.randomUUID().toString(), "lease-a",
                            TameworkProjectionIdentityComponent.KIND_BONDED_COMPANION,
                            null, OWNED_NPC, 1L));

            assertNull(new CommandLiveNpcSnapshotFactory().capture(
                    reference, scope.store, npc, null),
                    "A bonded lease owns this projection; it must not create a generic profile.");
        }
    }

    private static final class SnapshotScope implements AutoCloseable {
        private final Object oldTamework;
        private final Object oldEntityModule;
        private final Object oldMountPlugin;
        private final ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
        private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType;
        private final ComponentType<EntityStore, TransformComponent> transformType;
        private final ComponentType<EntityStore, NPCMountComponent> mountType =
                new ComponentType<>();
        private final TestEntityComponentStore store;

        private SnapshotScope(Object oldTamework, Object oldEntityModule,
                              Object oldMountPlugin) throws Exception {
            this.oldTamework = oldTamework;
            this.oldEntityModule = oldEntityModule;
            this.oldMountPlugin = oldMountPlugin;
            this.ownerType = EntityStore.REGISTRY.registerComponent(
                    TameworkOwnerComponent.class, "TestUnlinkedSnapshotOwner",
                    TameworkOwnerComponent.CODEC);
            this.linksType = EntityStore.REGISTRY.registerComponent(
                    TameworkCommandLinksComponent.class, "TestUnlinkedSnapshotLinks",
                    TameworkCommandLinksComponent.CODEC);
            this.tamedType = EntityStore.REGISTRY.registerComponent(
                    TameworkTamedComponent.class, "TestUnlinkedSnapshotTamed",
                    TameworkTamedComponent.CODEC);
            this.projectionType = EntityStore.REGISTRY.registerComponent(
                    TameworkProjectionIdentityComponent.class, "TestUnlinkedSnapshotProjection",
                    TameworkProjectionIdentityComponent.CODEC);
            this.transformType = EntityStore.REGISTRY.registerComponent(
                    TransformComponent.class, "TestUnlinkedSnapshotTransform",
                    TransformComponent.CODEC);

            TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            this.store = new TestEntityComponentStore(new EntityStore(world));

            EntityModule entityModule = (EntityModule) unsafe().allocateInstance(
                    EntityModule.class);
            Map<Class<?>, ComponentType<EntityStore, ?>> types = new HashMap<>();
            types.put(NPCEntity.class, npcType);
            setField(entityModule, EntityModule.class, "classToComponentType", types);
            setField(entityModule, EntityModule.class, "transformComponentType", transformType);
            staticField(EntityModule.class, "instance").set(null, entityModule);

            Tamework tamework = (Tamework) unsafe().allocateInstance(Tamework.class);
            setField(tamework, Tamework.class, "ownerComponentType", ownerType);
            setField(tamework, Tamework.class, "commandLinksComponentType", linksType);
            setField(tamework, Tamework.class, "tamedComponentType", tamedType);
            setField(tamework, Tamework.class, "projectionIdentityComponentType", projectionType);
            staticField(Tamework.class, "instance").set(null, tamework);

            MountPlugin mountPlugin = (MountPlugin) unsafe().allocateInstance(
                    MountPlugin.class);
            setField(mountPlugin, MountPlugin.class, "mountComponentType", mountType);
            staticField(MountPlugin.class, "instance").set(null, mountPlugin);
        }

        private static SnapshotScope install() throws Exception {
            SnapshotScope scope = new SnapshotScope(
                    staticField(Tamework.class, "instance").get(null),
                    staticField(EntityModule.class, "instance").get(null),
                    staticField(MountPlugin.class, "instance").get(null));
            return scope;
        }

        @Override
        public void close() throws Exception {
            store.close();
            EntityStore.REGISTRY.unregisterComponent(transformType);
            EntityStore.REGISTRY.unregisterComponent(tamedType);
            EntityStore.REGISTRY.unregisterComponent(projectionType);
            EntityStore.REGISTRY.unregisterComponent(linksType);
            EntityStore.REGISTRY.unregisterComponent(ownerType);
            staticField(Tamework.class, "instance").set(null, oldTamework);
            staticField(EntityModule.class, "instance").set(null, oldEntityModule);
            staticField(MountPlugin.class, "instance").set(null, oldMountPlugin);
        }
    }

    private static final class TestWorld extends World {
        private TestWorld() throws IOException {
            super("unlinked-snapshot-test", Path.of("."), new WorldConfig());
        }
    }

    private static Field staticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
