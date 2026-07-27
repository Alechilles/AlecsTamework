package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Behavioral contract for rejecting bonded projections at generic boundaries. */
class CommandGenericTargetAuthorityTest {
    @Test
    void identifiesOnlyLiveBondedProjectionMarkers() throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> bonded = scope.store.createReference();
            Ref<EntityStore> ordinary = scope.store.createReference();
            scope.store.put(bonded, scope.markerType,
                    TameworkProjectionIdentityComponent.bondedCompanion(
                            "profile", "lease"));
            scope.store.put(ordinary, scope.markerType,
                    new TameworkProjectionIdentityComponent(
                            "profile", "operation",
                            TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                            null, null, 0L));

            assertFalse(allowsGenericTargetMutation(bonded, scope.store));
            assertTrue(allowsGenericTargetMutation(ordinary, scope.store));
        }
    }

    @Test
    void forgedGenericReleaseLeavesBondedTargetOwnershipUntouched()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveBondedTarget();
            CommandOwnerReleaseService release = new CommandOwnerReleaseService(
                    new CommandLinkPolicyService(),
                    new CommandStepExecutionService(null, null, null),
                    new CommandFeedbackService(null),
                    new CommandNpcNameResolver());

            release.release(target.player, "generic-tool", genericConfig(),
                    target.uuid);

            assertEquals(target.owner, scope.store.getComponent(
                    target.reference, scope.ownerType).getOwnerId());
        }
    }

    @Test
    void forgedGenericCullLeavesBondedTargetLinksUntouched()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            LiveTarget target = scope.liveBondedTarget();
            DamageCause previousCommandCause = DamageCause.COMMAND;
            DamageCause.COMMAND = new DamageCause("test-command");
            CommandOwnerCullService cull = new CommandOwnerCullService(
                    new CommandLinkPolicyService(),
                    new CommandItemRegistry(),
                    new CommandLinkMutationService(null,
                            new CommandLinkPolicyService(), null, null),
                    new CommandFeedbackService(null),
                    new CommandNpcNameResolver());

            try {
                try {
                    cull.cull(target.player, "generic-tool", genericConfig(),
                            target.uuid);
                } catch (NullPointerException fixtureOnlyDamageCauseFailure) {
                    // The bare ECS fixture has no DamageCause asset store. Cull
                    // has already cleared links before this base-game-only path.
                    assertTrue(fixtureOnlyDamageCauseFailure.getStackTrace()[0]
                            .getClassName().equals(DamageCause.class.getName()));
                }

                assertTrue(scope.store.getComponent(target.reference,
                        scope.linksType).containsToolId("generic-tool"));
            } finally {
                DamageCause.COMMAND = previousCommandCause;
            }
        }
    }

    @Test
    void genericNearbyPresentationRejectsBondedLiveMarker()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> bonded = scope.store.createReference();
            scope.store.put(bonded, scope.markerType,
                    TameworkProjectionIdentityComponent.bondedCompanion(
                            "nearby-profile", "nearby-lease"));

            assertFalse(isAllowedInGenericNearbyMode(bonded, scope.store));
        }
    }

    @Test
    void genericTargetMutationFailsClosedWhenMarkerTypeIsUnavailable()
            throws Exception {
        try (ProjectionScope scope = ProjectionScope.install()) {
            Ref<EntityStore> ordinary = scope.store.createReference();
            Field instance = staticField(Tamework.class, "instance");
            Object configuredTamework = instance.get(null);
            try {
                instance.set(null, null);
                assertFalse(allowsGenericTargetMutation(ordinary, scope.store));
            } finally {
                instance.set(null, configuredTamework);
            }
        }
    }

    @Test
    void staleGenericCallbackRejectsCurrentBondedToolConfiguration()
            throws Exception {
        ItemStack physicalBondedHorn = testStack("test:bonded-horn");
        TwCommandItemConfig pageConfig = genericConfig();
        TwCommandItemConfig currentConfig = bondedConfig();

        assertFalse(allowsGenericCallback(
                physicalBondedHorn, pageConfig, currentConfig
        ));
    }

    @Test
    void duplicateToolIdsFailClosedRegardlessOfPhysicalStackOrder()
            throws Exception {
        ItemStack genericWhistle = testStack("test:generic-whistle");
        ItemStack bondedHorn = testStack("test:bonded-horn");

        assertEquals(genericWhistle, CommandToolInventoryService
                .selectUniqueToolStack("unique", java.util.List.of(
                        new CommandToolInventoryService.ToolCandidate(
                                genericWhistle, "unique"))));
        assertEquals(null, CommandToolInventoryService.selectUniqueToolStack(
                "unique", java.util.List.of()));
        assertEquals(null, CommandToolInventoryService.selectUniqueToolStack(
                "shared", java.util.List.of(
                        new CommandToolInventoryService.ToolCandidate(
                                genericWhistle, "shared"),
                        new CommandToolInventoryService.ToolCandidate(
                                bondedHorn, "shared"))));
        assertEquals(null, CommandToolInventoryService.selectUniqueToolStack(
                "shared", java.util.List.of(
                        new CommandToolInventoryService.ToolCandidate(
                                bondedHorn, "shared"),
                        new CommandToolInventoryService.ToolCandidate(
                                genericWhistle, "shared"))));
    }

    @Test
    void cullRepairSkipsBondedHornButRetainsGenericToolEligibility()
            throws Exception {
        ItemStack bondedHorn = testStack("test:bonded-horn");
        ItemStack genericWhistle = testStack("test:generic-whistle");

        assertFalse(allowsGenericCullRepair(bondedHorn, bondedConfig()));
        assertFalse(CommandGenericTargetAuthority.allowsGenericCullRepair(
                bondedHorn, null));
        assertFalse(CommandGenericTargetAuthority.allowsGenericCullRepair(
                bondedHorn, disabledGenericConfig()));
        assertTrue(allowsGenericCullRepair(genericWhistle, genericConfig()));
    }

    private static TwCommandItemConfig genericConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse(
                "{\"RequireTamed\":false}"),
                new ExtraInfo());
    }

    private static TwCommandItemConfig bondedConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterStorage":"BondedCompanions",
                  "BondedRosterId":"test:bonded"
                }
                """), new ExtraInfo());
    }

    private static TwCommandItemConfig disabledGenericConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse(
                "{\"Enabled\":false}"), new ExtraInfo());
    }

    private static boolean isAllowedInGenericNearbyMode(
            Ref<EntityStore> reference, TestEntityComponentStore store
    ) throws Exception {
        return invokeBoolean("allowsNearbyPresentation", reference, store);
    }

    private static boolean allowsGenericCallback(
            ItemStack stack, TwCommandItemConfig openTimeConfig,
            TwCommandItemConfig currentPhysicalConfig
    ) throws Exception {
        return invokeBoolean("allowsCurrentGenericCallback", stack,
                openTimeConfig, currentPhysicalConfig);
    }

    private static boolean allowsGenericCullRepair(
            ItemStack stack, TwCommandItemConfig currentPhysicalConfig
    ) throws Exception {
        return invokeBoolean("allowsGenericCullRepair", stack,
                currentPhysicalConfig);
    }

    private static boolean allowsGenericTargetMutation(
            Ref<EntityStore> reference, TestEntityComponentStore store
    ) throws Exception {
        return invokeBoolean("allowsGenericTargetMutation", reference, store);
    }

    private static boolean invokeBoolean(String methodName, Object... arguments)
            throws Exception {
        Class<?> authority;
        try {
            authority = Class.forName(
                    "com.alechilles.alecstamework.items.CommandGenericTargetAuthority"
            );
        } catch (ClassNotFoundException missing) {
            fail("Generic command boundaries need a bonded marker authority",
                    missing);
            return false;
        }
        Class<?>[] types = java.util.Arrays.stream(arguments)
                .map(argument -> argument instanceof TestEntityComponentStore
                        ? com.hypixel.hytale.component.Store.class
                        : argument instanceof Ref<?>
                        ? Ref.class
                        : argument.getClass())
                .toArray(Class<?>[]::new);
        Method method = authority.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return (boolean) method.invoke(null, arguments);
    }

    private static ItemStack testStack(String itemId) throws Exception {
        ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        setField(stack, ItemStack.class, "itemId", itemId);
        unsafe().putInt(stack, unsafe().objectFieldOffset(
                ItemStack.class.getDeclaredField("quantity")), 1);
        return stack;
    }

    private static final class ProjectionScope implements AutoCloseable {
        private final Object oldTamework;
        private final Object oldEntityModule;
        private final ComponentType<EntityStore, NPCEntity> npcType =
                new ComponentType<>();
        private final ComponentType<EntityStore,
                TameworkProjectionIdentityComponent> markerType =
                new ComponentType<>();
        private final ComponentType<EntityStore, TameworkOwnerComponent>
                ownerType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkCommandLinksComponent>
                linksType = new ComponentType<>();
        private final TestWorld world;
        private final TestEntityStore entityStore;
        private final TestEntityComponentStore store;

        private ProjectionScope(Object oldTamework, Object oldEntityModule)
                throws Exception {
            this.oldTamework = oldTamework;
            this.oldEntityModule = oldEntityModule;
            this.world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            this.entityStore = new TestEntityStore(world);
            this.world.bind(entityStore);
            this.store = new TestEntityComponentStore(entityStore);
            entityStore.store = store;
        }

        private static ProjectionScope install() throws Exception {
            Field instance = staticField(Tamework.class, "instance");
            Field entityModule = staticField(EntityModule.class, "instance");
            ProjectionScope scope = new ProjectionScope(instance.get(null),
                    entityModule.get(null));
            EntityModule module = (EntityModule) unsafe().allocateInstance(
                    EntityModule.class);
            Map<Class<?>, ComponentType<EntityStore, ?>> types = new HashMap<>();
            types.put(NPCEntity.class, scope.npcType);
            setField(module, EntityModule.class, "classToComponentType", types);
            entityModule.set(null, module);
            Tamework tamework = (Tamework) unsafe().allocateInstance(
                    Tamework.class);
            setField(tamework, Tamework.class,
                    "projectionIdentityComponentType", scope.markerType);
            setField(tamework, Tamework.class,
                    "ownerComponentType", scope.ownerType);
            setField(tamework, Tamework.class,
                    "commandLinksComponentType", scope.linksType);
            instance.set(null, tamework);
            return scope;
        }

        @Override
        public void close() throws Exception {
            store.close();
            staticField(Tamework.class, "instance").set(null, oldTamework);
            staticField(EntityModule.class, "instance").set(null,
                    oldEntityModule);
        }

        private LiveTarget liveBondedTarget() throws Exception {
            UUID owner = UUID.fromString("73000000-0000-0000-0000-000000000001");
            UUID uuid = UUID.fromString("73000000-0000-0000-0000-000000000002");
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(uuid);
            store.put(reference, npcType, npc);
            store.put(reference, markerType,
                    TameworkProjectionIdentityComponent.bondedCompanion(
                            "profile", "lease"));
            TameworkOwnerComponent ownership = new TameworkOwnerComponent();
            ownership.setOwnerId(owner);
            store.put(reference, ownerType, ownership);
            TameworkCommandLinksComponent links =
                    new TameworkCommandLinksComponent();
            links.setOwnerId(owner);
            links.setToolIds(new String[] {"generic-tool"});
            store.put(reference, linksType, links);
            entityStore.testWorld.initialize(uuid, reference);
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(owner);
            setField(player, Player.class, "playerRef", playerRef(owner));
            player.loadIntoWorld(entityStore.testWorld);
            return new LiveTarget(player, reference, owner, uuid);
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;
        private final TestWorld testWorld;

        private TestEntityStore(TestWorld world) {
            super(world);
            this.testWorld = world;
        }

        @Override
        public TestEntityComponentStore getStore() {
            return store;
        }
    }

    private static final class TestWorld extends
            com.hypixel.hytale.server.core.universe.world.World {
        private UUID uuid;
        private Ref<EntityStore> reference;
        private EntityStore entityStore;

        private TestWorld() throws java.io.IOException {
            super("test", java.nio.file.Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }

        private void initialize(UUID uuid, Ref<EntityStore> reference) {
            this.uuid = uuid;
            this.reference = reference;
        }

        private void bind(EntityStore entityStore) {
            this.entityStore = entityStore;
        }

        @Override
        public EntityStore getEntityStore() {
            return entityStore;
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID requested) {
            return uuid != null && uuid.equals(requested) ? reference : null;
        }
    }

    private record LiveTarget(Player player, Ref<EntityStore> reference,
                              UUID owner, UUID uuid) {
    }

    private static PlayerRef playerRef(UUID owner) throws Exception {
        PlayerRef ref = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
        setField(ref, PlayerRef.class, "uuid", owner);
        setField(ref, PlayerRef.class, "username", "test-owner");
        setField(ref, PlayerRef.class, "language", "en-US");
        return ref;
    }


    private static Field staticField(Class<?> type, String name)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, Class<?> type,
                                 String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
