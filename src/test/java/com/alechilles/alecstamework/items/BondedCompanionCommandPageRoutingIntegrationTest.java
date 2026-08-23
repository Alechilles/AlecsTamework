package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.hypixel.hytale.component.ComponentType;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.CommandReviveCostPresentation;
import com.alechilles.alecstamework.ui.CommandRosterStatusPresentation;
import com.alechilles.alecstamework.ui.CommandSelectionEventData;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcPanelFeatureAction;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
                                    null), (owner, roster) -> { },
                            (owner, currentRef, currentStore) -> player);
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

    /** Regression: an opaque bonded handle must use the durable bonded route. */
    @Test
    void commandUiBondedBinderUsesBondedRouterOutcomeAndRouteMode() throws Exception {
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

            AtomicReference<BondedCompanionActionRequest> captured =
                    new AtomicReference<>();
            BondedCompanionPanelActionRouter router =
                    new BondedCompanionPanelActionRouter(
                            new BondedCompanionPanelActionService(
                                    () -> recordingApi(captured)),
                            new CommandFeedbackService(null),
                            new HytaleBondedCompanionActionContextFactory(null, null),
                            (owner, roster) -> { },
                            (owner, currentRef, currentStore) -> player);
            CommandSelectionPageService service = new CommandSelectionPageService(
                    null, null, null, null, null, null, null, router);
            UUID sessionId = UUID.randomUUID();
            CommandUiActionGateway gateway = new CommandUiActionGateway();
            CommandUiSessionImpl bonded = new CommandUiSessionImpl(
                    sessionId,
                    new CommandUiSnapshot(sessionId, 0L, 0L, null, List.of(),
                            List.of(), new CommandUiPanelState("bonded")),
                    gateway, CommandUiWorldDispatcher.direct(),
                    CommandUiSessionImpl.Mode.BONDED);
            CommandUiSessionImpl generic = new CommandUiSessionImpl(
                    sessionId,
                    new CommandUiSnapshot(sessionId, 0L, 0L, null, List.of(),
                            List.of(), new CommandUiPanelState("generic")),
                    gateway, CommandUiWorldDispatcher.direct(),
                    CommandUiSessionImpl.Mode.GENERIC);
            AtomicInteger resolverCalls = new AtomicInteger();
            AtomicReference<BondedCompanionPanelActionRouter.CurrentUiContext>
                    currentContext = new AtomicReference<>();
            var contextResolver =
                    (BondedCompanionPanelActionRouter.CurrentUiContextResolver)
                    (owner, roster, profile) -> {
                        resolverCalls.incrementAndGet();
                        return currentContext.get();
                    };
            var routeProbe = service.bindBondedUiAction(bonded,
                    new CommandSelectionPageService.BondedUiActionBinding(
                            new CommandUiAction("DISMISS", CARD), OWNER,
                            "hydragon:dragons", "profile-7", contextResolver, false));
            assertEquals(CommandUiActionStatus.DENIED,
                    generic.invoke(routeProbe).toCompletableFuture().join().status());

            var handle = service.bindBondedUiAction(bonded,
                    new CommandSelectionPageService.BondedUiActionBinding(
                            new CommandUiAction("DISMISS", CARD), OWNER,
                            "hydragon:dragons", "profile-7", contextResolver, false));
            currentContext.set(new BondedCompanionPanelActionRouter.CurrentUiContext(
                    actor, store, bondedConfig(), bondedDismissFeature(),
                    ignored -> true));
            CommandUiActionResult applied = bonded.invoke(handle)
                    .toCompletableFuture().join();
            assertEquals(CommandUiActionStatus.APPLIED, applied.status());
            assertNotNull(captured.get());
            assertEquals(OWNER, captured.get().ownerUuid());
            assertEquals("profile-7", captured.get().profileId());
            assertEquals(1, resolverCalls.get());

            bonded.close();
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
    void bondedFlightToggleRoutesOnlyAvailableBondedCardWithEventContext()
            throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            AtomicReference<UUID> card = new AtomicReference<>();
            AtomicReference<Ref<EntityStore>> eventRef = new AtomicReference<>();
            AtomicReference<Store<EntityStore>> eventStore = new AtomicReference<>();
            LinkedNpcPanelFeatureAction toggle = (uuid, ref, currentStore) -> {
                card.set(uuid);
                eventRef.set(ref);
                eventStore.set(currentStore);
            };
            TameworkCommandSelectionPage page = page(playerRef(), bondedConfig(),
                    bondedFlightFeature(), ignored -> { }, ignored -> { }, toggle);
            refresh(page);

            page.handleDataEvent(actor, store, event(
                    "__bonded_flight_toggle__:" + CARD));

            assertEquals(CARD, card.get());
            assertSame(actor, eventRef.get());
            assertSame(store, eventStore.get());
        }
    }

    @Test
    void staleOrNonBondedFlightToggleCommandDoesNotInvokeCallback() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            AtomicInteger callbacks = new AtomicInteger();
            LinkedNpcPanelFeatureAction toggle = (uuid, ref, currentStore) ->
                    callbacks.incrementAndGet();
            TameworkCommandSelectionPage unavailable = page(playerRef(), bondedConfig(),
                    bondedDismissFeature(), ignored -> { }, ignored -> { }, toggle);
            unavailable.handleDataEvent(actor, store, event(
                    "__bonded_flight_toggle__:" + CARD));
            TameworkCommandSelectionPage generic = page(playerRef(), genericConfig(),
                    bondedFlightFeature(), ignored -> { }, ignored -> { }, toggle);
            generic.handleDataEvent(actor, store, event(
                    "__bonded_flight_toggle__:" + CARD));
            assertEquals(0, callbacks.get());
        }
    }

    @Test
    void serviceFlightToggleRouteUsesCurrentEventAuthorityBeforeActionBoundary()
            throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER); player.loadIntoWorld(world); player.setReference(actor);
            AtomicInteger actions = new AtomicInteger();
            CommandSelectionPageService service = new CommandSelectionPageService(
                    null, null, null, null, null, null, null, null, null,
                    (owner, ref, currentStore, item, row) -> {
                        actions.incrementAndGet();
                        assertSame(actor, ref); assertSame(store, currentStore);
                        return true;
                    }, (owner, ref, currentStore) -> ref == actor
                            && currentStore == store ? player : null);
            var row = bondedFlightFeature().bonded();
            assertTrue(service.routeFlightToggle(OWNER, actor, store, bondedConfig(),
                    "horn", row, ignored -> true));
            assertEquals(1, actions.get());
            assertFalse(service.routeFlightToggle(OWNER, actor, store, bondedConfig(),
                    "horn", row, ignored -> false));
            assertEquals(1, actions.get());
        }
    }

    /** A bonded Horn command choice is a tool preference, not a generic NPC action. */
    @Test
    void bondedPageCommandChoiceInvokesTheToolSelectionCallback() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            putObject(player, Player.class, "playerRef", playerRef());
            player.loadIntoWorld(world);
            player.setReference(actor);
            AtomicReference<String> selected = new AtomicReference<>();
            CommandSelectionPageService service = new CommandSelectionPageService(
                    null, null, null, null, null, null, null, null);

            TameworkCommandSelectionPage page = createBoundPage(
                    service, player, store, bondedConfig(), commandStack(
                            "test:horn", "horn-tool"), () -> false,
                    ignored -> true, selected::set);

            page.handleDataEvent(actor, store, event("Follow"));

            assertEquals("Follow", selected.get());
        }
    }

    /** A bonded Horn command choice must persist on its physical tool stack. */
    @Test
    void bondedMenuChoicePersistsTheSelectedCommandOnTheHorn() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            putObject(player, Player.class, "playerRef", silentPlayerRef());
            player.loadIntoWorld(world);
            player.setReference(store.createReference());
            ItemStack horn = metadataCommandStack("test:horn", "horn-tool");
            installInventory(player, horn);
            TwCommandItemConfig config = bondedConfigWithHold();
            com.alechilles.alecstamework.config.bonded
                    .BondedCompanionRosterRegistry rosters = bondedRosters();
            com.alechilles.alecstamework.config.CommandItemRegistry registry =
                    new com.alechilles.alecstamework.config.CommandItemRegistry(
                            rosters);
            registry.register("test:horn", config);
            CommandItemFeatureHandler handler = new CommandItemFeatureHandler(
                    registry, null, null);
            var apply = CommandItemFeatureHandler.class.getDeclaredMethod(
                    "applyMenuSelection", Player.class, String.class,
                    TwCommandItemConfig.class, String.class);
            apply.setAccessible(true);

            apply.invoke(handler, player, "horn-tool", config, "Hold");

            ItemStack updated = player.getInventory().getHotbar().getItemStack((short) 0);
            assertEquals("Hold", updated.getFromMetadataOrNull(
                    com.alechilles.alecstamework.config.TameworkMetadataKeys
                            .COMMAND_SELECTED_ID, Codec.STRING));
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

    /** Generic roster buttons must retain their public-page feature dispatch. */
    @Test
    void genericRosterFeatureEventsUseLivePublicPageContext() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            AtomicReference<String> action = new AtomicReference<>();
            AtomicReference<Ref<EntityStore>> eventRef = new AtomicReference<>();
            AtomicReference<Store<EntityStore>> eventStore = new AtomicReference<>();
            for (GenericFeatureEvent expected : GenericFeatureEvent.values()) {
                TameworkCommandSelectionPage page = genericFeaturePage(
                        expected, action, eventRef, eventStore);
                refresh(page);
                page.handleDataEvent(actor, store, event(expected.command()));
                if (expected == GenericFeatureEvent.REVIVE) {
                    page.handleDataEvent(actor, store, event("__revive_confirm__"));
                }
                assertEquals(expected.name(), action.get());
                assertSame(actor, eventRef.get());
                assertSame(store, eventStore.get());
            }
        }
    }

    /**
     * A page callback may survive a transfer, but its mutation must use the
     * event's destination player/store rather than the opening world's pair.
     */
    @Test
    void transferredPageCallbackUsesDestinationEventContextOnly() throws Exception {
        TestWorld sourceWorld = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestWorld destinationWorld = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore sourceEntityStore = new TestEntityStore(sourceWorld);
        TestEntityStore destinationEntityStore = new TestEntityStore(destinationWorld);
        try (TestEntityComponentStore sourceStore = new TestEntityComponentStore(sourceEntityStore);
             TestEntityComponentStore destinationStore = new TestEntityComponentStore(
                     destinationEntityStore)) {
            sourceEntityStore.store = sourceStore;
            destinationEntityStore.store = destinationStore;
            Ref<EntityStore> sourceRef = sourceStore.createReference();
            Ref<EntityStore> destinationRef = destinationStore.createReference();
            Player sourcePlayer = (Player) unsafe().allocateInstance(Player.class);
            sourcePlayer.setLegacyUUID(OWNER);
            putObject(sourcePlayer, Player.class, "playerRef", playerRef());
            sourcePlayer.loadIntoWorld(sourceWorld);
            sourcePlayer.setReference(sourceRef);
            Player destinationPlayer = (Player) unsafe().allocateInstance(Player.class);
            destinationPlayer.setLegacyUUID(OWNER);
            destinationPlayer.loadIntoWorld(destinationWorld);
            destinationPlayer.setReference(destinationRef);

            AtomicReference<BondedCompanionActionRequest> captured = new AtomicReference<>();
            AtomicReference<Store<EntityStore>> resolvedStore = new AtomicReference<>();
            AtomicReference<Player> authorityPlayer = new AtomicReference<>();
            BondedCompanionPanelActionRouter router = new BondedCompanionPanelActionRouter(
                    new BondedCompanionPanelActionService(() -> recordingApi(captured)),
                    new CommandFeedbackService(null),
                    new HytaleBondedCompanionActionContextFactory(null, null),
                    (owner, roster) -> { },
                    (owner, eventRef, eventStore) -> {
                        resolvedStore.set(eventStore);
                        return eventRef == destinationRef ? destinationPlayer : null;
                    });

            ItemStack horn = commandStack("test:horn", "horn-tool");
            installInventory(sourcePlayer, horn);
            BondedCompanionApi api = recordingApi(captured);
            BondedCompanionPanelEntrySourceService bondedSource =
                    new BondedCompanionPanelEntrySourceService(
                            BondedPanelTestFixtures.cache(api),
                            new BondedCompanionPanelRecordSource(),
                            new BondedCompanionPanelFeaturePresentationSource(() -> 10L));
            CommandPanelEntrySourceService panelSource =
                    new CommandPanelEntrySourceService(
                            null, new CommandPanelPreferenceService(), null,
                            null, null, null, bondedSource);
            panelSource.warmBondedRoster(OWNER, "hydragon:dragons");
            CommandSelectionPageService service = new CommandSelectionPageService(
                    new CommandToolInventoryService(null, panelSource,
                            new CommandPanelPreferenceService(), null),
                    null, null, null, null, null, null, router);
            TameworkCommandSelectionPage page = createBoundPage(service,
                    sourcePlayer, sourceStore, bondedConfig(), horn,
                    () -> true, currentPlayer -> {
                        authorityPlayer.set(currentPlayer);
                        return currentPlayer == destinationPlayer;
                    });
            refresh(page);

            page.handleDataEvent(destinationRef, destinationStore, event(
                    "__roster_dismiss__:" +
                            BondedCompanionPanelRecordSource.presentationUuid(
                                    "profile-7")));

            assertSame(destinationStore, resolvedStore.get());
            assertNotSame(sourceStore, resolvedStore.get());
            assertSame(destinationPlayer, authorityPlayer.get());
            assertNotSame(sourcePlayer, authorityPlayer.get());
            assertNotNull(captured.get());
            assertEquals(destinationWorld.getName(), captured.get().worldKey());
            assertNotSame(sourceRef, destinationPlayer.getReference());
        }
    }

    /** A stale rendered revision refreshes exactly its owner/roster generation. */
    @Test
    void rejectedBondedActionRefreshesTheExactRenderedRoster() throws Exception {
        TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
        TestEntityStore entityStore = new TestEntityStore(world);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            entityStore.store = store;
            Ref<EntityStore> actor = store.createReference();
            Player player = (Player) unsafe().allocateInstance(Player.class);
            player.setLegacyUUID(OWNER);
            player.loadIntoWorld(world);
            player.setReference(actor);
            AtomicReference<String> refreshed = new AtomicReference<>();
            BondedCompanionPanelActionRouter router = new BondedCompanionPanelActionRouter(
                    new BondedCompanionPanelActionService(
                            () -> rejectingApi(BondedCompanionResultCode.REVISION_CONFLICT)),
                    new CommandFeedbackService(null),
                    new HytaleBondedCompanionActionContextFactory(null, null),
                    (owner, roster) -> refreshed.set(owner + ":" + roster),
                    (owner, eventRef, eventStore) -> player);

            router.route(OWNER, actor, store, bondedConfig(), bondedDismissFeature(),
                    BondedCompanionPanelActionService.Action.STORE, ignored -> true);

            assertEquals(OWNER + ":hydragon:dragons", refreshed.get());
        }
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
                    new CommandFeedbackService(null),
                    new HytaleBondedCompanionActionContextFactory(
                            new ComponentType<EntityStore,
                                    TameworkBondedReviveEscrowComponent>(),
                            null), (owner, roster) -> { },
                    (owner, currentRef, currentStore) -> player);
            ItemStack horn = commandStack("test:horn", "horn-tool");
            installInventory(player, horn);
            BondedCompanionPanelEntrySourceService bondedSource =
                    new BondedCompanionPanelEntrySourceService(
                            BondedPanelTestFixtures.cache(api),
                            new BondedCompanionPanelRecordSource(),
                            new BondedCompanionPanelFeaturePresentationSource(
                                    () -> 10L));
            CommandPanelEntrySourceService panelSource =
                    new CommandPanelEntrySourceService(
                            null, new CommandPanelPreferenceService(), null,
                            null, null, null, bondedSource);
            panelSource.warmBondedRoster(OWNER, "hydragon:dragons");
            CommandToolInventoryService tools = new CommandToolInventoryService(
                    null, panelSource, new CommandPanelPreferenceService(), null);
            CommandSelectionPageService service = new CommandSelectionPageService(
                    tools, null, null, null, null, null, null, router);
            TameworkCommandSelectionPage page = createBoundPage(
                    service, player, store, bondedConfig(), horn,
                    genericAuthority, ignored -> bondedAuthority.getAsBoolean());
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
            CommandSelectionPageService.BondedLifecycleAuthority bondedAuthority)
            throws Exception {
        return createBoundPage(service, player, store, config, working,
                genericAuthority, bondedAuthority, ignored -> { });
    }

    private TameworkCommandSelectionPage createBoundPage(
            CommandSelectionPageService service,
            Player player,
            TestEntityComponentStore store,
            TwCommandItemConfig config,
            ItemStack working,
            BooleanSupplier genericAuthority,
            CommandSelectionPageService.BondedLifecycleAuthority bondedAuthority,
            java.util.function.Consumer<String> selectCommand)
            throws Exception {
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
                () -> { }, () -> { }, selectCommand);
        Object[] fixed = {
                player, store, player.getPlayerRef(), config, working,
                "horn-tool", actions, genericAuthority
        };
        Object[] arguments = createPage.getParameterCount() == fixed.length
                ? fixed
                : java.util.Arrays.copyOf(fixed, fixed.length + 1);
        if (arguments.length > fixed.length) {
            arguments[arguments.length - 1] =
                    bondedAuthority;
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

    private ItemStack metadataCommandStack(String itemId, String toolId) {
        return new MetadataItemStack(itemId, null).withMetadata(
                com.alechilles.alecstamework.config.TameworkMetadataKeys
                        .COMMAND_TOOL_ID, Codec.STRING, toolId);
    }

    private TameworkCommandSelectionPage page(
            PlayerRef playerRef, TwCommandItemConfig config,
            CommandPanelFeaturePresentation feature,
            java.util.function.Consumer<UUID> dismiss,
            java.util.function.Consumer<UUID> recall) {
        return page(playerRef, config, feature, dismiss, recall,
                (ignored, ignoredRef, ignoredStore) -> { });
    }

    private TameworkCommandSelectionPage page(
            PlayerRef playerRef, TwCommandItemConfig config,
            CommandPanelFeaturePresentation feature,
            java.util.function.Consumer<UUID> dismiss,
            java.util.function.Consumer<UUID> recall,
            LinkedNpcPanelFeatureAction flightToggle) {
        java.util.function.Consumer<UUID> noUuid = ignored -> { };
        java.util.function.Consumer<String> noString = ignored -> { };
        Runnable noRun = () -> { };
        return new TameworkCommandSelectionPage(
                playerRef, config, null, true,
                List::of, List::of, () -> Map.of(CARD, feature),
                () -> null,
                () -> "LinkedMode", () -> false, () -> "16",
                () -> "Default", () -> "None", () -> "",
                List::of, () -> "", List::of, ignored -> true, true,
                noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, noUuid,
                (ignored, ignoredRef, ignoredStore) -> { },
                (uuid, ignoredRef, ignoredStore) -> dismiss.accept(uuid),
                (ignored, ignoredRef, ignoredStore) -> { },
                (ignored, ignoredRef, ignoredStore) -> { }, flightToggle, noUuid,
                recall, noUuid, noUuid,
                noUuid, noString, ignored -> { }, noRun, noRun, noRun,
                noString, noString, noString, noRun, noString,
                (uuid, group) -> { }, noString);
    }

    private TameworkCommandSelectionPage genericFeaturePage(
            GenericFeatureEvent expected,
            AtomicReference<String> action,
            AtomicReference<Ref<EntityStore>> eventRef,
            AtomicReference<Store<EntityStore>> eventStore) throws Exception {
        CommandPanelFeaturePresentation feature = genericFeature(expected);
        LinkedNpcPanelFeatureAction capture = (uuid, ref, store) -> {
            action.set(expected.name());
            eventRef.set(ref);
            eventStore.set(store);
        };
        java.util.function.Consumer<UUID> noUuid = ignored -> { };
        java.util.function.Consumer<String> noString = ignored -> { };
        Runnable noRun = () -> { };
        return new TameworkCommandSelectionPage(
                playerRef(), genericRosterConfig(), null, true,
                () -> List.of(genericEntry()), () -> List.of(genericEntry()),
                () -> Map.of(CARD, feature), () -> null,
                () -> "LinkedMode", () -> false, () -> "16",
                () -> "Default", () -> "None", () -> "",
                List::of, () -> "", List::of, ignored -> true, true,
                noUuid, noUuid, noUuid, noUuid, noUuid, noUuid, noUuid,
                capture, capture, capture, capture,
                (ignored, ignoredRef, ignoredStore) -> { },
                noUuid, noUuid, noUuid, noUuid,
                noUuid, noString, ignored -> { }, noRun, noRun, noRun,
                noString, noString, noString, noRun, noString,
                (uuid, group) -> { }, noString);
    }

    private CommandPanelFeaturePresentation genericFeature(
            GenericFeatureEvent event) {
        CommandTimedSummoningState state = event == GenericFeatureEvent.SUMMON
                ? CommandTimedSummoningState.ROSTER_STORED
                : event == GenericFeatureEvent.DISMISS
                        ? CommandTimedSummoningState.ACTIVE
                        : CommandTimedSummoningState.DEAD_REVIVABLE;
        CommandRosterStatusPresentation roster = new CommandRosterStatusPresentation(
                "profile-7", "test:family", state, 1L, null, 0L,
                false, 0L, 0, 0, null, null);
        CommandReviveCostPresentation revival = new CommandReviveCostPresentation(
                PaidCommandRevivalQuote.Status.READY, 0L, List.of(), "1", null, null);
        return new CommandPanelFeaturePresentation(roster, revival);
    }

    private LinkedNpcEntry genericEntry() {
        return new LinkedNpcEntry(CARD, "Nimbus", 1, 1, 1, 1, "", 1,
                1, 1, 1, true, false, true, false, false, false, 0L,
                new LinkedNpcTraitIndicator[0]);
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

    private CommandPanelFeaturePresentation bondedFlightFeature() {
        return CommandPanelFeaturePresentation.bonded(
                new BondedCompanionPanelPresentation(
                        "profile-7", "hydragon:dragons",
                        "Bonded_Miniwyvern_Storm", 7L, "Nimbus",
                        "Miniwyvern", "Female", "Miniwyvern Storm",
                        Map.of("bonded.flightToggle.available", "true"), Map.of(),
                        new BondedCompanionStatusPresentation(
                                BondedCompanionStateView.ACTIVE,
                                BondedCompanionStatusPresentation.Action.DISMISS,
                                true, null, 0L), null));
    }

    private TwCommandItemConfig bondedConfigWithHold() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:dragons",
                  "CommandList": [
                    {"Id":"Follow", "DisplayName":"Follow", "Steps":[]},
                    {"Id":"Hold", "DisplayName":"Hold", "Steps":[]}
                  ]
                }
                """), new ExtraInfo());
    }

    private com.alechilles.alecstamework.config.bonded
            .BondedCompanionRosterRegistry bondedRosters() throws Exception {
        var roster = com.alechilles.alecstamework.config.assets
                .TwBondedCompanionRosterConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterId": "hydragon:dragons",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire"]
                }
                """), new ExtraInfo());
        putObject(roster, com.alechilles.alecstamework.config.assets
                .TwBondedCompanionRosterConfig.class, "id", "TestRoster");
        var rosters = new com.alechilles.alecstamework.config.bonded
                .BondedCompanionRosterRegistry();
        rosters.replace(List.of(roster), 1L);
        return rosters;
    }

    private TwCommandItemConfig genericConfig() {
        return TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse("{}"), new ExtraInfo());
    }

    private TwCommandItemConfig genericRosterConfig() {
        return TwCommandItemConfig.CODEC.decode(BsonDocument.parse("""
                {
                  "RosterStorage": "OwnerCommandFamily",
                  "CommandFamilyId": "test:family",
                  "CommandList": [{"Id":"Follow", "DisplayName":"Follow", "Steps":[]}]
                }
                """), new ExtraInfo());
    }

    private enum GenericFeatureEvent {
        SUMMON("__roster_summon__:" + CARD),
        DISMISS("__roster_dismiss__:" + CARD),
        REVIVE("__respawn__:" + CARD);

        private final String command;

        GenericFeatureEvent(String command) {
            this.command = command;
        }

        private String command() {
            return command;
        }
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

    private BondedCompanionApi rejectingApi(BondedCompanionResultCode code) {
        return (BondedCompanionApi) Proxy.newProxyInstance(
                BondedCompanionApi.class.getClassLoader(),
                new Class<?>[]{BondedCompanionApi.class},
                (proxy, method, arguments) -> {
                    if ("store".equals(method.getName())) {
                        return CompletableFuture.completedFuture(
                                new BondedCompanionResult<>(code, null,
                                        "stale rendered revision"));
                    }
                    if ("availability".equals(method.getName())) {
                        return BondedCompanionAvailability.availableNow();
                    }
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

    private PlayerRef silentPlayerRef() throws Exception {
        PlayerRef ref = (PlayerRef) unsafe().allocateInstance(PlayerRef.class);
        putObject(ref, PlayerRef.class, "uuid", OWNER);
        putObject(ref, PlayerRef.class, "username", "BondedTester");
        putObject(ref, PlayerRef.class, "language", "en-US");
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

    /** Asset-store-free stack that preserves the metadata write under test. */
    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            BsonDocument next = metadata == null
                    ? new BsonDocument() : metadata.clone();
            if (value == null) next.remove(key);
            else next.put(key, codec.encode(value));
            return new MetadataItemStack(itemId,
                    next.isEmpty() ? null : next);
        }
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
        @Override public Ref<EntityStore> getEntityRef(UUID uuid) {
            return entityStore == null ? null : entityStore.getRefFromUUID(uuid);
        }
    }
}
