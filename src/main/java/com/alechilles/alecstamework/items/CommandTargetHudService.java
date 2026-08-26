package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.actions.TameworkTameFoodDisplayResolver;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shows a compact right-side status HUD while a player points a command item at a supported NPC.
 */
public final class CommandTargetHudService extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 100L;
    private static final long TARGET_SCAN_INTERVAL_MS = 200L;
    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final long STATIC_DISPLAY_CACHE_MS = 30_000L;
    private static final int MAX_CANDIDATES_PER_PASS = 4;
    private static final int RESERVED_ACTIVE_CANDIDATES = 1;

    private final CommandItemRegistry registry;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    private final CommandTargetHudFoodResolver foodResolver;
    private final TameworkTameFoodDisplayResolver tameFoodDisplayResolver;
    private final CommandTargetHudAttachmentResolver attachmentResolver;
    private final CommandTargetHudTameRequirementResolver tameRequirementResolver;
    private final CommandTargetHudActivationTracker activationTracker;
    private final CommandTargetInspector targetInspector;
    private final CommandTargetHudStateStore hudStateStore;
    private final CommandTargetHudPresentationCoordinator presentationCoordinator;
    private final Map<StaticTargetCacheKey, StaticTargetDisplay> staticTargetCache = new HashMap<>();
    private final Map<UUID, DebugLogState> debugLogStateByPlayer = new HashMap<>();

    public CommandTargetHudService(CommandItemRegistry registry) {
        this(registry, new CommandTargetHudActivationTracker(), new CommandTargetInspector(),
                resolveCommandHudRegistry());
    }

    public CommandTargetHudService(CommandItemRegistry registry,
                                   @Nonnull CommandTargetHudActivationTracker activationTracker) {
        this(registry, activationTracker, new CommandTargetInspector(),
                resolveCommandHudRegistry());
    }

    public CommandTargetHudService(CommandItemRegistry registry,
                                   @Nonnull CommandTargetHudActivationTracker activationTracker,
                                   @Nonnull CommandTargetInspector targetInspector) {
        this(registry, activationTracker, targetInspector, resolveCommandHudRegistry());
    }

    CommandTargetHudService(CommandItemRegistry registry,
                            @Nonnull CommandTargetHudActivationTracker activationTracker,
                            @Nonnull CommandTargetInspector targetInspector,
                            @Nullable CommandHudRegistry commandHudRegistry) {
        this.registry = registry;
        this.activationTracker = activationTracker;
        this.targetInspector = targetInspector;
        this.presentationCoordinator = new CommandTargetHudPresentationCoordinator(
                commandHudRegistry, (store, playerUuid) ->
                        activationTracker.markDirty(store, playerUuid));
        this.hudStateStore = new CommandTargetHudStateStore(
                activationTracker, presentationCoordinator);
        this.linkPolicyService = new CommandLinkPolicyService();
        CommandNpcNameResolver nameResolver = new CommandNpcNameResolver();
        this.loadedSnapshotService = new CommandLoadedNpcStatusSnapshotService(
                nameResolver,
                linkPolicyService,
                new CommandLinkedPanelProgressionPresentationService(),
                new CommandLinkedPanelCooldownSnapshotService()
        );
        this.foodResolver = new CommandTargetHudFoodResolver();
        this.tameFoodDisplayResolver = new TameworkTameFoodDisplayResolver();
        this.attachmentResolver = new CommandTargetHudAttachmentResolver();
        this.tameRequirementResolver = new CommandTargetHudTameRequirementResolver();
    }

    @Nullable
    private static CommandHudRegistry resolveCommandHudRegistry() {
        Tamework plugin = Tamework.getInstance();
        TameworkApi api = plugin == null ? null : plugin.getApi();
        if (api == null || !(api.commandHud() instanceof CommandHudRegistry registry)) {
            return null;
        }
        return registry;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        CommandTargetHudStateStore.StoreTickState tickState = hudStateStore.tickState(store);
        if (SWEEP_INTERVAL_MS > 0L && nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        processCandidatePlayers(store, nowMs);
    }

    private void processCandidatePlayers(@Nonnull Store<EntityStore> store, long nowMs) {
        CommandTargetHudActivationTracker.CandidateBatch batch =
                activationTracker.selectCandidateBatch(
                        store,
                        MAX_CANDIDATES_PER_PASS,
                        nowMs,
                        TARGET_SCAN_INTERVAL_MS,
                        RESERVED_ACTIVE_CANDIDATES
                );
        for (UUID playerUuid : batch.playerUuids()) {
            PlayerCandidate candidate = resolvePlayerCandidate(playerUuid, store);
            if (candidate == null) {
                debugMissingFromStore(playerUuid, nowMs);
                activationTracker.remove(store, playerUuid);
                continue;
            }
            updatePlayer(candidate.playerUuid(), candidate.player(), candidate.playerRef(), store, nowMs);
        }
    }

    @Nullable
    private PlayerCandidate resolvePlayerCandidate(@Nonnull UUID playerUuid,
                                                   @Nonnull Store<EntityStore> store) {
        if (store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        Ref<EntityStore> playerRef = store.getExternalData().getWorld().getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        Player player = playerType != null ? store.getComponent(playerRef, playerType) : null;
        return player != null ? new PlayerCandidate(playerUuid, player, playerRef) : null;
    }

    private void updatePlayer(@Nonnull UUID playerUuid,
                              @Nullable Player player,
                              @Nullable Ref<EntityStore> playerRef,
                              @Nonnull Store<EntityStore> store,
                              long nowMs) {
        if (!CommandHudClientReadiness.canRender(player)) {
            return;
        }
        CommandTargetHudStateStore.HudState previous = hudStateStore.stateForStore(store, playerUuid);
        if (!activationTracker.shouldInspectPlayer(store, playerUuid, nowMs)) {
            return;
        }
        String cachedActiveItemId = activationTracker.cachedCommandItemId(store, playerUuid);
        if (cachedActiveItemId != null
                && !activationTracker.isDirty(store, playerUuid)
                && !shouldScanTarget(previous, cachedActiveItemId, nowMs)) {
            return;
        }

        ActiveCommandItem activeCommand = resolveActiveCommandItem(player);
        if (activeCommand == null) {
            activationTracker.recordResolvedHand(store, playerUuid, null, false, nowMs);
            debug(playerUuid, nowMs, "no-command", "no command item in active hand; previousVisible=" + isVisible(previous));
            hideHud(store, playerUuid, player);
            return;
        }
        activationTracker.recordResolvedHand(store, playerUuid, activeCommand.itemId(), true, nowMs);
        if (!shouldScanTarget(previous, activeCommand.itemId(), nowMs)) {
            return;
        }

        TargetCandidate candidate = resolveTarget(
                playerUuid,
                player,
                playerRef,
                activeCommand,
                store,
                nowMs
        );
        String targetKey = candidate != null ? candidate.key() : null;
        if (!shouldRefresh(playerUuid, previous, targetKey, nowMs)) {
            rememberScan(store, playerUuid, previous, activeCommand.itemId(), nowMs);
            return;
        }
        if (candidate == null) {
            debug(playerUuid, nowMs, "no-target:" + activeCommand.itemId(),
                    "no supported target; item=" + activeCommand.itemId() + ", previousVisible=" + isVisible(previous));
            hideHudAndRememberNoTarget(store, playerUuid, player, activeCommand.itemId(), nowMs);
            return;
        }
        CommandTargetHudViewModel model = buildModel(player, candidate.npcRef(), candidate.npc(), store, nowMs);
        if (model == null) {
            debug(playerUuid, nowMs, "model-null:" + targetKey,
                    "model build returned null; target=" + targetKey + ", item=" + activeCommand.itemId());
            hideHudAndRememberNoTarget(store, playerUuid, player, activeCommand.itemId(), nowMs);
            return;
        }
        showHud(store, playerUuid, player, activeCommand.config(), model,
                targetKey, activeCommand.itemId(), nowMs);
    }

    @Nullable
    private TargetCandidate resolveTarget(@Nonnull UUID playerUuid,
                                          @Nullable Player player,
                                          @Nullable Ref<EntityStore> playerRef,
                                          @Nonnull ActiveCommandItem activeCommand,
                                          @Nonnull Store<EntityStore> store,
                                          long nowMs) {
        if (player == null || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        TwCommandItemConfig config = activeCommand.config();
        if (config == null || !config.isEnabled()) {
            return null;
        }

        CommandTargetInspector.Target target = targetInspector.resolveTarget(
                playerUuid,
                playerRef,
                store,
                nowMs
        );
        if (target == null) {
            return null;
        }
        Ref<EntityStore> npcRef = target.reference();
        NPCEntity npc = target.npc();
        if (npc == null || npc.getUuid() == null || !isSupportedTarget(npcRef, npc, player, config, store)) {
            return null;
        }
        return new TargetCandidate(buildTargetKey(npc.getUuid(), activeCommand.itemId()), npcRef, npc);
    }

    @Nullable
    private ActiveCommandItem resolveActiveCommandItem(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        ItemStack activeStack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (activeStack == null || activeStack.isEmpty() || activeStack.getItemId() == null) {
            return null;
        }
        TwCommandItemConfig config = registry != null ? registry.get(activeStack.getItemId()) : null;
        if (config == null || !config.isEnabled()) {
            return null;
        }
        return new ActiveCommandItem(activeStack.getItemId(), config);
    }

    private boolean isSupportedTarget(@Nonnull Ref<EntityStore> npcRef,
                                      @Nonnull NPCEntity npc,
                                      @Nonnull Player player,
                                      @Nonnull TwCommandItemConfig config,
                                      @Nonnull Store<EntityStore> store) {
        String roleId = linkPolicyService.resolveRoleId(npc);
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);
        UUID playerUuid = player.getUuid();
        boolean commandEligible = playerUuid != null
                && linkPolicyService.isRoleAllowed(roleId, config, tamed)
                && linkPolicyService.passesOwnerAndTamed(
                config.isRequireOwner(),
                config.isRequireTamed(),
                npcRef,
                playerUuid,
                store
        );
        return shouldShowForEligibility(tamed, commandEligible, isUntamedTameableTarget(tamed, roleId));
    }

    @Nullable
    private CommandTargetHudViewModel buildModel(@Nonnull Player player,
                                                 @Nonnull Ref<EntityStore> npcRef,
                                                 @Nonnull NPCEntity npc,
                                                 @Nonnull Store<EntityStore> store,
                                                 long nowMs) {
        String roleId = linkPolicyService.resolveRoleId(npc);
        LinkedNpcEntry status = loadedSnapshotService.buildLoadedEntry(
                player,
                npcRef,
                store,
                new CommandLoadedNpcStatusSnapshotService.NpcStatusContext(
                        npc.getUuid(),
                        null,
                        false,
                        true,
                        false,
                        false,
                        null,
                        null,
                        null,
                        roleId,
                        null
                ),
                CommandLoadedNpcStatusSnapshotService.SnapshotOptions.compactHud()
        );
        if (status == null) {
            return null;
        }
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);
        StaticTargetDisplay staticDisplay = resolveStaticTargetDisplay(player, npcRef, npc, roleId, tamed, store, nowMs);
        return new CommandTargetHudViewModel(
                status,
                staticDisplay.favoriteFood(),
                tamed ? staticDisplay.foodRows() : List.of(),
                staticDisplay.attachmentRows(),
                resolveTameRequirement(npcRef, staticDisplay.tameRequirement(), store),
                staticDisplay.ownerDisplayName()
        );
    }

    @Nonnull
    private StaticTargetDisplay resolveStaticTargetDisplay(@Nonnull Player player,
                                                           @Nonnull Ref<EntityStore> npcRef,
                                                           @Nonnull NPCEntity npc,
                                                           @Nullable String roleId,
                                                           boolean tamed,
                                                           @Nonnull Store<EntityStore> store,
                                                           long nowMs) {
        StaticTargetCacheKey key = new StaticTargetCacheKey(
                npc.getUuid(),
                resolveLanguage(player),
                roleId,
                tamed
        );
        StaticTargetDisplay cached = staticTargetCache.get(key);
        if (cached != null && isStaticDisplayCacheValidForTests(cached.cachedAtMs(), nowMs, STATIC_DISPLAY_CACHE_MS)) {
            return cached;
        }
        if (cached != null) {
            staticTargetCache.remove(key);
        }
        StaticTargetDisplay resolved = buildStaticTargetDisplay(player, npcRef, npc, roleId, tamed, store, nowMs);
        staticTargetCache.put(key, resolved);
        return resolved;
    }

    @Nonnull
    private StaticTargetDisplay buildStaticTargetDisplay(@Nonnull Player player,
                                                         @Nonnull Ref<EntityStore> npcRef,
                                                         @Nonnull NPCEntity npc,
                                                         @Nullable String roleId,
                                                         boolean tamed,
                                                         @Nonnull Store<EntityStore> store,
                                                         long nowMs) {
        TameworkTameFoodDisplayResolver.FoodDisplay foodDisplay =
                tameFoodDisplayResolver.resolveFoodDisplayItemIds(
                        roleId, npc.getRole(), npcRef, store, tamed);
        List<CommandTargetHudViewModel.FoodRow> foodRows =
                foodResolver.resolveFoodEntries(player, foodDisplay.entries());
        return new StaticTargetDisplay(
                tamed ? null : firstFoodRow(foodRows, player, foodDisplay.favoriteItemIds()),
                tamed ? foodRows : List.of(),
                attachmentResolver.resolveRows(roleId, resolveModelAssetId(npcRef, store), resolveAttachmentIds(npcRef, store)),
                resolveCachedTameRequirement(roleId, npc.getRole(), tamed),
                resolveOwnerDisplayName(npcRef, store),
                nowMs
        );
    }

    @Nullable
    private static String resolveLanguage(@Nonnull Player player) {
        PlayerRef playerRef = player.getPlayerRef();
        String language = playerRef != null ? playerRef.getLanguage() : null;
        return language != null && !language.isBlank() ? language : null;
    }

    @Nullable
    private String resolveOwnerDisplayName(@Nonnull Ref<EntityStore> npcRef,
                                           @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType != null ? store.getComponent(npcRef, ownerType) : null;
        return resolveOwnerDisplayNameForTests(owner);
    }

    @Nullable
    static String resolveOwnerDisplayNameForTests(@Nullable TameworkOwnerComponent owner) {
        if (owner == null || !owner.hasOwner()) {
            return null;
        }
        String ownerName = owner.getOwnerName();
        return ownerName != null && !ownerName.isBlank() ? ownerName : null;
    }

    @Nullable
    private CommandTargetHudViewModel.FoodRow firstFoodRow(@Nonnull List<CommandTargetHudViewModel.FoodRow> rows,
                                                          @Nonnull Player player,
                                                          @Nullable String[] fallbackItemIds) {
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return foodResolver.resolveFavoriteFood(player, fallbackItemIds);
    }

    @Nullable
    private Map<String, String> resolveAttachmentIds(@Nonnull Ref<EntityStore> npcRef,
                                                     @Nonnull Store<EntityStore> store) {
        TameworkAttachmentsComponent attachments = store.getComponent(npcRef, TameworkAttachmentsComponent.getComponentType());
        return resolveDisplayAttachmentIds(
                attachments != null ? attachments.getAttachmentIds() : null,
                CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store)
        );
    }

    @Nullable
    static Map<String, String> resolveDisplayAttachmentIds(@Nullable Map<String, String> persistedAttachments,
                                                           @Nullable Map<String, String> modelAttachments) {
        if (persistedAttachments != null && !persistedAttachments.isEmpty()) {
            return persistedAttachments;
        }
        return modelAttachments != null && !modelAttachments.isEmpty() ? modelAttachments : null;
    }

    @Nullable
    private String resolveModelAssetId(@Nonnull Ref<EntityStore> npcRef,
                                       @Nonnull Store<EntityStore> store) {
        ModelComponent modelComponent = store.getComponent(npcRef, ModelComponent.getComponentType());
        Model model = modelComponent != null ? modelComponent.getModel() : null;
        return model != null ? model.getModelAssetId() : null;
    }

    @Nullable
    private CachedTameRequirement resolveCachedTameRequirement(@Nullable String roleId,
                                                               @Nullable com.hypixel.hytale.server.npc.role.Role role,
                                                               boolean tamed) {
        if (tamed) {
            return null;
        }
        double requiredSeconds = tameFoodDisplayResolver.resolveRequiredTranquilizerSeconds(roleId, role);
        if (requiredSeconds <= 0.0) {
            return null;
        }
        CommandTargetHudViewModel.TameRequirementRow row =
                tameRequirementResolver.fromRequiredRemainingSeconds(requiredSeconds, null);
        return row != null ? new CachedTameRequirement(row.tranquilizerRequired(), row.requiredStacks()) : null;
    }

    @Nullable
    private CommandTargetHudViewModel.TameRequirementRow resolveTameRequirement(@Nonnull Ref<EntityStore> npcRef,
                                                                                @Nullable CachedTameRequirement cached,
                                                                                @Nonnull Store<EntityStore> store) {
        if (cached == null) {
            return null;
        }
        String currentStacksText = resolveCurrentTranquilizerStacksText(npcRef, store);
        return new CommandTargetHudViewModel.TameRequirementRow(
                cached.tranquilizerRequired(),
                cached.requiredStacks(),
                currentStacksText
        );
    }

    @Nullable
    private String resolveCurrentTranquilizerStacksText(@Nonnull Ref<EntityStore> npcRef,
                                                       @Nonnull Store<EntityStore> store) {
        TameworkTranquilizerPeakComponent peak =
                store.getComponent(npcRef, TameworkTranquilizerPeakComponent.getComponentType());
        double peakSeconds = peak != null ? peak.getPeakRemainingSeconds() : 0.0;
        int stacks = TranquilizerStackDisplayService.computeStacks(peakSeconds);
        if (stacks <= 0) {
            return null;
        }
        String remainingText = TranquilizerStackDisplayService.formatRemainingDuration(peakSeconds);
        return TranquilizerStackDisplayService.formatStackValue(stacks, remainingText, 0);
    }

    private void showHud(@Nonnull Store<EntityStore> store,
                         @Nonnull UUID playerUuid,
                         @Nonnull Player player,
                         @Nonnull TwCommandItemConfig config,
                         @Nonnull CommandTargetHudViewModel model,
                         @Nonnull String targetKey,
                         @Nonnull String activeItemId,
                         long nowMs) {
        CommandTargetHudPresentation presentation =
                presentationCoordinator.present(
                        store, player, config, targetKey, model, activeItemId);
        if (presentation == null || presentation.closed()) {
            presentationCoordinator.closePlayer(playerUuid);
            hudStateStore.remove(store, playerUuid);
            return;
        }
        debug(playerUuid, nowMs, "present:" + targetKey,
                "presented target hud; target=" + targetKey + ", item=" + activeItemId
                        + ", custom=" + presentation.custom()
                        + ", display=" + model.status().displayName());
        hudStateStore.put(
                store,
                playerUuid,
                new CommandTargetHudStateStore.HudState(
                        store, targetKey, nowMs, presentation, true, nowMs, activeItemId
                )
        );
    }

    private void hideHud(@Nonnull Store<EntityStore> store,
                         @Nonnull UUID playerUuid,
                         @Nullable Player player) {
        CommandTargetHudStateStore.HudState previous = hudStateStore.stateForStore(store, playerUuid);
        if (previous == null || previous.presentation() == null) {
            debug(playerUuid, System.currentTimeMillis(), "hide-empty", "hide requested with no stored hud state");
            hudStateStore.remove(store, playerUuid);
            return;
        }
        if (player == null || player.getPlayerRef() == null || player.getHudManager() == null) {
            presentationCoordinator.closePlayer(playerUuid);
            debug(playerUuid, System.currentTimeMillis(), "hide-fallback",
                    "hide fallback clear; previousTarget=" + previous.targetKey());
            hudStateStore.remove(store, playerUuid);
            return;
        }
        presentationCoordinator.hide(player);
        debug(playerUuid, System.currentTimeMillis(), "hide-manager",
                "removed hud through manager; previousTarget=" + previous.targetKey());
        hudStateStore.remove(store, playerUuid);
    }

    private void hideHudAndRememberNoTarget(@Nonnull Store<EntityStore> store,
                                            @Nonnull UUID playerUuid,
                                            @Nullable Player player,
                                            @Nonnull String activeItemId,
                                            long nowMs) {
        CommandTargetHudStateStore.HudState previous = hudStateStore.stateForStore(store, playerUuid);
        if (previous != null && previous.presentation() != null && previous.visible()) {
            if (player != null && player.getPlayerRef() != null && player.getHudManager() != null) {
                presentationCoordinator.hide(player);
                debug(playerUuid, nowMs, "hide-no-target-manager",
                        "removed hud after target lost; previousTarget=" + previous.targetKey() + ", item=" + activeItemId);
            } else {
                presentationCoordinator.closePlayer(playerUuid);
                debug(playerUuid, nowMs, "hide-no-target-fallback",
                        "fallback clear after target lost; previousTarget=" + previous.targetKey() + ", item=" + activeItemId);
            }
        }
        hudStateStore.put(
                store,
                playerUuid,
                new CommandTargetHudStateStore.HudState(
                        store,
                        null,
                        previous != null ? previous.lastRefreshMs() : 0L,
                        null,
                        false,
                        nowMs,
                        activeItemId
                )
        );
    }

    private void rememberScan(@Nonnull Store<EntityStore> store,
                              @Nonnull UUID playerUuid,
                              @Nullable CommandTargetHudStateStore.HudState previous,
                              @Nonnull String activeItemId,
                              long nowMs) {
        if (previous == null) {
            hudStateStore.put(
                    store,
                    playerUuid,
                    new CommandTargetHudStateStore.HudState(
                            store, null, 0L, null, false, nowMs, activeItemId
                    )
            );
            return;
        }
        hudStateStore.put(
                store,
                playerUuid,
                new CommandTargetHudStateStore.HudState(
                        store,
                        previous.targetKey(),
                        previous.lastRefreshMs(),
                        previous.presentation(),
                        previous.visible(),
                        nowMs,
                        activeItemId
                )
        );
    }

    private void debugMissingFromStore(@Nullable UUID playerUuid, long nowMs) {
        if (playerUuid == null) {
            return;
        }
        debug(playerUuid, nowMs, "missing-from-store",
                "candidate was not present in this world store; dropping any stale hud state");
    }

    private void debug(@Nonnull UUID playerUuid,
                       long nowMs,
                       @Nonnull String key,
                       @Nonnull String message) {
        if (!CommandTargetHudDebugLog.isEnabled()) {
            return;
        }
        DebugLogState previous = debugLogStateByPlayer.get(playerUuid);
        if (previous != null && key.equals(previous.key()) && nowMs < previous.nextAtMs()) {
            return;
        }
        debugLogStateByPlayer.put(playerUuid, new DebugLogState(key, nowMs + 1_000L));
        CommandTargetHudDebugLog.info("player=" + playerUuid + " " + message);
    }

    private static boolean isVisible(@Nullable CommandTargetHudStateStore.HudState state) {
        return state != null && state.visible();
    }

    @Nonnull
    private static String buildTargetKey(@Nonnull UUID npcUuid, @Nullable String activeItemId) {
        return npcUuid + "|" + (activeItemId == null ? "" : activeItemId);
    }

    @Nonnull
    static String buildTargetKeyForTests(@Nonnull UUID npcUuid, @Nullable String activeItemId) {
        return buildTargetKey(npcUuid, activeItemId);
    }

    private boolean shouldRefresh(@Nonnull UUID playerUuid,
                                  @Nullable CommandTargetHudStateStore.HudState previous,
                                  @Nullable String targetKey,
                                  long nowMs) {
        if (targetKey == null || targetKey.isBlank()) {
            return previous != null && previous.visible();
        }
        if (previous == null || previous.presentation() == null || !previous.visible()) {
            return true;
        }
        if (presentationCoordinator.needsRefresh(playerUuid)) {
            return true;
        }
        return shouldRefreshForTests(previous.targetKey(), previous.visible(), targetKey, previous.lastRefreshMs(), nowMs, REFRESH_INTERVAL_MS);
    }

    static boolean shouldRefreshForTests(@Nullable String previousTargetKey,
                                         @Nullable String currentTargetKey,
                                         long previousRefreshMs,
                                         long nowMs,
                                         long refreshIntervalMs) {
        return shouldRefreshForTests(previousTargetKey, true, currentTargetKey, previousRefreshMs, nowMs, refreshIntervalMs);
    }

    static boolean shouldRefreshForTests(@Nullable String previousTargetKey,
                                         boolean previousVisible,
                                         @Nullable String currentTargetKey,
                                         long previousRefreshMs,
                                         long nowMs,
                                         long refreshIntervalMs) {
        if (currentTargetKey == null || currentTargetKey.isBlank()) {
            return previousVisible && previousTargetKey != null && !previousTargetKey.isBlank();
        }
        if (!previousVisible || previousTargetKey == null || !previousTargetKey.equals(currentTargetKey)) {
            return true;
        }
        return nowMs - previousRefreshMs >= Math.max(0L, refreshIntervalMs);
    }

    private static boolean shouldScanTarget(@Nullable CommandTargetHudStateStore.HudState previous,
                                            @Nonnull String activeItemId,
                                            long nowMs) {
        if (previous == null) {
            return true;
        }
        return shouldScanTargetForTests(previous.activeItemId(), activeItemId, previous.lastTargetScanMs(), nowMs, TARGET_SCAN_INTERVAL_MS);
    }

    static boolean shouldScanTargetForTests(@Nullable String previousActiveItemId,
                                            @Nullable String currentActiveItemId,
                                            long previousScanMs,
                                            long nowMs,
                                            long scanIntervalMs) {
        if (currentActiveItemId == null || currentActiveItemId.isBlank()) {
            return true;
        }
        if (previousActiveItemId == null || !previousActiveItemId.equals(currentActiveItemId)) {
            return true;
        }
        return nowMs - previousScanMs >= Math.max(0L, scanIntervalMs);
    }

    static boolean shouldShowForEligibility(boolean tamed, boolean commandEligible, boolean untamedTameable) {
        return tamed ? commandEligible : commandEligible || untamedTameable;
    }

    static long sweepIntervalMsForTests() {
        return SWEEP_INTERVAL_MS;
    }

    static long targetScanIntervalMsForTests() {
        return TARGET_SCAN_INTERVAL_MS;
    }

    static float targetDistanceForTests() {
        return CommandTargetInspector.TARGET_DISTANCE;
    }

    static long refreshIntervalMsForTests() {
        return REFRESH_INTERVAL_MS;
    }

    static boolean isStaticDisplayCacheValidForTests(long cachedAtMs, long nowMs, long ttlMs) {
        return ttlMs > 0L && nowMs >= cachedAtMs && nowMs - cachedAtMs < ttlMs;
    }

    static double resolveRequiredTranquilizerSecondsForTests(@Nullable String requirementId,
                                                            @Nullable String jsonPayload) {
        return TameworkTameFoodDisplayResolver.resolveRequiredTranquilizerSeconds(requirementId, jsonPayload);
    }

    private static boolean isUntamedTameableTarget(boolean tamed, @Nullable String roleId) {
        if (tamed) {
            return false;
        }
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        return hasEnabledTameInteraction(config);
    }

    private static boolean hasEnabledTameInteraction(@Nullable TwInteractionConfig config) {
        if (config == null || !config.isEnabled()) {
            return false;
        }
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (entry instanceof TwInteractionConfig.TameInteraction && entry.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private record DebugLogState(@Nonnull String key,
                                 long nextAtMs) {
    }

    private record PlayerCandidate(@Nonnull UUID playerUuid,
                                   @Nonnull Player player,
                                   @Nonnull Ref<EntityStore> playerRef) {
    }

    private record StaticTargetCacheKey(@Nullable UUID npcUuid,
                                        @Nullable String language,
                                        @Nullable String roleId,
                                        boolean tamed) {
    }

    private record StaticTargetDisplay(@Nullable CommandTargetHudViewModel.FoodRow favoriteFood,
                                       @Nonnull List<CommandTargetHudViewModel.FoodRow> foodRows,
                                       @Nonnull List<CommandTargetHudViewModel.AttachmentRow> attachmentRows,
                                       @Nullable CachedTameRequirement tameRequirement,
                                       @Nullable String ownerDisplayName,
                                       long cachedAtMs) {
        private StaticTargetDisplay {
            foodRows = foodRows == null ? List.of() : List.copyOf(foodRows);
            attachmentRows = attachmentRows == null ? List.of() : List.copyOf(attachmentRows);
        }
    }

    private record CachedTameRequirement(boolean tranquilizerRequired, int requiredStacks) {
    }

    private record ActiveCommandItem(@Nonnull String itemId,
                                     @Nonnull TwCommandItemConfig config) {
    }

    private record TargetCandidate(@Nonnull String key,
                                   @Nonnull Ref<EntityStore> npcRef,
                                   @Nonnull NPCEntity npc) {
    }
}
