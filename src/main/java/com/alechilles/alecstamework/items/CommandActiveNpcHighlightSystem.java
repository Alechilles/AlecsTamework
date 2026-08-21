package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;

/**
 * Reconciles controller-only indicators for the equipped command tool.
 * Inventory events queue candidate players, while an 800 ms pass covers missed NPC load and
 * alias changes. Work runs at 100 ms only while candidates exist and processes at most four
 * players and 82 NPCs per player sweep.
 */
public final class CommandActiveNpcHighlightSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 100L;
    private static final long RECONCILE_INTERVAL_MS = 800L;
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
    private final CommandActiveNpcHighlightProxyService proxyService =
            new CommandActiveNpcHighlightProxyService();
    private final CommandActiveNpcHighlightDisplayTracker<
            CommandActiveNpcHighlightPlanService.HighlightTarget> displayTracker =
            new CommandActiveNpcHighlightDisplayTracker<>();
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
                scheduleProxyRemoval(store, displayTracker.remove(store, playerUuid));
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
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return;
        }
        ActiveTool activeTool = resolveActiveTool(playerCandidate.player());
        if (activeTool == null) {
            batchService.remove(store, playerUuid);
            scheduleProxyRemoval(store, displayTracker.remove(store, playerUuid));
            activationTracker.recordResolvedHand(store, playerUuid, null, false, nowMs);
            return;
        }
        activationTracker.recordResolvedHand(store, playerUuid, activeTool.itemId(), true, nowMs);
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
                    scheduleProxyRemoval(store, displayTracker.reconcile(
                            store, playerUuid, activeTool.toolId(), desiredTargets
                    ));
                    return desiredTargets;
                }
        );
        ArrayList<CommandActiveNpcHighlightProxyService.SyncTarget> syncTargets =
                new ArrayList<>(targets.size());
        for (CommandActiveNpcHighlightPlanService.HighlightTarget target : targets) {
            emitForLoadedTarget(
                    store, world, loadedTargetProbe, playerCandidate.ref(), playerUuid,
                    activeTool.toolId(), target, syncTargets
            );
        }
        scheduleProxySync(store, syncTargets);
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
            @Nonnull List<CommandActiveNpcHighlightProxyService.SyncTarget> syncTargets
    ) {
        UUID resolvedNpcUuid = targetResolver.resolve(
                target.npcUuid(), target.profileId(), loadedTargetProbe
        );
        Ref<EntityStore> npcRef = resolvedNpcUuid != null
                ? world.getEntityRef(resolvedNpcUuid)
                : null;
        if (npcRef == null || !npcRef.isValid()
                || !CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store)) {
            scheduleProxyRemoval(store, displayTracker.forgetTarget(store, playerUuid, target));
            return;
        }
        NPCEntity npc = component(store, npcRef, NPCEntity.getComponentType());
        TameworkCommandLinksComponent links = component(
                store, npcRef, TameworkCommandLinksComponent.getComponentType()
        );
        ModelComponent model = component(store, npcRef, ModelComponent.getComponentType());
        if (npc == null || links == null
                || !playerUuid.equals(links.getOwnerId())
                || !links.containsToolId(toolId)) {
            scheduleProxyRemoval(store, displayTracker.forgetTarget(store, playerUuid, target));
            return;
        }
        if (isNpcInMountLifecycle(store, npcRef)) {
            scheduleProxyRemoval(store, displayTracker.forgetTarget(
                    store, playerUuid, target
            ));
            return;
        }
        scheduleProxyRemoval(store, displayTracker.forgetProxyForDifferentParent(
                store, playerUuid, target, resolvedNpcUuid
        ));
        UUID proxyUuid = displayTracker.proxyUuid(
                store, playerUuid, target, resolvedNpcUuid
        );
        if (proxyUuid == null) {
            requestProxyCreation(
                    store, world, playerUuid, toolId, target, resolvedNpcUuid,
                    anchorResolver.resolveHeadOffset(model)
            );
            return;
        }
        Ref<EntityStore> proxyRef = world.getEntityRef(proxyUuid);
        if (proxyRef != null && proxyRef.isValid()
                && proxyService.requiresRecreation(store, npcRef, proxyRef)) {
            scheduleProxyRemoval(store, displayTracker.forgetTarget(store, playerUuid, target));
            requestProxyCreation(
                    store, world, playerUuid, toolId, target, resolvedNpcUuid,
                    anchorResolver.resolveHeadOffset(model)
            );
            return;
        }
        NetworkId networkId = proxyRef != null && proxyRef.isValid()
                ? component(store, proxyRef, NetworkId.getComponentType())
                : null;
        if (networkId == null) {
            scheduleProxyRemoval(store, displayTracker.forgetTarget(store, playerUuid, target));
            return;
        }
        syncTargets.add(new CommandActiveNpcHighlightProxyService.SyncTarget(
                proxyUuid, resolvedNpcUuid
        ));
        int resolvedNetworkId = networkId.getId();
        if (!displayTracker.needsEmission(
                store, playerUuid, target, resolvedNetworkId
        )) {
            return;
        }
        boolean emitted = emitter.emit(
                networkId,
                viewerRef,
                target.colorHex(),
                new CommandActiveNpcHighlightAnchor(null, new Vector3f()),
                store
        );
        if (emitted) {
            displayTracker.recordEmission(
                    store, playerUuid, target, resolvedNetworkId
            );
        }
    }

    private void requestProxyCreation(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID playerUuid,
            @Nonnull String toolId,
            @Nonnull CommandActiveNpcHighlightPlanService.HighlightTarget target,
            @Nonnull UUID npcUuid,
            @Nonnull Vector3f attachmentOffset
    ) {
        if (!displayTracker.beginProxyCreation(
                store, playerUuid, target, npcUuid
        )) {
            return;
        }
        try {
            world.execute(() -> createProxyOnWorld(
                    store, world, playerUuid, toolId, target, npcUuid, attachmentOffset
            ));
        } catch (RuntimeException failure) {
            displayTracker.cancelProxyCreation(store, playerUuid, target, npcUuid);
        }
    }

    private void createProxyOnWorld(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull UUID playerUuid,
            @Nonnull String toolId,
            @Nonnull CommandActiveNpcHighlightPlanService.HighlightTarget target,
            @Nonnull UUID npcUuid,
            @Nonnull Vector3f attachmentOffset
    ) {
        UUID proxyUuid = null;
        boolean proxyRetained = false;
        try {
            PlayerCandidate playerCandidate = resolvePlayer(store, playerUuid);
            ActiveTool activeTool = playerCandidate != null
                    ? resolveActiveTool(playerCandidate.player())
                    : null;
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (activeTool == null || !toolId.equals(activeTool.toolId())
                    || npcRef == null || !npcRef.isValid()
                    || !allowsProxyFor(store, npcRef, playerUuid, toolId)) {
                return;
            }
            proxyUuid = proxyService.create(store, npcRef, attachmentOffset);
            if (proxyUuid != null && displayTracker.recordProxy(
                    store, playerUuid, target, npcUuid, proxyUuid
            )) {
                proxyRetained = true;
                return;
            }
        } catch (RuntimeException ignored) {
            // A later reconciliation can retry after a transient entity or asset failure.
        } finally {
            displayTracker.cancelProxyCreation(store, playerUuid, target, npcUuid);
            if (proxyUuid != null && !proxyRetained) {
                proxyService.removeAll(store, world, List.of(proxyUuid));
            }
        }
    }

    private boolean allowsProxyFor(@Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> npcRef,
                                   @Nonnull UUID playerUuid,
                                   @Nonnull String toolId) {
        TameworkCommandLinksComponent links = component(
                store, npcRef, TameworkCommandLinksComponent.getComponentType()
        );
        return CommandGenericTargetAuthority.allowsGenericTargetMutation(npcRef, store)
                && component(store, npcRef, NPCEntity.getComponentType()) != null
                && !isNpcInMountLifecycle(store, npcRef)
                && links != null
                && playerUuid.equals(links.getOwnerId())
                && links.containsToolId(toolId);
    }

    private boolean isNpcInMountLifecycle(@Nonnull Store<EntityStore> store,
                                          @Nonnull Ref<EntityStore> npcRef) {
        return component(store, npcRef, NPCMountComponent.getComponentType()) != null
                || component(store, npcRef, MountedComponent.getComponentType()) != null
                || component(store, npcRef, TameworkRideMountComponent.getComponentType()) != null;
    }

    private void scheduleProxyRemoval(@Nonnull Store<EntityStore> store,
                                      @Nonnull List<UUID> proxyUuids) {
        if (proxyUuids.isEmpty() || store.getExternalData() == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        try {
            world.execute(() -> proxyService.removeAll(store, world, proxyUuids));
        } catch (RuntimeException ignored) {
            // The world is already closing, so its non-persistent proxies will be discarded.
        }
    }

    private void scheduleProxySync(
            @Nonnull Store<EntityStore> store,
            @Nonnull List<CommandActiveNpcHighlightProxyService.SyncTarget> targets) {
        if (targets.isEmpty() || store.getExternalData() == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        List<CommandActiveNpcHighlightProxyService.SyncTarget> snapshot = List.copyOf(targets);
        try {
            world.execute(() -> proxyService.syncAll(store, world, snapshot));
        } catch (RuntimeException ignored) {
            // The next bounded pass will retry unless the world is closing.
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
