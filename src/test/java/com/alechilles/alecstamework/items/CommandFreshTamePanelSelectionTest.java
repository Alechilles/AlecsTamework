package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Regression for newly tamed owned animals whose profile projection is not available yet. */
class CommandFreshTamePanelSelectionTest {
    private static final UUID OWNER = UUID.fromString(
            "78000000-0000-0000-0000-000000000001");
    private static final UUID NPC = UUID.fromString(
            "78000000-0000-0000-0000-000000000002");

    @Test
    void freshOwnedAnimalReflectsItemSelectionWithoutProfileProjection() throws Exception {
        try (TestScope scope = TestScope.install()) {
            Ref<EntityStore> playerRef = scope.store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            player.loadIntoWorld(scope.world);
            player.setReference(playerRef);
            scope.world.references.put(OWNER, playerRef);

            Ref<EntityStore> npcRef = scope.store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(NPC);
            scope.store.put(npcRef, scope.npcType, npc);
            scope.store.put(npcRef, scope.entitySupportType,
                    (com.hypixel.hytale.server.npc.role.support.EntitySupport) unsafe().allocateInstance(
                            com.hypixel.hytale.server.npc.role.support.EntitySupport.class));
            scope.store.put(npcRef, scope.ownerType,
                    new TameworkOwnerComponent(OWNER, "Owner"));
            var distantPosition = new TransformComponent();
            distantPosition.getPosition().set(100_000.0, 0.0, 100_000.0);
            scope.store.put(npcRef, scope.transformType, distantPosition);
            scope.world.references.put(NPC, npcRef);

            TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                    new BsonDocument(), new ExtraInfo());
            ItemStack stack = new MetadataStack("test:flute", new BsonDocument());
            var liveIndex = new com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex();
            liveIndex.observe(NPC, OWNER, scope.world.getName());
            CommandPanelEntrySourceService source = entrySource(liveIndex);

            assertFalse(row(source, player, scope.store, stack, config).active(),
                    "A fresh owned animal starts unselected when its item has no record.");

            LinkedNpcRecord ownedRecord = new LinkedNpcRecord(
                    NPC, null, null, null, null, "Companion", null, "Cow",
                    null, false, false, null);
            CommandLinkMutationService mutations = new CommandLinkMutationService(
                    new CommandLinkedNpcRecordStore(), new CommandLinkPolicyService(),
                    new CommandNpcNameResolver());
            var selected = mutations.toggleLinkedNpcActive(stack, NPC, config, ownedRecord);
            assertTrue(selected.toggled);
            assertTrue(selected.active);

            var selectedRow = row(source, player, scope.store, selected.updatedItem, config);
            assertTrue(selectedRow.linked(),
                    "The selected item record must mark the live owned row as linked.");
            assertTrue(selectedRow.active(),
                    "The selected item record must mark the live owned row as active.");

            var page = new com.alechilles.alecstamework.ui.LinkedNpcPanelPageState();
            page.setPageSize(1);
            assertTrue(source.buildSnapshot(player, scope.store, selected.updatedItem, config, "flute", page)
                            .entries().stream().anyMatch(entry -> NPC.equals(entry.npcUuid())),
                    "Pagination must retain a newly tamed indexed animal before its profile is published.");

            var deselected = mutations.toggleLinkedNpcActive(
                    selected.updatedItem, NPC, config, ownedRecord);
            assertTrue(deselected.toggled);
            assertFalse(deselected.active);
            assertFalse(row(source, player, scope.store, deselected.updatedItem, config).active(),
                    "Deselecting the item record must make the live owned row inactive.");
            scope.store.put(npcRef, scope.ownerType,
                    new TameworkOwnerComponent(UUID.randomUUID(), "Other owner"));
            assertTrue(source.buildSnapshot(player, scope.store, stack, config, "flute").entries().isEmpty(),
                    "A stale owner-index candidate must not expose an animal after its ownership changes.");
        }
    }

    private static CommandPanelEntrySourceService entrySource(
            com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex liveIndex) {
        var names = new CommandNpcNameResolver();
        var policy = new CommandLinkPolicyService();
        var persistence = new CommandPersistenceView(new CommandPersistenceView.ProjectionLookup() {
            @Override
            public java.util.Optional<com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState> find(
                    com.alechilles.alecstamework.companion.identity.ProfileId id) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState> find(
                    com.alechilles.alecstamework.companion.identity.NpcAlias alias) {
                return java.util.Optional.empty();
            }
        });
        var linked = new CommandLinkedPanelEntryService(
                new CommandLinkedNpcRecordStore(), null, names, null,
                persistence, policy, new CommandGroupService(), null);
        return new CommandPanelEntrySourceService(
                linked, new CommandPanelPreferenceService(), policy, names,
                null, null, null, new CommandOwnedPanelRecordSource(Map::of), liveIndex);
    }

    private static com.alechilles.alecstamework.ui.LinkedNpcEntry row(
            CommandPanelEntrySourceService source,
            Player player,
            TestEntityComponentStore store,
            ItemStack stack,
            TwCommandItemConfig config) {
        return source.buildSnapshot(player, store, stack, config, "flute")
                .entries().stream().filter(entry -> NPC.equals(entry.npcUuid()))
                .findFirst().orElseThrow();
    }

    private static final class MetadataStack extends ItemStack {
        private MetadataStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(String key,
                                          com.hypixel.hytale.codec.Codec<T> codec,
                                          T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, codec.encode(value));
            }
            return new MetadataStack(itemId, next);
        }
    }

    private static final class TestScope implements AutoCloseable {
        private final Object oldTamework;
        private final Object oldEntityModule;
        private final Object oldMountPlugin;
        private final Object oldEntityStatsModule;
        private final Object oldNpcPlugin;
        private final ComponentType<EntityStore, com.hypixel.hytale.server.npc.role.support.EntitySupport> entitySupportType = new ComponentType<>();
        private final ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        private final ComponentType<EntityStore, TransformComponent> transformType = new ComponentType<>();
        private final ComponentType<EntityStore, NPCMountComponent> mountType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
        private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType;
        private final TestWorld world;
        private final TestEntityComponentStore store;

        private TestScope(Object oldTamework, Object oldEntityModule, Object oldMountPlugin,
                          Object oldEntityStatsModule)
                throws Exception {
            this.oldTamework = oldTamework;
            this.oldEntityModule = oldEntityModule;
            this.oldMountPlugin = oldMountPlugin;
            this.oldEntityStatsModule = oldEntityStatsModule;
            this.oldNpcPlugin = staticField(com.hypixel.hytale.server.npc.NPCPlugin.class, "instance").get(null);
            staticField(com.hypixel.hytale.server.npc.NPCPlugin.class, "instance").set(null,
                    unsafe().allocateInstance(com.hypixel.hytale.server.npc.NPCPlugin.class));
            setField(com.hypixel.hytale.server.npc.NPCPlugin.get(), com.hypixel.hytale.server.npc.NPCPlugin.class,
                    "entitySupportComponentType", entitySupportType);
            this.ownerType = EntityStore.REGISTRY.registerComponent(
                    TameworkOwnerComponent.class, "TestFreshTameOwner",
                    TameworkOwnerComponent.CODEC);
            this.projectionType = EntityStore.REGISTRY.registerComponent(
                    TameworkProjectionIdentityComponent.class, "TestFreshTameProjection",
                    TameworkProjectionIdentityComponent.CODEC);
            this.world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            this.world.references = new HashMap<>();
            TestEntityStore entityStore = new TestEntityStore(world);
            this.store = new TestEntityComponentStore(entityStore);
            entityStore.store = store;

            EntityModule entityModule = (EntityModule) unsafe().allocateInstance(EntityModule.class);
            Map<Class<?>, ComponentType<EntityStore, ?>> types = new HashMap<>();
            types.put(NPCEntity.class, npcType);
            setField(entityModule, EntityModule.class, "classToComponentType", types);
            setField(entityModule, EntityModule.class, "transformComponentType", transformType);
            staticField(EntityModule.class, "instance").set(null, entityModule);

            Tamework tamework = (Tamework) unsafe().allocateInstance(Tamework.class);
            setField(tamework, Tamework.class, "ownerComponentType", ownerType);
            setField(tamework, Tamework.class, "projectionIdentityComponentType", projectionType);
            staticField(Tamework.class, "instance").set(null, tamework);

            MountPlugin mountPlugin = (MountPlugin) unsafe().allocateInstance(MountPlugin.class);
            setField(mountPlugin, MountPlugin.class, "mountComponentType", mountType);
            staticField(MountPlugin.class, "instance").set(null, mountPlugin);

            EntityStatsModule entityStats = (EntityStatsModule) unsafe().allocateInstance(
                    EntityStatsModule.class);
            setField(entityStats, EntityStatsModule.class, "entityStatMapComponentType",
                    new ComponentType<EntityStore, EntityStatMap>());
            staticField(EntityStatsModule.class, "instance").set(null, entityStats);
        }

        private static TestScope install() throws Exception {
            return new TestScope(
                    staticField(Tamework.class, "instance").get(null),
                    staticField(EntityModule.class, "instance").get(null),
                    staticField(MountPlugin.class, "instance").get(null),
                    staticField(EntityStatsModule.class, "instance").get(null));
        }

        @Override
        public void close() throws Exception {
            store.close();
            staticField(com.hypixel.hytale.server.npc.NPCPlugin.class, "instance").set(null, oldNpcPlugin);
            EntityStore.REGISTRY.unregisterComponent(projectionType);
            EntityStore.REGISTRY.unregisterComponent(ownerType);
            staticField(Tamework.class, "instance").set(null, oldTamework);
            staticField(EntityModule.class, "instance").set(null, oldEntityModule);
            staticField(MountPlugin.class, "instance").set(null, oldMountPlugin);
            staticField(EntityStatsModule.class, "instance").set(null, oldEntityStatsModule);
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;

        private TestEntityStore(World world) {
            super(world);
            ((TestWorld) world).entityStore = this;
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }

    private static final class TestWorld extends World {
        private Map<UUID, Ref<EntityStore>> references;
        private EntityStore entityStore;

        private TestWorld() throws IOException {
            super("fresh-tame-test", Path.of("."), new WorldConfig());
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID id) {
            return references.get(id);
        }

        @Override
        public EntityStore getEntityStore() {
            return entityStore;
        }

        @Override
        public String getName() {
            return "fresh-tame-test";
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
