package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore.Slot;
import com.alechilles.alecstamework.ui.TameworkCommandHotswapHud;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.query.Query;
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
    private static final long FALLBACK_DISCOVERY_INTERVAL_MS = 1_500L;
    private static final int MAX_CANDIDATES_PER_PASS = 16;
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
    private final CommandHotswapHudGroupStatusResolver groupStatusResolver =
            new CommandHotswapHudGroupStatusResolver(null, null, null);
    private final Map<UUID, HudState> statesByPlayer = new HashMap<>();
    private final Map<Store<EntityStore>, StoreTickState> storeTickStateByStore = new IdentityHashMap<>();

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
        this.registry = registry;
        this.activationTracker = activationTracker;
        this.targetInspector = targetInspector;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        StoreTickState tickState = storeTickState(store);
        if (SWEEP_INTERVAL_MS > 0L && nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        if (nowMs >= tickState.nextFallbackDiscoveryAtMs) {
            tickState.nextFallbackDiscoveryAtMs = nowMs + FALLBACK_DISCOVERY_INTERVAL_MS;
            seedCandidatesFromPlayerSweep(store);
        }
        processCandidatePlayers(store, nowMs);
    }

    @Nonnull
    private StoreTickState storeTickState(@Nonnull Store<EntityStore> store) {
        return storeTickStateByStore.computeIfAbsent(store, ignored -> new StoreTickState());
    }

    private void processCandidatePlayers(@Nonnull Store<EntityStore> store, long nowMs) {
        CommandTargetHudActivationTracker.CandidateBatch batch =
                activationTracker.selectCandidateBatch(MAX_CANDIDATES_PER_PASS);
        for (UUID playerUuid : batch.playerUuids()) {
            PlayerCandidate candidate = resolvePlayerCandidate(playerUuid, store);
            if (candidate == null) {
                continue;
            }
            updatePlayer(candidate.playerUuid(), candidate.player(), nowMs);
        }
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
        for (int index = 0; index < chunk.size(); index++) {
            Player player = chunk.getComponent(index, playerType);
            UUID playerUuid = player != null ? player.getUuid() : null;
            if (playerUuid != null) {
                activationTracker.markDirty(playerUuid);
            }
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

    private void updatePlayer(@Nonnull UUID playerUuid,
                              @Nullable Player player,
                              long nowMs) {
        if (playerUuid == null) {
            return;
        }
        String activeItemId = resolveActiveCommandItemId(player);
        activationTracker.recordResolvedHand(playerUuid, activeItemId, activeItemId != null, nowMs);
        CommandHotswapHudViewModel model = resolveModel(player, nowMs);
        HudState previous = statesByPlayer.get(playerUuid);
        if (!model.visible()) {
            removeHud(playerUuid, player, previous);
            return;
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || player.getHudManager() == null) {
            return;
        }
        if (previous == null || previous.playerRef() != playerRef) {
            TameworkCommandHotswapHud hud = new TameworkCommandHotswapHud(playerRef, model);
            player.getHudManager().addCustomHud(playerRef, hud);
            statesByPlayer.put(playerUuid, new HudState(playerRef, hud, model));
            return;
        }
        if (!previous.model().equals(model)) {
            previous.hud().refresh(model);
            statesByPlayer.put(playerUuid, new HudState(playerRef, previous.hud(), model));
        }
    }

    @Nonnull
    private CommandHotswapHudViewModel resolveModel(@Nullable Player player, long nowMs) {
        ItemStack stack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (stack == null || stack.isEmpty() || stack.getItemId() == null || registry == null) {
            return hiddenModel();
        }
        TwCommandItemConfig config = registry.get(stack.getItemId());
        if (config == null || !config.isEnabled()) {
            return hiddenModel();
        }
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

    private void removeHud(@Nonnull UUID playerUuid,
                           @Nullable Player player,
                           @Nullable HudState previous) {
        if (previous == null) {
            return;
        }
        if (player != null && player.getPlayerRef() != null && player.getHudManager() != null) {
            player.getHudManager().removeCustomHud(
                    player.getPlayerRef(), TameworkCommandHotswapHud.HUD_KEY
            );
        } else {
            previous.hud().hideNow();
        }
        statesByPlayer.remove(playerUuid);
    }

    @Nullable
    private String resolveActiveCommandItemId(@Nullable Player player) {
        ItemStack stack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (stack == null || stack.isEmpty() || stack.getItemId() == null || registry == null) {
            return null;
        }
        TwCommandItemConfig config = registry.get(stack.getItemId());
        return config != null && config.isEnabled() ? stack.getItemId() : null;
    }

    /** Keeps scheduler deadlines separate for each entity store. */
    private static final class StoreTickState {
        private long nextSweepAtMs;
        private long nextFallbackDiscoveryAtMs;
    }

    /** Carries a stable player identity with the live component resolved for this tick. */
    private record PlayerCandidate(@Nonnull UUID playerUuid,
                                   @Nonnull Player player) {
    }

    private record HudState(@Nonnull PlayerRef playerRef,
                            @Nonnull TameworkCommandHotswapHud hud,
                            @Nonnull CommandHotswapHudViewModel model) {
    }
}
