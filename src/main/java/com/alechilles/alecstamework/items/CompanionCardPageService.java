package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companioncard.CardRenameAvailability;
import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.alechilles.alecstamework.companioncard.CompanionCardInteractions;
import com.alechilles.alecstamework.companioncard.CompanionCardPreferences;
import com.alechilles.alecstamework.companioncard.CompanionCardRequests;
import com.alechilles.alecstamework.companioncard.CompanionCards;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.TameworkCompanionCardPage;
import com.alechilles.alecstamework.ui.TameworkCompanionTalentsPage;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opens and runs the companion card for one loaded companion.
 *
 * <p>Ownership: the interaction runtime opens the card through {@link CompanionCards.Sink}; this service owns the
 * open pages (at most one per viewer), their refresh chains, and the built-in actions. Every action re-resolves the
 * viewer and companion on the world thread and checks ownership again; the card's rendered state is never trusted.
 * Modes and {@code Card} entries go back through {@code TameworkInteract} as {@link CompanionCardRequests}. Built-in
 * actions call the companion window's services through their item-free entry points, and only for companions that
 * accept generic actions (bonded companions keep their own panel).
 *
 * <p>Refresh: while a card is open, a delayed task re-renders it every {@link #REFRESH_MS} on the world thread so
 * health, needs and timers stay current. The chain stops when the card closes, its companion or viewer is gone, or the
 * plugin shuts down. One chain per open card; nothing else is scheduled.
 */
public final class CompanionCardPageService implements CompanionCards.Sink {
    private static final Logger LOGGER = Logger.getLogger(CompanionCardPageService.class.getName());
    /** Passed to services that key hook dispatches by item; the card has no command item. */
    static final String CARD_SOURCE_ID = "tamework:companion_card";
    private static final long REFRESH_MS = 2_000L;

    private final CommandTalentPageService talents;
    private final CommandOwnerReleaseService releases;
    private final CommandOwnerCullService culls;
    private final CommandPanelActionService panelActions;
    private final CommandLinkPolicyService links = new CommandLinkPolicyService();
    private final CommandNpcNameResolver names = new CommandNpcNameResolver();
    private final CommandLoadedNpcStatusSnapshotService snapshots;
    private final CommandLinkedFlightToggleActionService flight = new CommandLinkedFlightToggleActionService();
    private final CommandLinkedShoulderRideActionService shoulder = new CommandLinkedShoulderRideActionService();
    private final Map<UUID, TameworkCompanionCardPage> openPages = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    CompanionCardPageService(CommandTalentPageService talents,
                             CommandOwnerReleaseService releases,
                             CommandOwnerCullService culls,
                             CommandPanelActionService panelActions) {
        this.talents = talents;
        this.releases = releases;
        this.culls = culls;
        this.panelActions = panelActions;
        this.snapshots = new CommandLoadedNpcStatusSnapshotService(names, links,
                new CommandLinkedPanelProgressionPresentationService(),
                new CommandLinkedPanelCooldownSnapshotService());
    }

    /** Stops refresh chains and forgets open cards. Called on plugin shutdown. */
    public void shutdown() {
        stopped = true;
        openPages.clear();
    }

    @Override
    public void open(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef, @Nonnull Ref<EntityStore> npcRef,
                     @Nonnull Store<EntityStore> store, @Nonnull CompanionCardInteractions interactions) {
        if (stopped || player.getPageManager() == null) {
            return;
        }
        PlayerRef uiPlayer = player.getPlayerRef();
        UUID npcUuid = npcUuid(npcRef, store);
        TameworkCompanionCardPage.View view = buildView(player, playerRef, npcRef, store, interactions);
        if (uiPlayer == null || npcUuid == null || view == null) {
            return;
        }
        Session session = new Session(player.getUuid(), npcUuid, playerRef, store);
        TameworkCompanionCardPage page = new TameworkCompanionCardPage(uiPlayer, npcUuid, view, session);
        session.page = page;
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[tw-companion-card] could not open the card for npc=" + npcUuid, ex);
            return;
        }
        openPages.put(player.getUuid(), page);
        scheduleRefresh(session);
    }

    @Override
    public void update(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef, @Nonnull Ref<EntityStore> npcRef,
                       @Nonnull Store<EntityStore> store, @Nonnull CompanionCardInteractions interactions) {
        TameworkCompanionCardPage page = openPages.get(player.getUuid());
        UUID npcUuid = npcUuid(npcRef, store);
        if (page == null || page.isClosed() || npcUuid == null || !npcUuid.equals(page.npcUuid())
                || !showing(player, page)) {
            return;
        }
        TameworkCompanionCardPage.View view = buildView(player, playerRef, npcRef, store, interactions);
        if (view == null) {
            page.closeCard();
            return;
        }
        page.show(view);
    }

    @Nullable
    private TameworkCompanionCardPage.View buildView(Player player, Ref<EntityStore> playerRef,
                                                     Ref<EntityStore> npcRef, Store<EntityStore> store,
                                                     CompanionCardInteractions interactions) {
        NPCEntity npc = npcRef.isValid() ? store.getComponent(npcRef, NPCEntity.getComponentType()) : null;
        if (npc == null) {
            return null;
        }
        LinkedNpcEntry entry = snapshots.buildLoadedEntry(player, npcRef, store,
                CommandLoadedNpcStatusSnapshotService.NpcStatusContext.empty(npc.getUuid()),
                CommandLoadedNpcStatusSnapshotService.SnapshotOptions.linkedPanel());
        if (entry == null) {
            return null;
        }
        boolean owner = owns(player, npcRef, store);
        boolean generic = owner && CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store);
        if (generic) {
            entry = entry.withOwnedActions();
        }
        CardRenameAvailability rename = CardRenameAvailability.OFF;
        NamingFeatureHandler naming = naming();
        if (generic && naming != null) {
            rename = naming.cardRenameAvailability(player, playerRef, npcRef, store,
                    CompanionCardPreferences.renameMode());
        }
        return new TameworkCompanionCardPage.View(entry, owner ? interactions : CompanionCardInteractions.EMPTY,
                owner, generic, ownerName(npcRef, store), rename, CompanionCardPreferences.playerChoiceAllowed(),
                CompanionCardPreferences.effective(playerRef, store) == CompanionCardFBehaviour.CARD);
    }

    /** One open card: its viewer, companion and page. Page callbacks run on the world thread. */
    private final class Session implements TameworkCompanionCardPage.Actions {
        private final UUID playerUuid;
        private final UUID npcUuid;
        private final Ref<EntityStore> playerRef;
        private final Store<EntityStore> store;
        private TameworkCompanionCardPage page;

        private Session(UUID playerUuid, UUID npcUuid, Ref<EntityStore> playerRef, Store<EntityStore> store) {
            this.playerUuid = playerUuid;
            this.npcUuid = npcUuid;
            this.playerRef = playerRef;
            this.store = store;
        }

        @Override
        public void mode(int stepIndex) {
            request(new CompanionCardRequests.SetMode(page.view().interactions().configId(), stepIndex));
        }

        @Override
        public void entry(int entryIndex, @Nonnull CompanionCardInteractions.Kind kind) {
            if (request(new CompanionCardRequests.RunEntry(page.view().interactions().configId(), entryIndex))
                    && kind == CompanionCardInteractions.Kind.MOUNT) {
                // The player is about to ride: get the card out of the way.
                page.closeCard();
            }
        }

        @Override
        public void toggleBreeding() {
            Live live = live(true);
            if (live != null && live.entry() != null && live.entry().breedingAvailable()) {
                panelActions.toggleLoadedNpcBreeding(live.player(), live.npcRef(), store, npcUuid,
                        live.entry().displayName());
                refresh();
            }
        }

        @Override
        public void toggleFlight() {
            if (live(true) != null) {
                flight.toggle(playerUuid, playerRef, store, CARD_SOURCE_ID, npcUuid);
                refresh();
            }
        }

        @Override
        public void toggleShoulder() {
            if (live(true) != null) {
                shoulder.toggle(playerUuid, playerRef, store, CARD_SOURCE_ID, npcUuid);
                refresh();
            }
        }

        @Override
        public void talents() {
            Live live = live(true);
            if (live == null) {
                return;
            }
            talents.openTalentPage(live.player(), () -> {
                Live current = live(true);
                if (current == null) {
                    return null;
                }
                NPCEntity npc = store.getComponent(current.npcRef(), NPCEntity.getComponentType());
                String displayName = npc == null ? null : names.resolveNpcDisplayName(current.npcRef(), store, npc);
                return new CommandTalentPageService.TalentTarget(current.npcRef(), store,
                        displayName == null ? "" : displayName,
                        npc == null ? null : links.resolveRoleId(npc));
            }, () -> reopen(true));
        }

        @Override
        public void rename() {
            Live live = live(true);
            NamingFeatureHandler naming = naming();
            if (live == null || naming == null) {
                return;
            }
            naming.beginCardNaming(live.player(), playerRef, live.npcRef(), store,
                    CompanionCardPreferences.renameMode(), () -> reopen(false));
        }

        @Override
        public void release() {
            Live live = live(true);
            if (live != null) {
                page.closeCard();
                releases.releaseLoaded(live.player(), npcUuid);
            }
        }

        @Override
        public void cull() {
            Live live = live(true);
            if (live != null) {
                page.closeCard();
                culls.cullLoaded(live.player(), npcUuid);
            }
        }

        @Override
        public void toggleOpenOnF() {
            if (!CompanionCardPreferences.playerChoiceAllowed() || !playerRef.isValid()) {
                return;
            }
            boolean card = CompanionCardPreferences.effective(playerRef, store) == CompanionCardFBehaviour.CARD;
            CompanionCardFBehaviour next = card ? CompanionCardFBehaviour.CYCLE : CompanionCardFBehaviour.CARD;
            // Picking the server default clears the override, so a later default change applies again.
            CompanionCardPreferences.setOverride(playerRef, store,
                    next == CompanionCardPreferences.serverDefault() ? null : next);
            refresh();
        }

        @Override
        public void closed() {
            openPages.remove(playerUuid, page);
        }

        /** Queues a request for the role tick and registers the interaction that wakes it. */
        private boolean request(CompanionCardRequests.Request request) {
            Live live = live(false);
            if (live == null) {
                return false;
            }
            NPCEntity npc = store.getComponent(live.npcRef(), NPCEntity.getComponentType());
            StateSupport state = npc == null ? null : NpcSupportAccess.state(npc.getRole(), live.npcRef(), store);
            if (state == null) {
                return false;
            }
            CompanionCardRequests.queue(npcUuid, playerUuid, request);
            state.addInteraction(playerRef);
            return true;
        }

        /** Re-renders the open card from live state, keeping its last interaction snapshot. */
        private void refresh() {
            if (page == null || page.isClosed()) {
                return;
            }
            Player player = player();
            if (player != null && !showing(player, page)) {
                // Another page replaced the card (talents, rename); it no longer receives updates.
                openPages.remove(playerUuid, page);
                return;
            }
            Ref<EntityStore> npcRef = npcRef();
            TameworkCompanionCardPage.View view = player == null || npcRef == null ? null
                    : buildView(player, playerRef, npcRef, store, page.view().interactions());
            if (view == null) {
                page.closeCard();
                return;
            }
            page.show(view);
            if (waitingOnRoleTick(view)) {
                // Cooldowns and mode availability come from the role tick; ask it again until they clear.
                request(new CompanionCardRequests.Refresh());
            }
        }

        private boolean waitingOnRoleTick(TameworkCompanionCardPage.View view) {
            CompanionCardInteractions interactions = view.interactions();
            if (!view.canManage()) {
                return false;
            }
            if (!interactions.modes().isEmpty() && !interactions.modeAllowed()) {
                return true;
            }
            for (CompanionCardInteractions.Action action : interactions.actions()) {
                if (action.status() == CompanionCardInteractions.Status.COOLDOWN) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Opens a fresh card after a page that replaced it closes, if nothing else is open. The talents page's Back
         * leaves that page open, so {@code fromTalents} lets the card replace it.
         */
        private void reopen(boolean fromTalents) {
            Player player = player();
            Ref<EntityStore> npcRef = npcRef();
            Object current = player == null || player.getPageManager() == null
                    ? null : player.getPageManager().getCustomPage();
            if (stopped || player == null || npcRef == null || player.getPageManager() == null
                    || current != null && !(fromTalents && current instanceof TameworkCompanionTalentsPage)) {
                return;
            }
            open(player, playerRef, npcRef, store, page.view().interactions());
            request(new CompanionCardRequests.Refresh());
        }

        /**
         * The current viewer and companion, or {@code null} when either is gone. With {@code generic}, also requires
         * that the viewer owns a companion that accepts generic actions.
         */
        @Nullable
        private Live live(boolean generic) {
            Player player = player();
            Ref<EntityStore> npcRef = npcRef();
            if (player == null || npcRef == null || !owns(player, npcRef, store)
                    || generic && !CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store)) {
                return null;
            }
            return new Live(player, npcRef, page == null ? null : page.view().entry());
        }

        @Nullable
        private Player player() {
            return playerRef.isValid() ? store.getComponent(playerRef, Player.getComponentType()) : null;
        }

        @Nullable
        private Ref<EntityStore> npcRef() {
            World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
            Ref<EntityStore> ref = world == null ? null : world.getEntityRef(npcUuid);
            return ref != null && ref.isValid() && ref.getStore() == store ? ref : null;
        }
    }

    private record Live(Player player, Ref<EntityStore> npcRef, @Nullable LinkedNpcEntry entry) {
    }

    private void scheduleRefresh(Session session) {
        CompletableFuture.delayedExecutor(REFRESH_MS, TimeUnit.MILLISECONDS).execute(() -> {
            World world = session.store.getExternalData() == null ? null : session.store.getExternalData().getWorld();
            if (stopped || world == null || session.page == null || session.page.isClosed()) {
                return;
            }
            try {
                world.execute(() -> {
                    if (stopped || session.page.isClosed() || openPages.get(session.playerUuid) != session.page) {
                        return;
                    }
                    session.refresh();
                    if (!session.page.isClosed() && openPages.get(session.playerUuid) == session.page) {
                        scheduleRefresh(session);
                    }
                });
            } catch (RuntimeException ignored) {
                // The world is shutting down; the card goes with it.
            }
        });
    }

    private static boolean showing(Player player, TameworkCompanionCardPage page) {
        return player.getPageManager() != null && player.getPageManager().getCustomPage() == page;
    }

    private boolean owns(Player player, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        UUID owner = links.resolveOwnerId(npcRef, store);
        return owner != null && owner.equals(player.getUuid());
    }

    @Nullable
    private static String ownerName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = type == null ? null : store.getComponent(npcRef, type);
        String name = owner == null || !owner.hasOwner() ? null : owner.getOwnerName();
        return name == null || name.isBlank() ? null : name;
    }

    @Nullable
    private static UUID npcUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = npcRef.isValid() ? store.getComponent(npcRef, NPCEntity.getComponentType()) : null;
        return npc == null ? null : npc.getUuid();
    }

    @Nullable
    private static NamingFeatureHandler naming() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getNamingFeatureHandler();
    }
}
