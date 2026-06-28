package com.alechilles.alecstamework.items;

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
import com.alechilles.alecstamework.ui.TameworkCommandTargetHud;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
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
    private static final long FALLBACK_DISCOVERY_INTERVAL_MS = 1_500L;
    private static final long TARGET_SCAN_INTERVAL_MS = 200L;
    private static final long PRESENTATION_PULSE_INTERVAL_MS = 250L;
    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final long STATIC_DISPLAY_CACHE_MS = 30_000L;
    private static final int MAX_CANDIDATES_PER_PASS = 4;
    private static final float TARGET_DISTANCE = 15.0f;

    private final CommandItemRegistry registry;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    private final CommandTargetHudFoodResolver foodResolver;
    private final TameworkTameFoodDisplayResolver tameFoodDisplayResolver;
    private final CommandTargetHudAttachmentResolver attachmentResolver;
    private final CommandTargetHudTameRequirementResolver tameRequirementResolver;
    private final CommandTargetHudActivationTracker activationTracker;
    private final Map<UUID, HudState> stateByPlayer = new HashMap<>();
    private final Map<StaticTargetCacheKey, StaticTargetDisplay> staticTargetCache = new HashMap<>();
    private final Map<UUID, DebugLogState> debugLogStateByPlayer = new HashMap<>();
    private long nextSweepAtMs;
    private long nextFallbackDiscoveryAtMs;
    private int nextCandidateOffset;

    public CommandTargetHudService(CommandItemRegistry registry) {
        this(registry, new CommandTargetHudActivationTracker());
    }

    public CommandTargetHudService(CommandItemRegistry registry,
                                   @Nonnull CommandTargetHudActivationTracker activationTracker) {
        this.registry = registry;
        this.activationTracker = activationTracker;
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

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        if (SWEEP_INTERVAL_MS > 0L && nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        if (nowMs >= nextFallbackDiscoveryAtMs) {
            nextFallbackDiscoveryAtMs = nowMs + FALLBACK_DISCOVERY_INTERVAL_MS;
            seedCandidatesFromPlayerSweep(store);
        }
        processCandidatePlayers(store, nowMs);
    }

    private void processCandidatePlayers(@Nonnull Store<EntityStore> store, long nowMs) {
        List<UUID> selectedCandidates = selectCandidatesForCurrentPass(activationTracker.candidatePlayerUuids());
        for (UUID playerUuid : selectedCandidates) {
            PlayerCandidate candidate = resolvePlayerCandidate(playerUuid, store);
            if (candidate == null) {
                dropInactiveCandidate(playerUuid);
                continue;
            }
            updatePlayer(candidate.playerUuid(), candidate.player(), candidate.playerRef(), store, nowMs);
        }
    }

    @Nonnull
    private List<UUID> selectCandidatesForCurrentPass(@Nonnull List<UUID> candidates) {
        if (candidates.isEmpty()) {
            nextCandidateOffset = 0;
            return List.of();
        }
        ArrayList<UUID> dirtyCandidates = new ArrayList<>();
        ArrayList<UUID> regularCandidates = new ArrayList<>();
        for (UUID candidate : candidates) {
            if (activationTracker.isDirty(candidate)) {
                dirtyCandidates.add(candidate);
            } else {
                regularCandidates.add(candidate);
            }
        }

        ArrayList<UUID> selected = new ArrayList<>(Math.min(MAX_CANDIDATES_PER_PASS, candidates.size()));
        for (UUID candidate : dirtyCandidates) {
            if (selected.size() >= MAX_CANDIDATES_PER_PASS) {
                return List.copyOf(selected);
            }
            selected.add(candidate);
        }

        int remaining = MAX_CANDIDATES_PER_PASS - selected.size();
        List<UUID> selectedRegular = selectCandidatesForPass(regularCandidates, remaining, nextCandidateOffset);
        selected.addAll(selectedRegular);
        nextCandidateOffset = nextCandidateOffsetForPass(nextCandidateOffset, selectedRegular.size(), regularCandidates.size());
        return selected.isEmpty() ? List.of() : List.copyOf(selected);
    }

    private void seedCandidatesFromPlayerSweep(@Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        if (playerType == null) {
            return;
        }
        store.forEachChunk(
                Query.and(playerType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) ->
                        seedCandidateChunk(chunk, playerType)
        );
    }

    private void seedCandidateChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                    @Nonnull ComponentType<EntityStore, Player> playerType) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Player player = chunk.getComponent(i, playerType);
            UUID playerUuid = player != null ? player.getUuid() : null;
            if (playerUuid == null) {
                continue;
            }
            activationTracker.markDirty(playerUuid);
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
        HudState previous = stateByPlayer.get(playerUuid);
        if (!activationTracker.shouldInspectPlayer(playerUuid, nowMs)) {
            return;
        }
        String cachedActiveItemId = activationTracker.cachedCommandItemId(playerUuid);
        if (cachedActiveItemId != null
                && !activationTracker.isDirty(playerUuid)
                && !shouldScanTarget(previous, cachedActiveItemId, nowMs)) {
            long presentationMs = pulsePresentation(playerUuid, previous, nowMs);
            rememberPresentation(playerUuid, previous, presentationMs);
            return;
        }

        ActiveCommandItem activeCommand = resolveActiveCommandItem(player);
        if (activeCommand == null) {
            activationTracker.recordResolvedHand(playerUuid, null, false, nowMs);
            debug(playerUuid, nowMs, "no-command", "no command item in active hand; previousVisible=" + isVisible(previous));
            hideHud(playerUuid, player);
            return;
        }
        activationTracker.recordResolvedHand(playerUuid, activeCommand.itemId(), true, nowMs);
        if (!shouldScanTarget(previous, activeCommand.itemId(), nowMs)) {
            long presentationMs = pulsePresentation(playerUuid, previous, nowMs);
            rememberPresentation(playerUuid, previous, presentationMs);
            return;
        }

        TargetCandidate candidate = resolveTarget(player, playerRef, activeCommand, store);
        String targetKey = candidate != null ? candidate.key() : null;
        if (!shouldRefresh(previous, targetKey, nowMs)) {
            long presentationMs = pulsePresentation(playerUuid, previous, nowMs);
            rememberScan(playerUuid, previous, activeCommand.itemId(), nowMs, presentationMs);
            return;
        }
        if (candidate == null) {
            debug(playerUuid, nowMs, "no-target:" + activeCommand.itemId(),
                    "no supported target; item=" + activeCommand.itemId() + ", previousVisible=" + isVisible(previous));
            hideHudAndRememberNoTarget(playerUuid, player, activeCommand.itemId(), nowMs);
            return;
        }
        CommandTargetHudViewModel model = buildModel(player, candidate.npcRef(), candidate.npc(), store, nowMs);
        if (model == null) {
            debug(playerUuid, nowMs, "model-null:" + targetKey,
                    "model build returned null; target=" + targetKey + ", item=" + activeCommand.itemId());
            hideHudAndRememberNoTarget(playerUuid, player, activeCommand.itemId(), nowMs);
            return;
        }
        showHud(playerUuid, player, model, targetKey, activeCommand.itemId(), nowMs);
    }

    @Nullable
    private TargetCandidate resolveTarget(@Nullable Player player,
                                          @Nullable Ref<EntityStore> playerRef,
                                          @Nonnull ActiveCommandItem activeCommand,
                                          @Nonnull Store<EntityStore> store) {
        if (player == null || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        TwCommandItemConfig config = activeCommand.config();
        if (config == null || !config.isEnabled()) {
            return null;
        }

        Ref<EntityStore> npcRef = TargetUtil.getTargetEntity(playerRef, TARGET_DISTANCE, store);
        if (npcRef == null || !npcRef.isValid() || npcRef.equals(playerRef)) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
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
                tameFoodDisplayResolver.resolveFoodDisplayItemIds(roleId, npc.getRole(), tamed);
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

    private void showHud(@Nonnull UUID playerUuid,
                         @Nonnull Player player,
                         @Nonnull CommandTargetHudViewModel model,
                         @Nonnull String targetKey,
                         @Nonnull String activeItemId,
                         long nowMs) {
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || player.getHudManager() == null) {
            stateByPlayer.remove(playerUuid);
            return;
        }
        String language = playerRef.getLanguage();
        HudState previous = stateByPlayer.get(playerUuid);
        TameworkCommandTargetHud hud = previous != null ? previous.hud() : null;
        if (hud == null) {
            hud = new TameworkCommandTargetHud(playerRef, model, language);
            player.getHudManager().addCustomHud(playerRef, hud);
            hud.present();
            debug(playerUuid, nowMs, "show:" + targetKey,
                    "created hud; target=" + targetKey + ", item=" + activeItemId + ", display=" + model.status().displayName());
        } else {
            hud.refresh(model, language);
            hud.present();
            debug(playerUuid, nowMs, "refresh:" + targetKey,
                    "refreshed hud; target=" + targetKey + ", item=" + activeItemId + ", display=" + model.status().displayName());
        }
        stateByPlayer.put(playerUuid, new HudState(targetKey, nowMs, hud, true, nowMs, activeItemId, nowMs));
    }

    private void hideHud(@Nonnull UUID playerUuid, @Nullable Player player) {
        HudState previous = stateByPlayer.get(playerUuid);
        if (previous == null || previous.hud() == null) {
            debug(playerUuid, System.currentTimeMillis(), "hide-empty", "hide requested with no stored hud state");
            stateByPlayer.remove(playerUuid);
            return;
        }
        if (player == null || player.getPlayerRef() == null || player.getHudManager() == null) {
            previous.hud().hideNow();
            debug(playerUuid, System.currentTimeMillis(), "hide-fallback",
                    "hide fallback clear; previousTarget=" + previous.targetKey());
            stateByPlayer.remove(playerUuid);
            return;
        }
        player.getHudManager().removeCustomHud(player.getPlayerRef(), TameworkCommandTargetHud.HUD_KEY);
        debug(playerUuid, System.currentTimeMillis(), "hide-manager",
                "removed hud through manager; previousTarget=" + previous.targetKey());
        stateByPlayer.remove(playerUuid);
    }

    private void hideHudAndRememberNoTarget(@Nonnull UUID playerUuid,
                                            @Nullable Player player,
                                            @Nonnull String activeItemId,
                                            long nowMs) {
        HudState previous = stateByPlayer.get(playerUuid);
        if (previous != null && previous.hud() != null && previous.visible()) {
            if (player != null && player.getPlayerRef() != null && player.getHudManager() != null) {
                player.getHudManager().removeCustomHud(player.getPlayerRef(), TameworkCommandTargetHud.HUD_KEY);
                debug(playerUuid, nowMs, "hide-no-target-manager",
                        "removed hud after target lost; previousTarget=" + previous.targetKey() + ", item=" + activeItemId);
            } else {
                previous.hud().hideNow();
                debug(playerUuid, nowMs, "hide-no-target-fallback",
                        "fallback clear after target lost; previousTarget=" + previous.targetKey() + ", item=" + activeItemId);
            }
        }
        stateByPlayer.put(playerUuid, new HudState(
                null,
                previous != null ? previous.lastRefreshMs() : 0L,
                null,
                false,
                nowMs,
                activeItemId,
                previous != null ? previous.lastPresentationMs() : 0L
        ));
    }

    private void rememberScan(@Nonnull UUID playerUuid,
                              @Nullable HudState previous,
                              @Nonnull String activeItemId,
                              long nowMs,
                              long presentationMs) {
        if (previous == null) {
            stateByPlayer.put(playerUuid, new HudState(null, 0L, null, false, nowMs, activeItemId, presentationMs));
            return;
        }
        stateByPlayer.put(playerUuid, new HudState(
                previous.targetKey(),
                previous.lastRefreshMs(),
                previous.hud(),
                previous.visible(),
                nowMs,
                activeItemId,
                presentationMs
        ));
    }

    private void rememberPresentation(@Nonnull UUID playerUuid,
                                      @Nullable HudState previous,
                                      long presentationMs) {
        if (previous == null) {
            return;
        }
        stateByPlayer.put(playerUuid, new HudState(
                previous.targetKey(),
                previous.lastRefreshMs(),
                previous.hud(),
                previous.visible(),
                previous.lastTargetScanMs(),
                previous.activeItemId(),
                presentationMs
        ));
    }

    private long pulsePresentation(@Nonnull UUID playerUuid, @Nullable HudState previous, long nowMs) {
        if (!shouldPulsePresentation(previous, nowMs)) {
            return previous != null ? previous.lastPresentationMs() : 0L;
        }
        previous.hud().present();
        debug(playerUuid, nowMs, "present:" + previous.targetKey(), "presentation pulse; target=" + previous.targetKey());
        return nowMs;
    }

    private void dropInactiveCandidate(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        HudState previous = stateByPlayer.get(playerUuid);
        if (previous != null && previous.hud() != null) {
            previous.hud().hideNow();
            debug(playerUuid, System.currentTimeMillis(), "drop-inactive",
                    "dropped inactive candidate; previousTarget=" + previous.targetKey());
        }
        activationTracker.remove(playerUuid);
        stateByPlayer.remove(playerUuid);
        debugLogStateByPlayer.remove(playerUuid);
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

    private static boolean isVisible(@Nullable HudState state) {
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

    private static boolean shouldRefresh(@Nullable HudState previous,
                                         @Nullable String targetKey,
                                         long nowMs) {
        if (targetKey == null || targetKey.isBlank()) {
            return previous != null && previous.visible();
        }
        if (previous == null || previous.hud() == null || !previous.visible()) {
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

    private static boolean shouldScanTarget(@Nullable HudState previous,
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

    private static boolean shouldPulsePresentation(@Nullable HudState previous, long nowMs) {
        return previous != null
                && shouldPulsePresentationForTests(
                previous.visible(),
                previous.hud() != null,
                previous.lastPresentationMs(),
                nowMs,
                PRESENTATION_PULSE_INTERVAL_MS
        );
    }

    static boolean shouldPulsePresentationForTests(boolean visible,
                                                   boolean hasHud,
                                                   long previousPresentationMs,
                                                   long nowMs,
                                                   long pulseIntervalMs) {
        return visible && hasHud && nowMs - previousPresentationMs >= Math.max(0L, pulseIntervalMs);
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

    static long presentationPulseIntervalMsForTests() {
        return PRESENTATION_PULSE_INTERVAL_MS;
    }

    static float targetDistanceForTests() {
        return TARGET_DISTANCE;
    }

    static long refreshIntervalMsForTests() {
        return REFRESH_INTERVAL_MS;
    }

    static boolean isStaticDisplayCacheValidForTests(long cachedAtMs, long nowMs, long ttlMs) {
        return ttlMs > 0L && nowMs >= cachedAtMs && nowMs - cachedAtMs < ttlMs;
    }

    static List<UUID> selectCandidatesForPassForTests(@Nonnull List<UUID> candidates,
                                                       int maxCandidates,
                                                       int offset) {
        return selectCandidatesForPass(candidates, maxCandidates, offset);
    }

    @Nonnull
    private static List<UUID> selectCandidatesForPass(@Nonnull List<UUID> candidates,
                                                      int maxCandidates,
                                                      int offset) {
        if (candidates.isEmpty() || maxCandidates <= 0) {
            return List.of();
        }
        int size = candidates.size();
        int limit = Math.min(maxCandidates, size);
        int start = Math.floorMod(offset, size);
        ArrayList<UUID> selected = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            selected.add(candidates.get((start + i) % size));
        }
        return List.copyOf(selected);
    }

    private static int nextCandidateOffsetForPass(int offset, int selectedCount, int candidateCount) {
        if (candidateCount <= 0 || selectedCount <= 0) {
            return 0;
        }
        return Math.floorMod(offset + selectedCount, candidateCount);
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

    private record HudState(@Nullable String targetKey,
                            long lastRefreshMs,
                            @Nullable TameworkCommandTargetHud hud,
                            boolean visible,
                            long lastTargetScanMs,
                            @Nullable String activeItemId,
                            long lastPresentationMs) {
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
