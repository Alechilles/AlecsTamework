package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpoint;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCapture;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
        List<CompanionEntityCheckpointCapture> checkpoints = new ArrayList<>();
        try (SnapshotScope scope = SnapshotScope.install()) {
            Ref<EntityStore> npcRef = scope.store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(npcUuid);
            npc.setRoleName("Tamed_Sheep");
            scope.store.put(npcRef, NPCEntity.getComponentType(), npc);
            scope.store.put(npcRef, TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(ownerUuid, "Owner"));
            scope.addCheckpointComponents(npcRef, npcUuid);

            CommandLinkedNpcStateSnapshotService service =
                    new CommandLinkedNpcStateSnapshotService(
                            (snapshot, worldKey) -> {
                                published.add(new PublishedProfile(snapshot, worldKey));
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            },
                            new LoadedNpcIdentityIndex(),
                            checkpoint -> {
                                checkpoints.add(checkpoint);
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            }
                    );

            service.refreshFromEntity(npcRef, scope.store);

            assertNull(service.getSnapshot(npcUuid));
            assertEquals(List.of(), published);
            assertEquals(List.of(), checkpoints);

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
            assertEquals(1, checkpoints.size());
            assertEquals(CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    checkpoints.getFirst().boundary());
            assertEquals(npcUuid, checkpoints.getFirst().alias().value());
        }
    }

    @Test
    void linkedRefreshPublishesBaselineCheckpointAfterProfile() throws Exception {
        UUID npcUuid = UUID.fromString("93000000-0000-0000-0000-000000000003");
        UUID ownerUuid = UUID.fromString("93000000-0000-0000-0000-000000000004");
        List<PublishedProfile> published = new ArrayList<>();
        List<CompanionEntityCheckpointCapture> checkpoints = new ArrayList<>();
        try (SnapshotScope scope = SnapshotScope.install()) {
            Ref<EntityStore> npcRef = scope.store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(npcUuid);
            npc.setRoleName("Tamed_Sheep");
            scope.store.put(npcRef, NPCEntity.getComponentType(), npc);
            scope.store.put(npcRef, TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(ownerUuid, "Owner"));
            scope.store.put(npcRef, TameworkCommandLinksComponent.getComponentType(),
                    new TameworkCommandLinksComponent(ownerUuid, new String[] {"tool-a"}));
            scope.addCheckpointComponents(npcRef, npcUuid);

            CommandLinkedNpcStateSnapshotService service =
                    new CommandLinkedNpcStateSnapshotService(
                            (snapshot, worldKey) -> {
                                published.add(new PublishedProfile(snapshot, worldKey));
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            },
                            new LoadedNpcIdentityIndex(),
                            checkpoint -> {
                                checkpoints.add(checkpoint);
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            }
                    );

            service.refreshFromEntity(npcRef, scope.store);

            assertEquals(1, published.size());
            assertEquals(1, checkpoints.size());
            assertEquals(CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    checkpoints.getFirst().boundary());
            assertEquals(npcUuid, checkpoints.getFirst().alias().value());
            assertEquals(ownerUuid, checkpoints.getFirst().ownerId().value());
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

    @Test
    void ordinaryRefreshStillIgnoresMissingEntityInputs() {
        CommandLinkedNpcStateSnapshotService service =
                new CommandLinkedNpcStateSnapshotService();

        assertDoesNotThrow(() -> service.refreshFromEntity(null, null));
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
        private final ComponentType<EntityStore, NPCEntity> npcType;
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType;
        private final ComponentType<EntityStore, NPCMountComponent> mountType =
                new ComponentType<>();
        private final ComponentType<EntityStore, UUIDComponent> uuidType;
        private final ComponentType<EntityStore, TransformComponent> transformType;
        private final TestEntityComponentStore store;

        private SnapshotScope(Object oldTamework, Object oldEntityModule,
                              Object oldMountPlugin)
                throws Exception {
            this.oldTamework = oldTamework;
            this.oldEntityModule = oldEntityModule;
            this.oldMountPlugin = oldMountPlugin;
            this.npcType = new ComponentType<>();
            this.ownerType = EntityStore.REGISTRY.registerComponent(
                    TameworkOwnerComponent.class, "TestAdminSpawnSnapshotOwner",
                    TameworkOwnerComponent.CODEC
            );
            this.linksType = EntityStore.REGISTRY.registerComponent(
                    TameworkCommandLinksComponent.class, "TestAdminSpawnSnapshotLinks",
                    TameworkCommandLinksComponent.CODEC
            );
            this.tamedType = EntityStore.REGISTRY.registerComponent(
                    TameworkTamedComponent.class, "TestAdminSpawnSnapshotTamed",
                    TameworkTamedComponent.CODEC
            );
            this.uuidType = EntityStore.REGISTRY.registerComponent(
                    UUIDComponent.class, "TestAdminSpawnSnapshotUuid", UUIDComponent.CODEC
            );
            this.transformType = EntityStore.REGISTRY.registerComponent(
                    TransformComponent.class, "TestAdminSpawnSnapshotTransform",
                    TransformComponent.CODEC
            );
            TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            this.store = new TestEntityComponentStore(new EntityStore(world)) {
                @Override
                public com.hypixel.hytale.component.Holder<EntityStore> copySerializableEntity(
                        Ref<EntityStore> reference) {
                    var holder = EntityStore.REGISTRY.newHolder();
                    holder.addComponent(uuidType, new UUIDComponent(getComponent(reference, uuidType).getUuid()));
                    holder.addComponent(ownerType, getComponent(reference, ownerType).clone());
                    holder.addComponent(tamedType, getComponent(reference, tamedType).clone());
                    holder.addComponent(transformType, getComponent(reference, transformType).clone());
                    var links = getComponent(reference, linksType);
                    if (links != null) holder.addComponent(linksType, links.clone());
                    return holder;
                }
            };
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
            setField(entityModule, EntityModule.class, "uuidComponentType", scope.uuidType);
            setField(entityModule, EntityModule.class, "transformComponentType", scope.transformType);
            entityModuleInstance.set(null, entityModule);

            Tamework tamework = (Tamework) unsafe().allocateInstance(Tamework.class);
            setField(tamework, Tamework.class, "ownerComponentType", scope.ownerType);
            setField(tamework, Tamework.class, "commandLinksComponentType", scope.linksType);
            setField(tamework, Tamework.class, "tamedComponentType", scope.tamedType);
            tameworkInstance.set(null, tamework);
            MountPlugin mountPlugin = (MountPlugin) unsafe().allocateInstance(
                    MountPlugin.class
            );
            setField(mountPlugin, MountPlugin.class, "mountComponentType", scope.mountType);
            mountPluginInstance.set(null, mountPlugin);
            return scope;
        }

        private void addCheckpointComponents(
                Ref<EntityStore> reference,
                UUID npcUuid
        ) {
            store.put(reference, uuidType, new UUIDComponent(npcUuid));
            store.put(reference, tamedType, new TameworkTamedComponent(true));
            store.put(reference, transformType, new TransformComponent(
                    new org.joml.Vector3d(1.0D, 2.0D, 3.0D), new Rotation3f()
            ));
        }

        @Override
        public void close() throws Exception {
            store.close();
            EntityStore.REGISTRY.unregisterComponent(transformType);
            EntityStore.REGISTRY.unregisterComponent(uuidType);
            EntityStore.REGISTRY.unregisterComponent(tamedType);
            EntityStore.REGISTRY.unregisterComponent(linksType);
            EntityStore.REGISTRY.unregisterComponent(ownerType);
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
