package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.companioncard.CardRenameAvailability;
import com.alechilles.alecstamework.companioncard.CompanionCardInteractions;
import com.alechilles.alecstamework.companioncard.CompanionCardPreferences;
import com.alechilles.alecstamework.companioncard.CompanionCardRequests;
import com.alechilles.alecstamework.companioncard.CompanionCards;
import com.alechilles.alecstamework.items.components.TameworkCompanionCardPlacementComponent;
import com.alechilles.alecstamework.items.components.TameworkInspectPreferencesComponent;
import com.alechilles.alecstamework.npc.actions.TameworkTameFoodDisplayResolver;
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
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.List;
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
 * viewer and companion on the world thread and checks the viewer's access again (the owner, or a player the owner
 * shares the companion with at a level that covers the action); the card's rendered state is never trusted.
 * Modes and {@code Card} entries go back through {@code TameworkInteract} as {@link CompanionCardRequests}. Built-in
 * actions call the companion window's services through their item-free entry points, and only for companions that
 * accept generic actions (bonded companions keep their own panel). Homes and groups need no command item: the home
 * lives on the companion and groups are the viewer's own (see {@link CompanionCardHomeActions} and
 * {@link CommandCompanionGroups}).
 *
 * <p>Refresh: while a card is open, a delayed task checks every {@link #WATCH_MS} on the world thread that its
 * companion is still there and alive, closing the card when it is not, and re-renders the card every
 * {@link #REFRESH_MS} so health, needs and timers stay current. The chain stops when the card closes, its companion or
 * viewer is gone, or the plugin shuts down. One chain per open card; nothing else is scheduled.
 */
public final class CompanionCardPageService implements CompanionCards.Sink {
    private static final Logger LOGGER = Logger.getLogger(CompanionCardPageService.class.getName());
    /** Passed to services that key hook dispatches by item; the card has no command item. */
    static final String CARD_SOURCE_ID = "tamework:companion_card";
    private static final long REFRESH_MS = 2_000L;
    /**
     * How often an open panel checks that its companion is still there. One cheap lookup per open panel (an entity
     * ref by uuid and a death check), so a companion that dies or vanishes closes the panel, and hands the camera
     * back, within a quarter second rather than at the next full refresh.
     */
    private static final long WATCH_MS = 250L;
    private static final int WATCHES_PER_REFRESH = (int) (REFRESH_MS / WATCH_MS);

    private final CommandTalentPageService talents;
    private final CommandOwnerReleaseService releases;
    private final CommandOwnerCullService culls;
    private final CommandPanelActionService panelActions;
    private final CompanionCardHomeActions homes;
    @Nullable
    private final CommandOwnedPanelRecordSource ownedRows;
    private final CommandLinkPolicyService links = new CommandLinkPolicyService();
    private final CommandNpcNameResolver names = new CommandNpcNameResolver();
    private final CommandTargetHudFoodResolver foodResolver = new CommandTargetHudFoodResolver();
    private final TameworkTameFoodDisplayResolver foodDisplay = new TameworkTameFoodDisplayResolver();
    private final CommandTargetHudAttachmentResolver attachments = new CommandTargetHudAttachmentResolver();
    private final CommandLoadedNpcStatusSnapshotService snapshots;
    private final CommandLinkedFlightToggleActionService flight = new CommandLinkedFlightToggleActionService();
    private final CommandLinkedShoulderRideActionService shoulder = new CommandLinkedShoulderRideActionService();
    private final Map<UUID, Session> openPages = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    CompanionCardPageService(CommandTalentPageService talents,
                             CommandOwnerReleaseService releases,
                             CommandOwnerCullService culls,
                             CommandPanelActionService panelActions,
                             CompanionCardHomeActions homes,
                             @Nullable CommandOwnedPanelRecordSource ownedRows) {
        this.talents = talents;
        this.releases = releases;
        this.culls = culls;
        this.panelActions = panelActions;
        this.homes = homes;
        this.ownedRows = ownedRows;
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
        if (uiPlayer == null || npcUuid == null) {
            return;
        }
        String groupKey = groupKey(player, npcUuid);
        // Food and appearance rarely change, so they are worked out once per open and carried in the view.
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        String roleId = npc == null ? null : names.resolveNpcRoleId(npc);
        List<CommandTargetHudViewModel.FoodRow> foods = npc == null ? List.of() : foodResolver.resolveFoodEntries(
                player, foodDisplay.resolveFoodDisplayItemIds(roleId, npc.getRole(), npcRef, store, true).entries());
        List<CommandTargetHudViewModel.AttachmentRow> appearance = attachments.resolveRows(roleId,
                CommandTargetHudService.resolveModelAssetId(npcRef, store),
                CommandTargetHudService.resolveAttachmentIds(npcRef, store), TameworkCompanionCardPage.APPEARANCE_SLOTS);
        TameworkCompanionCardPage.View view = buildView(player, playerRef, npcRef, store, interactions, groupKey,
                foods, appearance);
        if (view == null) {
            return;
        }
        Session session = new Session(player.getUuid(), npcUuid, playerRef, store, groupKey, uiPlayer);
        TameworkCompanionCardPage page = new TameworkCompanionCardPage(uiPlayer, npcUuid, view, session);
        session.page = page;
        // Decided before the page builds, so the card opens straight in its camera layout.
        boolean camera = TameworkInspectPreferencesComponent.cameraEnabled(playerRef, store)
                && CompanionInspectCamera.available(player, playerRef, store);
        page.useCameraLayout(camera);
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[tw-companion-card] could not open the card for npc=" + npcUuid, ex);
            return;
        }
        openPages.put(player.getUuid(), session);
        // A card replacing another card has already released the old camera in its close callback.
        session.cameraYaw = camera ? CompanionInspectCamera.apply(player, playerRef, npcRef, store, null, false) : null;
        session.cameraLocked = session.cameraYaw != null;
        if (session.cameraLocked) {
            page.showCameraControls();
        }
        scheduleRefresh(session);
    }

    @Override
    public void update(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef, @Nonnull Ref<EntityStore> npcRef,
                       @Nonnull Store<EntityStore> store, @Nonnull CompanionCardInteractions interactions) {
        Session session = openPages.get(player.getUuid());
        TameworkCompanionCardPage page = session == null ? null : session.page;
        UUID npcUuid = npcUuid(npcRef, store);
        if (page == null || page.isClosed() || npcUuid == null || !npcUuid.equals(page.npcUuid())
                || !showing(player, page)) {
            return;
        }
        TameworkCompanionCardPage.View view = buildView(player, playerRef, npcRef, store, interactions,
                session.groupKey, page.view().foods(), page.view().appearance());
        if (view == null) {
            page.closeCard();
            return;
        }
        page.show(view);
    }

    @Nullable
    private TameworkCompanionCardPage.View buildView(Player player, Ref<EntityStore> playerRef,
                                                     Ref<EntityStore> npcRef, Store<EntityStore> store,
                                                     CompanionCardInteractions interactions,
                                                     String groupKey,
                                                     List<CommandTargetHudViewModel.FoodRow> foods,
                                                     List<CommandTargetHudViewModel.AttachmentRow> appearance) {
        NPCEntity npc = npcRef.isValid() ? store.getComponent(npcRef, NPCEntity.getComponentType()) : null;
        if (npc == null || dying(npcRef, store)) {
            return null;
        }
        LinkedNpcEntry entry = snapshots.buildLoadedEntry(player, npcRef, store,
                CommandLoadedNpcStatusSnapshotService.NpcStatusContext.empty(npc.getUuid()),
                CommandLoadedNpcStatusSnapshotService.SnapshotOptions.linkedPanel());
        if (entry == null) {
            return null;
        }
        // The owner, or a player the owner shares the companion with, uses the card; anyone else only
        // reads it. Each button then needs the access level for its own action.
        AccessLevel access = access(player, npcRef, store);
        boolean manage = AccessAction.CARE.allowedAt(access);
        boolean generic = manage && CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store);
        List<LinkedNpcEntry.GroupMembership> groupOptions = List.of();
        if (generic) {
            entry = entry.withOwnedActions();
            // Groups are the viewer's own, but grouping a shared companion is for commanding it.
            if (AccessAction.COMMAND.allowedAt(access)) {
                groupOptions = CommandCompanionGroups.definedGroups(player).stream()
                        .map(group -> new LinkedNpcEntry.GroupMembership(group.groupId, group.name, group.colorHex))
                        .toList();
            }
            List<String> memberships = groupOptions.isEmpty() ? List.of()
                    : CommandCompanionGroups.groups(player, groupKey, npc.getUuid());
            entry = entry.withCompanionGroups(groupKey, groupOptions.stream()
                    .filter(group -> memberships.contains(group.id())).toList(), true);
        }
        CardRenameAvailability rename = CardRenameAvailability.OFF;
        NamingFeatureHandler naming = naming();
        if (generic && naming != null && AccessAction.RENAME.allowedAt(access)) {
            rename = naming.cardRenameAvailability(player, playerRef, npcRef, store,
                    CompanionCardPreferences.renameMode());
        }
        return new TameworkCompanionCardPage.View(entry, manage ? interactions : CompanionCardInteractions.EMPTY,
                manage, generic, access, ownerName(npcRef, store), rename, groupOptions,
                TameworkInspectPreferencesComponent.closeAfterCommand(playerRef, store), foods, appearance,
                generic && access == AccessLevel.OWNER ? privateState(player.getUuid(), npcRef, store) : null);
    }

    /**
     * Whether the owner marked this companion private, or {@code null} when the card should not offer the switch:
     * a companion that is never shared (bonded), one without a profile, or an owner who shares with nobody and has
     * not marked it private. Reads memory only.
     */
    @Nullable
    private static Boolean privateState(UUID owner, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        UUID profileId = profileId(npcRef, store);
        if (profileId == null) {
            return null;
        }
        SharedAccessService service = SharedAccessService.get();
        boolean marked = service.grants(owner).privateCompanions().contains(profileId);
        if (!marked && service.isPrivate(owner, profileId)) {
            // Never shared whatever the flag says, so a switch would mislead.
            return null;
        }
        return marked || service.sharesAnything(owner) ? marked : null;
    }

    @Nullable
    private static UUID profileId(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkCompanionComponent> type = TameworkCompanionComponent.getComponentType();
        TameworkCompanionComponent companion = type == null || !npcRef.isValid() ? null : store.getComponent(npcRef, type);
        return companion == null ? null : companion.getProfileId();
    }

    /**
     * The viewer's group key for this companion: its profile when the companion index knows one, as in the
     * companions window, otherwise its NPC UUID. Resolved once per card, since it reads the owner's index.
     */
    private String groupKey(Player player, UUID npcUuid) {
        String key = ownedRows == null ? null : ownedRows.profileForRow(player.getUuid(), npcUuid)
                .map(profile -> CommandCompanionGroups.profileKey(profile.toString())).orElse(null);
        if (key == null) {
            return CommandCompanionGroups.entityKey(npcUuid);
        }
        CommandCompanionGroups.promoteProfile(player, key, npcUuid);
        return key;
    }

    /** One open card: its viewer, companion and page. Page callbacks run on the world thread. */
    private final class Session implements TameworkCompanionCardPage.Actions {
        private final UUID playerUuid;
        private final UUID npcUuid;
        private final Ref<EntityStore> playerRef;
        private final Store<EntityStore> store;
        private final String groupKey;
        private final PlayerRef uiPlayer;
        private TameworkCompanionCardPage page;
        /** Whether this card took the player's camera; its close hands it back. */
        private boolean cameraLocked;
        /** Where the camera is turning to while it is locked; rotate clicks turn it. */
        @Nullable
        private Float cameraYaw;
        private int watches;

        private Session(UUID playerUuid, UUID npcUuid, Ref<EntityStore> playerRef, Store<EntityStore> store,
                        String groupKey, PlayerRef uiPlayer) {
            this.playerUuid = playerUuid;
            this.npcUuid = npcUuid;
            this.playerRef = playerRef;
            this.store = store;
            this.groupKey = groupKey;
            this.uiPlayer = uiPlayer;
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
            Live live = live(AccessAction.BREED);
            if (live != null && live.entry() != null && live.entry().breedingAvailable()) {
                panelActions.toggleLoadedNpcBreeding(live.player(), live.npcRef(), store, npcUuid,
                        live.entry().displayName());
                refresh();
            }
        }

        @Override
        public void toggleFlight() {
            if (live(AccessAction.MOUNT) != null) {
                flight.toggle(playerUuid, playerRef, store, CARD_SOURCE_ID, npcUuid);
                refresh();
            }
        }

        @Override
        public void toggleShoulder() {
            if (live(AccessAction.MOUNT) != null) {
                shoulder.toggle(playerUuid, playerRef, store, CARD_SOURCE_ID, npcUuid);
                refresh();
            }
        }

        @Override
        public void setHome() {
            Live live = live(AccessAction.COMMAND);
            if (live != null && live.entry() != null && live.entry().loaded()) {
                homes.setHome(live.player(), live.npcRef(), store);
                refresh();
            }
        }

        @Override
        public void returnHome() {
            Live live = live(AccessAction.COMMAND);
            if (live != null) {
                homes.returnHome(live.player(), playerRef, live.npcRef(), store);
                refresh();
            }
        }

        @Override
        public void assignGroups(@Nonnull List<String> groupIds) {
            Live live = live(AccessAction.COMMAND);
            if (live != null && !CommandCompanionGroups.assignDefined(live.player(), groupKey, npcUuid, groupIds)) {
                // Rejected (a group was deleted meanwhile): show the saved memberships again.
                refresh();
            }
        }

        @Override
        public void talents() {
            // Talent points are the owner's to spend.
            Live live = live(AccessAction.OWNER_ONLY);
            if (live == null) {
                return;
            }
            talents.openTalentPage(live.player(), () -> {
                Live current = live(AccessAction.OWNER_ONLY);
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
            Live live = live(AccessAction.RENAME);
            NamingFeatureHandler naming = naming();
            if (live == null || naming == null) {
                return;
            }
            naming.beginCardNaming(live.player(), playerRef, live.npcRef(), store,
                    CompanionCardPreferences.renameMode(), () -> reopen(false));
        }

        @Override
        public void release() {
            Live live = live(AccessAction.OWNER_ONLY);
            if (live != null) {
                page.closeCard();
                releases.releaseLoaded(live.player(), npcUuid);
            }
        }

        @Override
        public void cull() {
            Live live = live(AccessAction.CULL);
            if (live != null) {
                page.closeCard();
                culls.cullLoaded(live.player(), npcUuid);
            }
        }

        @Override
        public void rotateCamera(int direction) {
            Player player = player();
            Ref<EntityStore> npcRef = npcRef();
            if (!cameraLocked || cameraYaw == null || player == null || npcRef == null) {
                return;
            }
            // Clicks during a turn add up: each one turns the target, and the client glides on towards it.
            Float turned = CompanionInspectCamera.apply(player, playerRef, npcRef, store,
                    CompanionInspectCamera.turned(cameraYaw, direction), true);
            if (turned != null) {
                cameraYaw = turned;
            }
        }

        @Override
        public void toggleCloseAfterCommand() {
            if (playerRef.isValid()) {
                TameworkInspectPreferencesComponent.setCloseAfterCommand(playerRef, store,
                        !TameworkInspectPreferencesComponent.closeAfterCommand(playerRef, store));
                refresh();
            }
        }

        @Override
        public void togglePrivate() {
            Live live = live(AccessAction.OWNER_ONLY);
            // The private flag is the owner's own choice, so this compares identity, not access.
            if (live == null || !playerUuid.equals(links.resolveOwnerId(live.npcRef(), store))) {
                return;
            }
            UUID profileId = profileId(live.npcRef(), store);
            Boolean current = privateState(playerUuid, live.npcRef(), store);
            if (profileId != null && current != null) {
                SharedAccessService.get().setPrivate(playerUuid, profileId, !current);
            }
            refresh();
        }

        @Override
        public void closed() {
            Session current = openPages.get(playerUuid);
            openPages.remove(playerUuid, this);
            // Skip when a newer card already holds the camera (its open ran before this close arrived).
            if (cameraLocked && (current == null || current == this)) {
                CompanionInspectCamera.release(uiPlayer);
            }
            cameraLocked = false;
        }

        /** Queues a request for the role tick and registers the interaction that wakes it. */
        private boolean request(CompanionCardRequests.Request request) {
            // Any access level may ask; the role tick checks each entry and mode for this viewer.
            Live live = live(null);
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
                openPages.remove(playerUuid, this);
                return;
            }
            Ref<EntityStore> npcRef = npcRef();
            TameworkCompanionCardPage.View view = player == null || npcRef == null ? null
                    : buildView(player, playerRef, npcRef, store, page.view().interactions(), groupKey,
                            page.view().foods(), page.view().appearance());
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
         * The current viewer and companion, or {@code null} when either is gone or the viewer is neither the
         * companion's owner nor a player the owner shares it with. With {@code action}, a built-in action, also
         * requires that the viewer's access covers it and that the companion accepts generic actions. Read now, so
         * access the owner took back since the card was drawn no longer counts.
         */
        @Nullable
        private Live live(@Nullable AccessAction action) {
            Player player = player();
            Ref<EntityStore> npcRef = npcRef();
            if (player == null || npcRef == null) {
                return null;
            }
            AccessLevel access = access(player, npcRef, store);
            if (!AccessAction.CARE.allowedAt(access) || action != null && (!action.allowedAt(access)
                    || !CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store))) {
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
            // A dying companion counts as gone: no command reaches it and the panel closes.
            return ref != null && ref.isValid() && ref.getStore() == store && !dying(ref, store) ? ref : null;
        }

        /**
         * Closes the panel because its companion died, unloaded, was captured or otherwise left this world. Closing
         * runs the page's dismiss callback, which hands the camera back. A panel already replaced by another page
         * (talents, rename) is only forgotten, so that page stays open.
         */
        private void companionGone() {
            Player player = player();
            if (player != null && !showing(player, page)) {
                openPages.remove(playerUuid, this);
                return;
            }
            page.closeCard();
        }
    }

    private static boolean dying(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return store.getComponent(npcRef, DeathComponent.getComponentType()) != null;
    }

    private record Live(Player player, Ref<EntityStore> npcRef, @Nullable LinkedNpcEntry entry) {
    }

    private void scheduleRefresh(Session session) {
        CompletableFuture.delayedExecutor(WATCH_MS, TimeUnit.MILLISECONDS).execute(() -> {
            World world = session.store.getExternalData() == null ? null : session.store.getExternalData().getWorld();
            if (stopped || world == null || !world.isAlive() || session.page == null || session.page.isClosed()) {
                return;
            }
            try {
                world.execute(() -> {
                    if (stopped || session.page.isClosed() || openPages.get(session.playerUuid) != session) {
                        return;
                    }
                    if (session.npcRef() == null) {
                        session.companionGone();
                    } else if (++session.watches % WATCHES_PER_REFRESH == 0) {
                        session.refresh();
                    }
                    if (!session.page.isClosed() && openPages.get(session.playerUuid) == session) {
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

    /**
     * The viewer's access level on the loaded companion: OWNER for its owner, NONE for a stranger and for an
     * unowned companion. World thread.
     */
    private AccessLevel access(Player player, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return CompanionAccess.level(links.resolveOwnerId(npcRef, store), player.getUuid(), npcRef, store);
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
