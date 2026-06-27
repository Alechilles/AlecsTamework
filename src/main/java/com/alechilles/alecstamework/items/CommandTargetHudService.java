package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.actions.TameworkTameFoodDisplayResolver;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.TameworkCommandTargetHud;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shows a compact right-side status HUD while a player points a command item at a supported NPC.
 */
public final class CommandTargetHudService extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 0L;
    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final float TARGET_DISTANCE = 6.0f;
    private static final String TRANQUILIZER_REQUIREMENT_ID = "TameworkEffectActive";

    private final CommandItemRegistry registry;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLoadedNpcStatusSnapshotService loadedSnapshotService;
    private final CommandTargetHudFoodResolver foodResolver;
    private final TameworkTameFoodDisplayResolver tameFoodDisplayResolver;
    private final CommandTargetHudAttachmentResolver attachmentResolver;
    private final CommandTargetHudTameRequirementResolver tameRequirementResolver;
    private final Map<UUID, HudState> stateByPlayer = new HashMap<>();
    private long nextSweepAtMs;

    public CommandTargetHudService(CommandItemRegistry registry) {
        this.registry = registry;
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

        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        if (playerType == null) {
            return;
        }

        HashSet<UUID> activePlayers = new HashSet<>();
        store.forEachChunk(
                Query.and(playerType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        updateChunk(chunk, playerType, store, activePlayers, nowMs)
        );
        clearInactivePlayers(activePlayers);
    }

    private void updateChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                             @Nonnull ComponentType<EntityStore, Player> playerType,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull HashSet<UUID> activePlayers,
                             long nowMs) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Player player = chunk.getComponent(i, playerType);
            UUID playerUuid = player != null ? player.getUuid() : null;
            if (playerUuid == null) {
                continue;
            }
            activePlayers.add(playerUuid);
            Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
            updatePlayer(playerUuid, player, playerRef, store, nowMs);
        }
    }

    private void updatePlayer(@Nonnull UUID playerUuid,
                              @Nullable Player player,
                              @Nullable Ref<EntityStore> playerRef,
                              @Nonnull Store<EntityStore> store,
                              long nowMs) {
        TargetSnapshot target = resolveTarget(player, playerRef, store);
        String targetKey = target != null ? target.key() : null;
        HudState previous = stateByPlayer.get(playerUuid);
        if (!shouldRefresh(previous, targetKey, nowMs)) {
            return;
        }
        if (target == null) {
            hideHud(playerUuid, player, nowMs);
            return;
        }
        showHud(playerUuid, player, target.model(), targetKey, nowMs);
    }

    @Nullable
    private TargetSnapshot resolveTarget(@Nullable Player player,
                                         @Nullable Ref<EntityStore> playerRef,
                                         @Nonnull Store<EntityStore> store) {
        if (player == null || playerRef == null || !playerRef.isValid()) {
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

        Ref<EntityStore> npcRef = TargetUtil.getTargetEntity(playerRef, TARGET_DISTANCE, store);
        if (npcRef == null || !npcRef.isValid() || npcRef.equals(playerRef)) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null || !isSupportedTarget(npcRef, npc, player, config, store)) {
            return null;
        }
        CommandTargetHudViewModel model = buildModel(player, npcRef, npc, store);
        if (model == null) {
            return null;
        }
        return new TargetSnapshot(buildTargetKey(npc.getUuid(), activeStack.getItemId()), model);
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
                                                 @Nonnull Store<EntityStore> store) {
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
                )
        );
        if (status == null) {
            return null;
        }
        return new CommandTargetHudViewModel(
                status,
                foodResolver.resolveFavoriteFood(
                        player,
                        tameFoodDisplayResolver.resolveTamingFoodItemIds(roleId, npc.getRole())
                ),
                attachmentResolver.resolveRows(roleId, resolveModelAssetId(npcRef, store), resolveAttachmentIds(npcRef, store)),
                resolveTameRequirement(npcRef, roleId, store)
        );
    }

    @Nullable
    private Map<String, String> resolveAttachmentIds(@Nonnull Ref<EntityStore> npcRef,
                                                     @Nonnull Store<EntityStore> store) {
        TameworkAttachmentsComponent attachments = store.getComponent(npcRef, TameworkAttachmentsComponent.getComponentType());
        return attachments != null ? attachments.getAttachmentIds() : null;
    }

    @Nullable
    private String resolveModelAssetId(@Nonnull Ref<EntityStore> npcRef,
                                       @Nonnull Store<EntityStore> store) {
        ModelComponent modelComponent = store.getComponent(npcRef, ModelComponent.getComponentType());
        Model model = modelComponent != null ? modelComponent.getModel() : null;
        return model != null ? model.getModelAssetId() : null;
    }

    @Nullable
    private CommandTargetHudViewModel.TameRequirementRow resolveTameRequirement(@Nonnull Ref<EntityStore> npcRef,
                                                                                @Nullable String roleId,
                                                                                @Nonnull Store<EntityStore> store) {
        double requiredSeconds = resolveRequiredTranquilizerSeconds(roleId);
        if (requiredSeconds <= 0.0) {
            return null;
        }
        String currentStacksText = resolveCurrentTranquilizerStacksText(npcRef, store);
        return tameRequirementResolver.fromRequiredRemainingSeconds(requiredSeconds, currentStacksText);
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
            hud.show();
        } else {
            hud.refresh(model, language);
        }
        stateByPlayer.put(playerUuid, new HudState(targetKey, nowMs, hud, true));
    }

    private void hideHud(@Nonnull UUID playerUuid, @Nullable Player player, long nowMs) {
        HudState previous = stateByPlayer.get(playerUuid);
        if (previous == null || previous.hud() == null) {
            return;
        }
        if (player == null || player.getPlayerRef() == null || player.getHudManager() == null) {
            stateByPlayer.remove(playerUuid);
            return;
        }
        if (previous.visible()) {
            previous.hud().hideNow();
        }
        stateByPlayer.put(playerUuid, new HudState(null, nowMs, previous.hud(), false));
    }

    private void clearInactivePlayers(@Nonnull HashSet<UUID> activePlayers) {
        if (stateByPlayer.isEmpty()) {
            return;
        }
        for (UUID playerUuid : new HashSet<>(stateByPlayer.keySet())) {
            if (playerUuid == null || activePlayers.contains(playerUuid)) {
                continue;
            }
            stateByPlayer.remove(playerUuid);
        }
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

    static boolean shouldShowForEligibility(boolean tamed, boolean commandEligible, boolean untamedTameable) {
        return tamed ? commandEligible : commandEligible || untamedTameable;
    }

    static long sweepIntervalMsForTests() {
        return SWEEP_INTERVAL_MS;
    }

    static double resolveRequiredTranquilizerSecondsForTests(@Nullable String requirementId,
                                                            @Nullable String jsonPayload) {
        return resolveRequiredTranquilizerSeconds(requirementId, jsonPayload);
    }

    private static double resolveRequiredTranquilizerSeconds(@Nullable String roleId) {
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return 0.0;
        }
        double requiredSeconds = 0.0;
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (!(entry instanceof TwInteractionConfig.TameInteraction) || !entry.isEnabled()) {
                continue;
            }
            requiredSeconds = Math.max(requiredSeconds, resolveRequiredTranquilizerSeconds(entry.getRequires()));
        }
        return requiredSeconds;
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

    private static double resolveRequiredTranquilizerSeconds(@Nullable TwInteractionConfig.RequirementGroup group) {
        if (group == null) {
            return 0.0;
        }
        return Math.max(
                resolveRequiredTranquilizerSeconds(group.getAll()),
                resolveRequiredTranquilizerSeconds(group.getAny())
        );
    }

    private static double resolveRequiredTranquilizerSeconds(@Nullable TwInteractionConfig.RequirementBucket bucket) {
        if (bucket == null) {
            return 0.0;
        }
        double requiredSeconds = 0.0;
        for (TwInteractionConfig.CustomRequirement custom : bucket.getCustom()) {
            if (custom == null) {
                continue;
            }
            requiredSeconds = Math.max(
                    requiredSeconds,
                    resolveRequiredTranquilizerSeconds(custom.getId(), custom.getJsonPayload())
            );
        }
        return requiredSeconds;
    }

    private static double resolveRequiredTranquilizerSeconds(@Nullable String requirementId,
                                                            @Nullable String jsonPayload) {
        if (!TRANQUILIZER_REQUIREMENT_ID.equalsIgnoreCase(safeTrim(requirementId))) {
            return 0.0;
        }
        JsonObject payload = parsePayload(jsonPayload);
        if (payload == null || !matchesTranquilizerEffect(payload)) {
            return 0.0;
        }
        JsonElement value = payload.get("MinRemainingSeconds");
        if (value == null || !value.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            double seconds = value.getAsDouble();
            return Double.isFinite(seconds) && seconds > 0.0 ? seconds : 0.0;
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    @Nullable
    private static JsonObject parsePayload(@Nullable String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(jsonPayload);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matchesTranquilizerEffect(@Nonnull JsonObject payload) {
        JsonElement effectId = payload.get("EffectId");
        return effectId != null
                && effectId.isJsonPrimitive()
                && CommandTargetHudTameRequirementResolver.TRANQUILIZER_EFFECT_ID.equalsIgnoreCase(safeTrim(effectId.getAsString()));
    }

    @Nullable
    private static String safeTrim(@Nullable String value) {
        return value == null ? null : value.trim();
    }

    private record HudState(@Nullable String targetKey,
                            long lastRefreshMs,
                            @Nullable TameworkCommandTargetHud hud,
                            boolean visible) {
    }

    private record TargetSnapshot(@Nonnull String key, @Nonnull CommandTargetHudViewModel model) {
    }
}
