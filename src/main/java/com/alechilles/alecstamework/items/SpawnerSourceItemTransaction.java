package com.alechilles.alecstamework.items;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Finalizes one exact spawner source stack and restores it if owner apply fails. */
final class SpawnerSourceItemTransaction {
    private final SpawnerPlayerInventoryService inventory;
    @Nullable
    private final Player player;
    @Nullable
    private final World world;
    @Nullable
    private final UUID playerUuid;
    @Nullable
    private final Integer slot;
    private final ItemStack original;
    @Nullable
    private final HytaleLogger logger;
    private final String flow;
    @Nullable
    private ItemStack applied;

    SpawnerSourceItemTransaction(@Nonnull SpawnerPlayerInventoryService inventory,
                                 @Nonnull Player player,
                                 @Nullable Integer slot,
                                 @Nonnull ItemStack original,
                                 @Nullable HytaleLogger logger,
                                 @Nonnull String flow) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.player = Objects.requireNonNull(player, "player");
        this.world = null;
        this.playerUuid = null;
        this.slot = slot;
        this.original = Objects.requireNonNull(original, "original");
        this.logger = logger;
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /** Uses stable identity so an async spawn continuation never retains a Player component. */
    SpawnerSourceItemTransaction(@Nonnull SpawnerPlayerInventoryService inventory,
                                 @Nonnull World world,
                                 @Nonnull UUID playerUuid,
                                 @Nullable Integer slot,
                                 @Nonnull ItemStack original,
                                 @Nullable HytaleLogger logger,
                                 @Nonnull String flow) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.player = null;
        this.world = Objects.requireNonNull(world, "world");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.slot = slot;
        this.original = Objects.requireNonNull(original, "original");
        this.logger = logger;
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    boolean prepare(@Nonnull ItemStack replacement) {
        if (!replace(original, replacement)) {
            log(Level.WARNING, flow + ": source item changed before owner admission could apply.");
            return false;
        }
        applied = replacement;
        return true;
    }

    void commit() {
        applied = null;
    }

    void compensate() {
        ItemStack replacement = applied;
        applied = null;
        if (replacement != null && !replace(replacement, original)) {
            log(Level.SEVERE, flow + ": failed to restore the source item after owner apply failed.");
        }
    }

    private boolean replace(@Nonnull ItemStack expected, @Nonnull ItemStack replacement) {
        if (slot == null) {
            return false;
        }
        Player resolved = resolvePlayer();
        if (resolved == null) {
            return false;
        }
        ItemStack current = inventory.getHotbarItem(resolved, slot);
        if (Objects.equals(current, replacement)) {
            return true;
        }
        return Objects.equals(current, expected)
                && inventory.updateHotbarSlot(resolved, slot, replacement);
    }

    @Nullable
    private Player resolvePlayer() {
        if (player != null) {
            return player;
        }
        WorldPlayerResolver.ResolvedPlayer resolved = world == null || playerUuid == null
                ? null : WorldPlayerResolver.resolve(world, playerUuid);
        return resolved == null ? null : resolved.player();
    }

    private void log(@Nonnull Level level, @Nonnull String message) {
        if (logger != null) {
            logger.at(level).log(message);
        }
    }
}
