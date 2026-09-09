package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;

/** Regression coverage for required profile capture after an admitted admin spawn. */
class AdminSpawnProfileSnapshotTest {
    @Test
    void ownedUnlinkedAdminSpawnPublishesWhileOrdinaryRefreshSkipsIt()
            throws Exception {
        UUID npcUuid = UUID.fromString("93000000-0000-0000-0000-000000000001");
        UUID ownerUuid = UUID.fromString("93000000-0000-0000-0000-000000000002");
        List<PublishedProfile> published = new ArrayList<>();
        try (SnapshotScope scope = SnapshotScope.install()) {
            Ref<EntityStore> npcRef = scope.store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(npcUuid);
            npc.setRoleName("Tamed_Sheep");
            scope.store.put(npcRef, NPCEntity.getComponentType(), npc);
            scope.store.put(npcRef, TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(ownerUuid, "Owner"));

            CommandLinkedNpcStateSnapshotService service =
                    new CommandLinkedNpcStateSnapshotService((snapshot, worldKey) -> {
                        published.add(new PublishedProfile(snapshot, worldKey));
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    });

            service.refreshFromEntity(npcRef, scope.store);

            assertNull(service.getSnapshot(npcUuid));
            assertEquals(List.of(), published);

            service.publishAdminSpawnProfile(npcRef, scope.store)
                    .toCompletableFuture().join();

            assertEquals(1, published.size());
            PublishedProfile profile = published.getFirst();
            assertEquals("admin-spawn-test", profile.worldKey());
            assertEquals(npcUuid, profile.snapshot().npcUuid());
            assertEquals(ownerUuid, profile.snapshot().ownerId());
            assertArrayEquals(new String[0], profile.snapshot().toolIds());
            assertTrue(profile.snapshot().tamed());
            assertEquals(profile.snapshot(), service.getSnapshot(npcUuid));
        }
    }

    @Test
    void requiredAdminCaptureFailsForMissingNpcInsteadOfSilentlySucceeding()
            throws Exception {
        try (SnapshotScope scope = SnapshotScope.install()) {
            CommandLinkedNpcStateSnapshotService service =
                    new CommandLinkedNpcStateSnapshotService();
            Ref<EntityStore> missingNpc = scope.store.createReference();

            assertThrows(CompletionException.class, () ->
                    service.publishAdminSpawnProfile(missingNpc, scope.store)
                            .toCompletableFuture().join()
            );
        }
    }

    private record PublishedProfile(
            CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            String worldKey
    ) {
    }

    private static final class SnapshotScope implements AutoCloseable {
        private final Object oldTamework;
        private final Object oldEntityModule;
        private final Object oldMountPlugin;
        private final ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                new ComponentType<>();
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                new ComponentType<>();
        private final ComponentType<EntityStore, NPCMountComponent> mountType =
                new ComponentType<>();
        private final TestEntityComponentStore store;

        private SnapshotScope(Object oldTamework, Object oldEntityModule,
                              Object oldMountPlugin)
                throws Exception {
            this.oldTamework = oldTamework;
            this.oldEntityModule = oldEntityModule;
            this.oldMountPlugin = oldMountPlugin;
            TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            this.store = new TestEntityComponentStore(new EntityStore(world));
        }

        private static SnapshotScope install() throws Exception {
            Field tameworkInstance = staticField(Tamework.class, "instance");
            Field entityModuleInstance = staticField(EntityModule.class, "instance");
            Field mountPluginInstance = staticField(MountPlugin.class, "instance");
            SnapshotScope scope = new SnapshotScope(
                    tameworkInstance.get(null),
                    entityModuleInstance.get(null),
                    mountPluginInstance.get(null)
            );
            EntityModule entityModule = (EntityModule) unsafe().allocateInstance(
                    EntityModule.class
            );
            Map<Class<?>, ComponentType<EntityStore, ?>> types = new HashMap<>();
            types.put(NPCEntity.class, scope.npcType);
            setField(entityModule, EntityModule.class, "classToComponentType", types);
            entityModuleInstance.set(null, entityModule);

            Tamework tamework = (Tamework) unsafe().allocateInstance(Tamework.class);
            setField(tamework, Tamework.class, "ownerComponentType", scope.ownerType);
            setField(tamework, Tamework.class, "commandLinksComponentType", scope.linksType);
            tameworkInstance.set(null, tamework);
            MountPlugin mountPlugin = (MountPlugin) unsafe().allocateInstance(
                    MountPlugin.class
            );
            setField(mountPlugin, MountPlugin.class, "mountComponentType", scope.mountType);
            mountPluginInstance.set(null, mountPlugin);
            return scope;
        }

        @Override
        public void close() throws Exception {
            store.close();
            staticField(Tamework.class, "instance").set(null, oldTamework);
            staticField(EntityModule.class, "instance").set(null, oldEntityModule);
            staticField(MountPlugin.class, "instance").set(null, oldMountPlugin);
        }
    }

    private static final class TestWorld extends World {
        private TestWorld() throws IOException {
            super("admin-spawn-test", Path.of("."), new WorldConfig());
        }

        @Override
        public String getName() {
            return "admin-spawn-test";
        }
    }

    private static Field staticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, Class<?> owner, String name,
                                 Object value) throws Exception {
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
