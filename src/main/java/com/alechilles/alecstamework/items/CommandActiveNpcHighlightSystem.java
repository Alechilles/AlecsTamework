package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reconciles renewable, controller-only indicators for the equipped command tool.
 * Work runs at 100 ms only for tracked command users and is capped at 82 NPCs per player sweep.
 */
public final class CommandActiveNpcHighlightSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 100L;
    private static final long RECONCILE_INTERVAL_MS = 800L;
    private static final long HIGHLIGHT_RENEWAL_INTERVAL_MS = 2_400L;
    static final int MAX_TARGETS_PER_PLAYER_SWEEP = 82;
    private static final int MAX_CANDIDATES_PER_PASS = 4;
    private static final int RESERVED_ACTIVE_CANDIDATES = 1;

    private final CommandItemRegistry registry;
    private final CommandTargetHudActivationTracker activationTracker;
    private final CommandPanelPreferenceService preferenceService = new CommandPanelPreferenceService();
    private final CommandLinkedNpcRecordStore recordStore = new CommandLinkedNpcRecordStore();
    private final CommandGroupService groupService = new CommandGroupService();
    private final CommandActiveNpcHighlightPlanService planService = new CommandActiveNpcHighlightPlanService();
    private final CommandActiveNpcHighlightAnchorResolver anchorResolver =
            new CommandActiveNpcHighlightAnchorResolver();
    private final CommandActiveNpcHighlightEmitter emitter = new CommandActiveNpcHighlightEmitter();
    private final CommandActiveNpcHighlightDisplayTracker<
            CommandActiveNpcHighlightPlanService.HighlightTarget> displayTracker =
            new CommandActiveNpcHighlightDisplayTracker<>(HIGHLIGHT_RENEWAL_INTERVAL_MS);
    private final CommandActiveNpcHighlightBatchService<
            CommandActiveNpcHighlightPlanService.HighlightTarget> batchService =
            new CommandActiveNpcHighlightBatchService<>(MAX_TARGETS_PER_PLAYER_SWEEP);
    private final CommandActiveNpcHighlightTargetResolver targetResolver;
    private final Object storesLock = new Object();
    private final Map<Store<EntityStore>, Long> nextSweepByStore = new IdentityHashMap<>();

    public CommandActiveNpcHighlightSystem(@Nonnull CommandItemRegistry registry,
                                           @Nonnull CommandTargetHudActivationTracker activationTracker,
                                           @Nonnull LoadedNpcIdentityIndex loadedNpcIdentities) {
        this.registry = registry;
        this.activationTracker = activationTracker;
        this.targetResolver = new CommandActiveNpcHighlightTargetResolver(loadedNpcIdentities);
        activationTracker.addLifecycleListener(new CommandTargetHudActivationTracker.LifecycleListener() {
            @Override
            public void onPlayerRemoved(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
                batchService.remove(store, playerUuid);
                displayTracker.remove(store, playerUuid);
            }

            @Override
            public void onStoreRemoved(@Nonnull Store<EntityStore> store) {
                batchService.clear(store);
                displayTracker.clear(store);
                clearStore(store);
            }
        });
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        if (!beginSweep(store, nowMs)) {
            return;
        }
        CommandTargetHudActivationTracker.CandidateBatch batch = activationTracker.selectCandidateBatch(
                store,
                MAX_CANDIDATES_PER_PASS,
                nowMs,
                SWEEP_INTERVAL_MS,
                RESERVED_ACTIVE_CANDIDATES
        );
        for (UUID playerUuid : batch.playerUuids()) {
            refreshPlayer(store, playerUuid, nowMs);
        }
    }

    private boolean beginSweep(@Nonnull Store<EntityStore> store, long nowMs) {
        synchronized (storesLock) {
            Long nextSweep = nextSweepByStore.get(store);
            if (nextSweep != null && nowMs < nextSweep) {
                return false;
            }
            nextSweepByStore.put(store, nowMs + SWEEP_INTERVAL_MS);
            return true;
        }
    }

    private void clearStore(@Nonnull Store<EntityStore> store) {
        synchronized (storesLock) {
            nextSweepByStore.remove(store);
        }
    }

    private void refreshPlayer(@Nonnull Store<EntityStore> store,
                               @Nonnull UUID playerUuid,
                               long nowMs) {
        PlayerCandidate playerCandidate = resolvePlayer(store, playerUuid);
        if (playerCandidate == null) {
            activationTracker.remove(store, playerUuid);
            return;
        }
        ActiveTool activeTool = resolveActiveTool(playerCandidate.player());
        if (activeTool == null) {
            batchService.remove(store, playerUuid);
            displayTracker.remove(store, playerUuid);
            activationTracker.recordResolvedHand(store, playerUuid, null, false, nowMs);
            return;
        }
        activationTracker.recordResolvedHand(store, playerUuid, activeTool.itemId(), true, nowMs);
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return;
        }
        CommandActiveNpcHighlightTargetResolver.LoadedTargetProbe loadedTargetProbe = npcUuid -> {
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            return npcRef != null && npcRef.isValid();
        };
        List<CommandActiveNpcHighlightPlanService.HighlightTarget> targets = batchService.select(
                store,
                playerUuid,
                activeTool.toolId(),
                nowMs,
                RECONCILE_INTERVAL_MS,
                () -> {
                    List<CommandActiveNpcHighlightPlanService.HighlightTarget> desiredTargets =
                            resolveTargets(activeTool.stack());
                    displayTracker.reconcile(
                            store, playerUuid, activeTool.toolId(), desiredTargets
                    );
                    return desiredTargets;
                }
        );
        for (CommandActiveNpcHighlightPlanService.HighlightTarget target : targets) {
            emitForLoadedTarget(
                    store, world, loadedTargetProbe, playerCandidate.ref(), playerUuid,
                    activeTool.toolId(), target, nowMs
            );
        }
    }

    @Nullable
    private PlayerCandidate resolvePlayer(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
        if (store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        if (playerRef == null || !playerRef.isValid() || playerType == null) {
            return null;
        }
        Player player = store.getComponent(playerRef, playerType);
        return player != null ? new PlayerCandidate(player, playerRef) : null;
    }

    @Nullable
    private ActiveTool resolveActiveTool(@Nonnull Player player) {
        ItemStack stack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (stack == null || stack.isEmpty() || stack.getItemId() == null) {
            return null;
        }
        TwCommandItemConfig config = registry.get(stack.getItemId());
        if (config == null
                || !config.isEnabled()
                || config.getRosterStorage() != TwCommandItemConfig.RosterStorage.ItemMetadata
                || !preferenceService.resolveActiveHighlightEnabled(stack)) {
            return null;
        }
        String toolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        return new ActiveTool(stack.getItemId(), toolId, stack);
    }

    @Nonnull
    private List<CommandActiveNpcHighlightPlanService.HighlightTarget> resolveTargets(
            @Nonnull ItemStack stack) {
        return planService.build(recordStore.read(stack), groupService.readGroups(stack));
    }

    private void emitForLoadedTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull CommandActiveNpcHighlightTargetResolver.LoadedTargetProbe loadedTargetProbe,
            @Nonnull Ref<EntityStore> viewerRef,
            @Nonnull UUID playerUuid,
            @Nonnull String toolId,
            @Nonnull CommandActiveNpcHighlightPlanService.HighlightTarget target,
            long nowMs
    ) {
        UUID resolvedNpcUuid = targetResolver.resolve(
                target.npcUuid(), target.profileId(), loadedTargetProbe
        );
        Ref<EntityStore> npcRef = resolvedNpcUuid != null
                ? world.getEntityRef(resolvedNpcUuid)
                : null;
        if (npcRef == null || !npcRef.isValid()
                || !CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store)) {
            displayTracker.forgetTarget(store, playerUuid, target);
            return;
        }
        NPCEntity npc = component(store, npcRef, NPCEntity.getComponentType());
        TameworkCommandLinksComponent links = component(
                store, npcRef, TameworkCommandLinksComponent.getComponentType()
        );
        NetworkId networkId = component(store, npcRef, NetworkId.getComponentType());
        ModelComponent model = component(store, npcRef, ModelComponent.getComponentType());
        if (npc == null || links == null || networkId == null
                || !playerUuid.equals(links.getOwnerId())
                || !links.containsToolId(toolId)) {
            return;
        }
        int resolvedNetworkId = networkId.getId();
        if (!displayTracker.needsEmission(
                store, playerUuid, target, resolvedNetworkId, nowMs
        )) {
            return;
        }
        boolean emitted = emitter.emit(
                networkId,
                viewerRef,
                target.colorHex(),
                anchorResolver.resolve(model),
                store
        );
        if (emitted) {
            displayTracker.recordEmission(
                    store, playerUuid, target, resolvedNetworkId, nowMs
            );
        }
    }

    @Nullable
    private static <T extends Component<EntityStore>> T component(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nullable ComponentType<EntityStore, T> type
    ) {
        return type != null ? store.getComponent(ref, type) : null;
    }

    private record PlayerCandidate(Player player, Ref<EntityStore> ref) {
    }

    private record ActiveTool(
            String itemId,
            String toolId,
            ItemStack stack
    ) {
    }
}
