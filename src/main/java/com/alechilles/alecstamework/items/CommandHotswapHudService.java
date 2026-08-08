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
    private static final long REFRESH_INTERVAL_MS = 200L;

    private final CommandItemRegistry registry;
    private final CommandHotswapAssignmentStore assignments = new CommandHotswapAssignmentStore();
    private final CommandTargetInspector targetInspector = new CommandTargetInspector();
    private final Map<UUID, HudState> statesByPlayer = new HashMap<>();
    private final Map<Store<EntityStore>, Long> nextRefreshByStore = new IdentityHashMap<>();

    public CommandHotswapHudService(@Nonnull CommandItemRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        long nextRefreshAtMs = nextRefreshByStore.getOrDefault(store, 0L);
        if (nowMs < nextRefreshAtMs) {
            return;
        }
        nextRefreshByStore.put(store, nowMs + REFRESH_INTERVAL_MS);
        store.forEachChunk(
                Query.and(Player.getComponentType()),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) ->
                        updateChunk(chunk)
        );
    }

    private void updateChunk(@Nonnull ArchetypeChunk<EntityStore> chunk) {
        for (int index = 0; index < chunk.size(); index++) {
            updatePlayer(chunk.getComponent(index, Player.getComponentType()));
        }
    }

    private void updatePlayer(@Nullable Player player) {
        UUID playerUuid = player != null ? player.getUuid() : null;
        if (playerUuid == null) {
            return;
        }
        CommandHotswapHudViewModel model = resolveModel(player);
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
    private CommandHotswapHudViewModel resolveModel(@Nullable Player player) {
        ItemStack stack = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (stack == null || stack.isEmpty() || stack.getItemId() == null || registry == null) {
            return hiddenModel();
        }
        TwCommandItemConfig config = registry.get(stack.getItemId());
        if (config == null || !config.isEnabled()) {
            return hiddenModel();
        }
        return new CommandHotswapHudViewModel(
                resolvePrimarySlot(player, stack, config),
                new CommandHotswapHudViewModel.Slot(
                        true, "RMB", "Tamework/CommandHotswaps/OpenMenu.png", ""
                ),
                resolveSlot(stack, config, Slot.Q, "Q"),
                resolveSlot(stack, config, Slot.E, "E"),
                resolveSlot(stack, config, Slot.R, "R")
        );
    }

    @Nonnull
    private CommandHotswapHudViewModel.Slot resolvePrimarySlot(
            @Nonnull Player player,
            @Nonnull ItemStack stack,
            @Nonnull TwCommandItemConfig config) {
        if (targetInspector.isLinkable(player, player.getReference(), config,
                player.getWorld() != null ? player.getWorld().getEntityStore().getStore() : null)) {
            return new CommandHotswapHudViewModel.Slot(
                    true, "LMB", "Tamework/CommandHotswaps/Link.png", ""
            );
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
        return new CommandHotswapHudViewModel(
                CommandHotswapHudViewModel.Slot.hidden("LMB"),
                CommandHotswapHudViewModel.Slot.hidden("RMB"),
                CommandHotswapHudViewModel.Slot.hidden("Q"),
                CommandHotswapHudViewModel.Slot.hidden("E"),
                CommandHotswapHudViewModel.Slot.hidden("R")
        );
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

    private record HudState(@Nonnull PlayerRef playerRef,
                            @Nonnull TameworkCommandHotswapHud hud,
                            @Nonnull CommandHotswapHudViewModel model) {
    }
}
