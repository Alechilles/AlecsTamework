package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore.Slot;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shows the assigned Q/E/R command glyphs while a command flute is equipped. */
public final class CommandHotswapHudService extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 100L;
    private static final long REFRESH_INTERVAL_MS = 200L;
    private static final int MAX_CANDIDATES_PER_PASS = 16;
    private static final int RESERVED_ACTIVE_CANDIDATES = 1;
    private static final CommandHotswapHudViewModel HIDDEN_MODEL = new CommandHotswapHudViewModel(
            CommandHotswapHudViewModel.Slot.hidden("LMB"),
            CommandHotswapHudViewModel.Slot.hidden("RMB"),
            CommandHotswapHudViewModel.Slot.hidden("Q"),
            CommandHotswapHudViewModel.Slot.hidden("E"),
            CommandHotswapHudViewModel.Slot.hidden("R"),
            CommandHotswapHudViewModel.GroupStatus.hidden()
    );
    private static final CommandHotswapHudViewModel.Slot OPEN_MENU_SLOT =
            new CommandHotswapHudViewModel.Slot(
                    true, "RMB", "Tamework/CommandHotswaps/OpenMenu.png", ""
            );
    private static final CommandHotswapHudViewModel.Slot LINK_SLOT =
            new CommandHotswapHudViewModel.Slot(
                    true, "LMB", "Tamework/CommandHotswaps/Link.png", ""
            );

    private final CommandItemRegistry registry;
    private final CommandHotswapAssignmentStore assignments = new CommandHotswapAssignmentStore();
    private final CommandTargetHudActivationTracker activationTracker;
    private final CommandTargetInspector targetInspector;
    private final CommandHotswapHudPresentationCoordinator presentationCoordinator;
    private final CommandHotswapHudGroupStatusResolver groupStatusResolver =
            new CommandHotswapHudGroupStatusResolver(null, null, null);
    private final Object storesLock = new Object();
    private final Map<Store<EntityStore>, StoreState> statesByStore = new IdentityHashMap<>();

    public CommandHotswapHudService(@Nonnull CommandItemRegistry registry) {
        this(registry, new CommandTargetHudActivationTracker(), new CommandTargetInspector());
    }

    public CommandHotswapHudService(@Nonnull CommandItemRegistry registry,
                                    @Nonnull CommandTargetInspector targetInspector) {
        this(registry, new CommandTargetHudActivationTracker(), targetInspector);
    }

    public CommandHotswapHudService(@Nonnull CommandItemRegistry registry,
                                    @Nonnull CommandTargetHudActivationTracker activationTracker) {
        this(registry, activationTracker, new CommandTargetInspector());
    }

    public CommandHotswapHudService(@Nonnull CommandItemRegistry registry,
                                    @Nonnull CommandTargetHudActivationTracker activationTracker,
                                    @Nonnull CommandTargetInspector targetInspector) {
        this(registry, activationTracker, targetInspector, resolveCommandHudRegistry());
    }

    CommandHotswapHudService(@Nonnull CommandItemRegistry registry,
                             @Nonnull CommandTargetHudActivationTracker activationTracker,
                             @Nonnull CommandTargetInspector targetInspector,
                             @Nullable CommandHudRegistry commandHudRegistry) {
        this.registry = registry;
        this.activationTracker = activationTracker;
        this.targetInspector = targetInspector;
        this.presentationCoordinator = new CommandHotswapHudPresentationCoordinator(
                commandHudRegistry, (store, playerUuid) ->
                        activationTracker.markDirty(store, playerUuid));
        activationTracker.addLifecycleListener(new CommandTargetHudActivationTracker.LifecycleListener() {
            @Override
            public void onPlayerRemoved(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
                clearPlayerState(store, playerUuid);
            }

            @Override
            public void onStoreRemoved(@Nonnull Store<EntityStore> store) {
                clearStoreState(store);
            }
        });
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        StoreTickState tickState = storeTickState(store);
        if (SWEEP_INTERVAL_MS > 0L && nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        processCandidatePlayers(store, nowMs);
    }

    @Nonnull
    private StoreTickState storeTickState(@Nonnull Store<EntityStore> store) {
        return storeState(store).tickState();
    }

    @Nonnull
    private StoreState storeState(@Nonnull Store<EntityStore> store) {
        synchronized (storesLock) {
            return statesByStore.computeIfAbsent(store, ignored -> new StoreState());
        }
    }

    @Nullable
    private StoreState existingStoreState(@Nonnull Store<EntityStore> store) {
        synchronized (storesLock) {
            return statesByStore.get(store);
        }
    }

    private void processCandidatePlayers(@Nonnull Store<EntityStore> store, long nowMs) {
        CommandTargetHudActivationTracker.CandidateBatch batch =
                activationTracker.selectCandidateBatch(
                        store,
                        MAX_CANDIDATES_PER_PASS,
                        nowMs,
                        REFRESH_INTERVAL_MS,
                        RESERVED_ACTIVE_CANDIDATES
                );
        for (UUID playerUuid : batch.playerUuids()) {
            PlayerCandidate candidate = resolvePlayerCandidate(playerUuid, store);
            if (candidate == null) {
                activationTracker.remove(store, playerUuid);
                continue;
            }
            updatePlayer(store, candidate.playerUuid(), candidate.player(), nowMs);
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
        return player != null ? new PlayerCandidate(playerUuid, player) : null;
    }

    private void updatePlayer(@Nonnull Store<EntityStore> store,
                              @Nonnull UUID playerUuid,
                              @Nullable Player player,
                              long nowMs) {
        if (playerUuid == null || !CommandHudClientReadiness.canRender(player)) {
            return;
        }
        ActiveCommandItem activeCommand = resolveActiveCommand(player);
        activationTracker.recordResolvedHand(
                store,
                playerUuid,
                activeCommand != null ? activeCommand.itemId() : null,
                activeCommand != null,
                nowMs
        );
        CommandHotswapHudViewModel model = resolveModel(player, activeCommand, nowMs);
        StoreState storeState = storeState(store);
        HudState previous = storeState.stateForPlayer(playerUuid);
        if (!model.visible()) {
            removeHud(store, playerUuid, player, previous);
            return;
        }
        if (activeCommand == null || player.getPlayerRef() == null
                || player.getHudManager() == null) {
            return;
        }
        CommandHotswapHudPresentation presentation = presentationCoordinator.present(
                store, player, activeCommand.config(), activeCommand.toolIdentity(), model);
        if (presentation == null || presentation.closed()) {
            presentationCoordinator.closePlayer(playerUuid);
            storeState.remove(playerUuid);
            return;
        }
        storeState.put(playerUuid, new HudState(
                player.getPlayerRef(), activeCommand.toolIdentity(), presentation, model));
    }

    @Nonnull
    private CommandHotswapHudViewModel resolveModel(@Nullable Player player,
                                                    @Nullable ActiveCommandItem activeCommand,
                                                    long nowMs) {
        if (player == null || activeCommand == null) {
            return hiddenModel();
        }
        ItemStack stack = activeCommand.stack();
        TwCommandItemConfig config = activeCommand.config();
        return new CommandHotswapHudViewModel(
                resolvePrimarySlot(player, stack, config, nowMs),
                OPEN_MENU_SLOT,
                resolveSlot(stack, config, Slot.Q, "Q"),
                resolveSlot(stack, config, Slot.E, "E"),
                resolveSlot(stack, config, Slot.R, "R"),
                config.usesBondedCompanionRoster()
                        ? CommandHotswapHudViewModel.GroupStatus.hidden()
                        : groupStatusResolver.resolve(player.getUuid(), stack)
        );
    }

    @Nonnull
    private CommandHotswapHudViewModel.Slot resolvePrimarySlot(
            @Nonnull Player player,
            @Nonnull ItemStack stack,
            @Nonnull TwCommandItemConfig config,
            long nowMs) {
        if (targetInspector.isLinkable(player, player.getReference(), config,
                player.getWorld() != null ? player.getWorld().getEntityStore().getStore() : null,
                nowMs)) {
            return LINK_SLOT;
        }
        String selectedId = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
        CommandEntry selected = config.findCommandById(selectedId);
        if (selected == null) {
            selected = config.findDefaultCommand();
        }
        if (selected == null) {
            return CommandHotswapHudViewModel.Slot.hidden("LMB");
        }
        return new CommandHotswapHudViewModel.Slot(
                true,
                "LMB",
                CommandHotswapHudIconResolver.resolve(selected.getIcon(), selected.getId()),
                CommandHotswapHudIconResolver.fallbackGlyph(selected.getId())
        );
    }

    @Nonnull
    private CommandHotswapHudViewModel.Slot resolveSlot(@Nonnull ItemStack stack,
                                                        @Nonnull TwCommandItemConfig config,
                                                        @Nonnull Slot slot,
                                                        @Nonnull String bindingLabel) {
        String commandId = assignments.read(stack, slot);
        if (CommandHotswapAction.isCycleGroup(commandId)
                && !config.usesBondedCompanionRoster()) {
            return new CommandHotswapHudViewModel.Slot(
                    true,
                    bindingLabel,
                    CommandHotswapHudIconResolver.resolve(null, commandId),
                    ""
            );
        }
        CommandEntry command = config.findCommandById(commandId);
        if (command == null) {
            return CommandHotswapHudViewModel.Slot.hidden(bindingLabel);
        }
        String icon = CommandHotswapHudIconResolver.resolve(command.getIcon(), command.getId());
        return new CommandHotswapHudViewModel.Slot(
                true,
                bindingLabel,
                icon,
                CommandHotswapHudIconResolver.fallbackGlyph(command.getId())
        );
    }

    @Nonnull
    private static CommandHotswapHudViewModel hiddenModel() {
        return HIDDEN_MODEL;
    }

    private void removeHud(@Nonnull Store<EntityStore> store,
                            @Nonnull UUID playerUuid,
                            @Nullable Player player,
                            @Nullable HudState previous) {
        if (previous == null) {
            presentationCoordinator.hide(playerUuid, player != null ? player.getHudManager() : null);
            return;
        }
        if (player != null && player.getPlayerRef() != null && player.getHudManager() != null) {
            presentationCoordinator.hide(player);
        } else {
            presentationCoordinator.closePlayer(playerUuid);
        }
        StoreState storeState = existingStoreState(store);
        if (storeState != null) {
            storeState.remove(playerUuid);
        }
    }

    @Nullable
    private ActiveCommandItem resolveActiveCommand(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        ItemStack stack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (stack == null || stack.isEmpty() || stack.getItemId() == null || registry == null) {
            return null;
        }
        TwCommandItemConfig config = registry.get(stack.getItemId());
        if (config == null || !config.isEnabled()) {
            return null;
        }
        return new ActiveCommandItem(stack, stack.getItemId(), config,
                CommandHotswapHudToolIdentity.from(player, stack));
    }

    private void clearPlayerState(@Nonnull Store<EntityStore> store,
                                  @Nonnull UUID playerUuid) {
        StoreState storeState = existingStoreState(store);
        if (storeState == null) {
            presentationCoordinator.closePlayer(playerUuid);
            return;
        }
        storeState.remove(playerUuid);
        presentationCoordinator.closePlayer(playerUuid);
    }

    private void clearStoreState(@Nonnull Store<EntityStore> store) {
        StoreState storeState;
        synchronized (storesLock) {
            storeState = statesByStore.remove(store);
        }
        presentationCoordinator.closeStore(store);
        if (storeState != null) storeState.clear();
    }

    /** Serializes player HUD state within one store without locking unrelated stores. */
    private static final class StoreState {
        private final Map<UUID, HudState> statesByPlayer = new HashMap<>();
        private final StoreTickState tickState = new StoreTickState();

        @Nonnull
        private synchronized StoreTickState tickState() {
            return tickState;
        }

        @Nullable
        private synchronized HudState stateForPlayer(@Nonnull UUID playerUuid) {
            return statesByPlayer.get(playerUuid);
        }

        private synchronized void put(@Nonnull UUID playerUuid, @Nonnull HudState state) {
            statesByPlayer.put(playerUuid, state);
        }

        @Nullable
        private synchronized HudState remove(@Nonnull UUID playerUuid) {
            return statesByPlayer.remove(playerUuid);
        }

        private synchronized void clear() {
            statesByPlayer.clear();
        }
    }

    /** Keeps scheduler deadlines separate for each entity store. */
    private static final class StoreTickState {
        private volatile long nextSweepAtMs;
    }

    /** Carries a stable player identity with the live component resolved for this tick. */
    private record PlayerCandidate(@Nonnull UUID playerUuid,
                                   @Nonnull Player player) {
    }

    /** Holds one resolved active command stack so activation and HUD rendering share the lookup. */
    private record ActiveCommandItem(@Nonnull ItemStack stack,
                                     @Nonnull String itemId,
                                     @Nonnull TwCommandItemConfig config,
                                     @Nonnull CommandHotswapHudToolIdentity toolIdentity) {
    }

    private record HudState(@Nonnull PlayerRef playerRef,
                            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
                            @Nonnull CommandHotswapHudPresentation presentation,
                            @Nonnull CommandHotswapHudViewModel model) {
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
}
