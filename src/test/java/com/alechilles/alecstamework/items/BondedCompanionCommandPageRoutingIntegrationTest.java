package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.hypixel.hytale.component.ComponentType;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.CommandSelectionEventData;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Event-to-router coverage for bonded cards backed by a live Hytale store. */
class BondedCompanionCommandPageRoutingIntegrationTest {
    private static final UUID OWNER = UUID.fromString(
            "74000000-0000-0000-0000-000000000001");
    private static final UUID CARD = UUID.fromString(
            "74000000-0000-0000-0000-000000000002");

    /** Protects bonded cards from falling through to generic not-linked handling. */
    @Test
    void commandPageDismissEventRoutesWithLivePlayerStoreContext() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            player.loadIntoWorld(world);
            player.setReference(actor);

            PlayerRef uiPlayer = playerRef();
            TwCommandItemConfig config = bondedConfig();
            CommandPanelFeaturePresentation feature = bondedDismissFeature();
            AtomicReference<BondedCompanionActionRequest> captured =
                    new AtomicReference<>();
            BondedCompanionApi api = recordingApi(captured);
            BondedCompanionPanelActionRouter bonded =
                    new BondedCompanionPanelActionRouter(
                            new BondedCompanionPanelActionService(() -> api),
                            new CommandFeedbackService(null),
                            new HytaleBondedCompanionActionContextFactory(
                                    new ComponentType<EntityStore,
                                            TameworkBondedReviveEscrowComponent>(),
                                    null));
            CommandSelectionPageService service = new CommandSelectionPageService(
                    null, null, null, null, null, null, null, bonded);
            AtomicReference<CommandSelectionPageService.FeatureRoute> route =
                    new AtomicReference<>();
            TameworkCommandSelectionPage page = page(
                    uiPlayer, config, feature, presentationUuid -> route.set(
                            service.routeFeatureAction(
                                    player, store, config, presentationUuid,
                                    feature,
                                    CommandSelectionPageService.FeatureAction.DISMISS)),
                    ignored -> { });
            refresh(page);

            page.handleDataEvent(actor, store, event(
                    "__roster_dismiss__:" + CARD));

            assertEquals(CommandSelectionPageService.FeatureRoute.BONDED,
                    route.get());
            BondedCompanionActionRequest request = captured.get();
            assertNotNull(request);
            assertEquals(OWNER, request.ownerUuid());
            assertEquals("profile-7", request.profileId());
            assertNotNull(request.actionContext());
            assertNotNull(request.actionContext().inventory());
            assertSame(store, player.getReference().getStore());
        }
    }

    /** Regression: a forged legacy recall event cannot escape a bonded roster page. */
    @Test
    void bondedPageRejectsForgedGenericRecallWhileGenericPageStillDispatches()
            throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            AtomicInteger recalls = new AtomicInteger();
            java.util.function.Consumer<UUID> recall = ignored ->
                    recalls.incrementAndGet();

            TameworkCommandSelectionPage bonded = page(
                    playerRef(), bondedConfig(), bondedDismissFeature(),
                    ignored -> { }, recall);
            bonded.handleDataEvent(actor, store, event("__recall__:" + CARD));
            assertEquals(0, recalls.get());

            TameworkCommandSelectionPage generic = page(
                    playerRef(), genericConfig(), bondedDismissFeature(),
                    ignored -> { }, recall);
            generic.handleDataEvent(actor, store, event("__recall__:" + CARD));
            assertEquals(1, recalls.get());
        }
    }

    @Test
    void currentPhysicalAuthorityRoutesDismissToApi() throws Exception {
        assertNotNull(dispatchPageDismiss(() -> true, () -> true));
    }

    @Test
    void pageCreatedBeforePhysicalAuthorityChangeRejectsDismissBeforeApiMutation()
            throws Exception {
        assertNull(dispatchPageDismiss(() -> true, () -> false));
    }

    private BondedCompanionActionRequest dispatchPageDismiss(
            BooleanSupplier genericAuthority,
            BooleanSupplier bondedAuthority) throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            putObject(player, Player.class, "playerRef", playerRef());
            player.loadIntoWorld(world);
            player.setReference(actor);
            AtomicReference<BondedCompanionActionRequest> captured = new AtomicReference<>();
            BondedCompanionApi api = recordingApi(captured);
            BondedCompanionPanelActionRouter router = new BondedCompanionPanelActionRouter(
                    new BondedCompanionPanelActionService(() -> api),
                    new CommandFeedbackService(null));
            ItemStack horn = commandStack("test:horn", "horn-tool");
            installInventory(player, horn);
            BondedCompanionPanelEntrySourceService bondedSource =
                    new BondedCompanionPanelEntrySourceService(
                            new BondedCompanionPanelRecordSource(() -> api),
                            new BondedCompanionPanelFeaturePresentationSource(
                                    () -> api, () -> 10L));
            CommandPanelEntrySourceService panelSource =
                    new CommandPanelEntrySourceService(
                            null, new CommandPanelPreferenceService(), null,
                            null, null, null, bondedSource);
            CommandToolInventoryService tools = new CommandToolInventoryService(
                    null, panelSource, new CommandPanelPreferenceService(), null);
            CommandSelectionPageService service = new CommandSelectionPageService(
                    tools, null, null, null, null, null, null, router);
            TameworkCommandSelectionPage page = createBoundPage(
                    service, player, store, bondedConfig(), horn,
                    genericAuthority, bondedAuthority);
            refresh(page);

            page.handleDataEvent(actor, store, event(
                    "__roster_dismiss__:" +
                            BondedCompanionPanelRecordSource.presentationUuid(
                                    "profile-7")));

            return captured.get();
        }
    }

    private TameworkCommandSelectionPage createBoundPage(
            CommandSelectionPageService service,
            Player player,
            TestEntityComponentStore store,
            TwCommandItemConfig config,
            ItemStack working,
            BooleanSupplier genericAuthority,
            BooleanSupplier bondedAuthority) throws Exception {
        var createPage = java.util.Arrays.stream(
                        CommandSelectionPageService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("createPage"))
                .max(java.util.Comparator.comparingInt(
                        java.lang.reflect.Method::getParameterCount))
                .orElseThrow();
        createPage.setAccessible(true);
        CommandSelectionPageService.Actions actions = new CommandSelectionPageService.Actions(
                ignored -> { }, ignored -> { }, ignored -> { }, ignored -> { },
                ignored -> { }, ignored -> { }, ignored -> { }, ignored -> { },
                () -> { }, () -> { }, ignored -> { });
        Object[] fixed = {
                player, store, player.getPlayerRef(), config, working,
                "horn-tool", actions, genericAuthority
        };
        Object[] arguments = createPage.getParameterCount() == fixed.length
                ? fixed
                : java.util.Arrays.copyOf(fixed, fixed.length + 1);
        if (arguments.length > fixed.length) {
            arguments[arguments.length - 1] = bondedAuthority;
        }
        return (TameworkCommandSelectionPage) createPage.invoke(
                service, arguments);
    }

    private void installInventory(Player player, ItemStack horn)
            throws Exception {
        SimpleItemContainer hotbar = new SimpleItemContainer((short) 1);
        hotbar.setItemStackForSlot((short) 0, horn);
        Inventory inventory = new Inventory();
        putObject(inventory, Inventory.class, "hotbar",
                new InventoryComponent.Hotbar(hotbar, (byte) 0));
        putObject(player, LivingEntity.class, "inventory", inventory);
    }

    private ItemStack commandStack(String itemId, String toolId)
            throws Exception {
        ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        putObject(stack, ItemStack.class, "itemId", itemId);
        unsafe().putInt(stack, unsafe().objectFieldOffset(
                ItemStack.class.getDeclaredField("quantity")), 1);
        putObject(stack, ItemStack.class, "metadata", new BsonDocument(
                com.alechilles.alecstamework.config.TameworkMetadataKeys
                        .COMMAND_TOOL_ID, new BsonString(toolId)));
        return stack;
    }

    private TameworkCommandSelectionPage page(
            PlayerRef playerRef, TwCommandItemConfig config,
            CommandPanelFeaturePresentation feature,
            java.util.function.Consumer<UUID> dismiss,
            java.util.function.Consumer<UUID> recall) {
        java.util.function.Consumer<UUID> noUuid = ignored -> { };
        java.util.function.Consumer<String> noString = ignored -> { };
        Runnable noRun = () -> { };
        return new TameworkCommandSelectionPage(
                playerRef, config, null, true,
                List::of, List::of, () -> Map.of(CARD, feature),
                () -> "LinkedMode", () -> false, () -> "16",
                () -> "Default", () -> "None", () -> "",
                List::of, () -> "", List::of, ignored -> true, true,
                noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, noUuid,
                noUuid, dismiss, noUuid, noUuid, recall, noUuid, noUuid,
                noUuid, noString, ignored -> { }, noRun, noRun, noRun,
                noString, noString, noString, noRun, noString,
                (uuid, group) -> { }, noString);
    }

    private CommandPanelFeaturePresentation bondedDismissFeature() {
        return CommandPanelFeaturePresentation.bonded(
                new BondedCompanionPanelPresentation(
                        "profile-7", "hydragon:dragons",
                        "Bonded_Miniwyvern_Storm", 7L, "Nimbus",
                        "Miniwyvern", "Female", "Miniwyvern Storm",
                        Map.of(), Map.of(), new BondedCompanionStatusPresentation(
                                BondedCompanionStateView.ACTIVE,
                                BondedCompanionStatusPresentation.Action.DISMISS,
                                true, null, 0L), null));
    }

    private TwCommandItemConfig bondedConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:dragons",
                  "CommandList": [
                    {"Id":"Follow", "DisplayName":"Follow", "Steps":[]}
                  ]
                }
                """), new ExtraInfo());
    }

    private TwCommandItemConfig genericConfig() {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("{}"), new ExtraInfo());
    }

    private BondedCompanionApi recordingApi(
            AtomicReference<BondedCompanionActionRequest> captured) {
        return (BondedCompanionApi) Proxy.newProxyInstance(
                BondedCompanionApi.class.getClassLoader(),
                new Class<?>[]{BondedCompanionApi.class},
                (proxy, method, arguments) -> {
                    if ("availability".equals(method.getName())) {
                        return BondedCompanionAvailability.availableNow();
                    }
                    if ("list".equals(method.getName())) {
                        return CompletableFuture.completedFuture(
                                new BondedCompanionResult<>(
                                        BondedCompanionResultCode.SUCCESS,
                                        List.of(bondedProfile()), null));
                    }
                    if ("store".equals(method.getName())) {
                        captured.set((BondedCompanionActionRequest) arguments[0]);
                        return CompletableFuture.completedFuture(
                                new BondedCompanionResult<>(
                                        BondedCompanionResultCode.SUCCESS,
                                        null, null));
                    }
                    if ("subscribe".equals(method.getName())) return (AutoCloseable) () -> { };
                    throw new AssertionError("Unexpected bonded API call: "
                            + method.getName());
                });
    }

    private BondedCompanionProfileView bondedProfile() {
        return new BondedCompanionProfileView(
                "profile-7", OWNER, "hydragon:dragons",
                "hydragon:dragon", "Bonded_Miniwyvern_Storm", "Nimbus",
                "Miniwyvern", "Female", 7L,
                BondedCompanionStateView.ACTIVE, false, true, false,
                Map.of(), new BondedCompanionLeaseView(
                        "lease-7", CARD, "world-a", 1L, 0L),
                0L, null);
    }

    private PlayerRef playerRef() throws Exception {
        PlayerRef ref = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
        putObject(ref, PlayerRef.class, "uuid", OWNER);
        putObject(ref, PlayerRef.class, "username", "BondedTester");
        putObject(ref, PlayerRef.class, "language", "en-US");
        putObject(ref, PlayerRef.class, "packetHandler",
                unsafe().allocateInstance(TestPacketHandler.class));
        return ref;
    }

    private void refresh(TameworkCommandSelectionPage page) throws Exception {
        var method = TameworkCommandSelectionPage.class.getDeclaredMethod(
                "refreshLinkedNpcEntries");
        method.setAccessible(true);
        method.invoke(page);
    }

    private CommandSelectionEventData event(
            String commandId) throws Exception {
        var event = new CommandSelectionEventData();
        putObject(event,
                CommandSelectionEventData.class,
                "commandId", commandId);
        return event;
    }

    private static void putObject(
            Object target, Class<?> owner, String name, Object value)
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

    private static final class TestPacketHandler extends PacketHandler {
        private TestPacketHandler() {
            super((ChannelConnection) null, new ProtocolVersion(0));
        }

        @Override public String getIdentifier() { return "bonded-test"; }
        @Override public void accept(ToServerPacket packet) { }
        @Override public void write(ToClientPacket packet) { }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;

        private TestEntityStore(World world) {
            super(world);
            if (world instanceof TestWorld testWorld) {
                testWorld.entityStore = this;
            }
        }
        @Override public TestEntityComponentStore getStore() { return store; }
    }

    private static final class TestWorld extends World {
        private EntityStore entityStore;

        private TestWorld() throws java.io.IOException {
            super("unused", Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world
                            .WorldConfig());
        }

        @Override public String getName() { return "world-a"; }
        @Override public EntityStore getEntityStore() { return entityStore; }
    }
}
