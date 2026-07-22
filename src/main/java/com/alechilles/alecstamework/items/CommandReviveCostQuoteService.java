package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.alechilles.alecstamework.ui.CommandReviveCostPresentation;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds one complete server-authoritative multi-item quote from one inventory scan. */
public final class CommandReviveCostQuoteService {
    private final CommandItemDisplayResolver displayResolver = new CommandItemDisplayResolver();

    @Nonnull
    public CommandReviveCostPresentation quote(@Nullable Player player,
                                               @Nonnull TwCompanionConfig.ReviveSettings settings) {
        return quote(countInventory(player), player, settings);
    }

    @Nonnull
    CommandReviveCostPresentation quote(@Nonnull Map<String, Integer> ownedByItemId,
                                        @Nullable Player player,
                                        @Nonnull TwCompanionConfig.ReviveSettings settings) {
        TwItemCostComponent[] costs = settings.getCosts();
        ArrayList<CommandReviveCostPresentation.CostLine> lines = new ArrayList<>(costs.length);
        for (TwItemCostComponent cost : costs) {
            String itemId = cost.getItemId();
            lines.add(new CommandReviveCostPresentation.CostLine(
                    itemId,
                    displayResolver.resolveItemDisplayName(player, itemId),
                    resolveItemIcon(itemId),
                    Math.max(0, ownedByItemId.getOrDefault(itemId, 0)),
                    cost.getQuantity()
            ));
        }
        return new CommandReviveCostPresentation(
                List.copyOf(lines),
                fingerprint(settings),
                settings.getInsufficientCostMessage()
        );
    }

    /** Scans backpack, storage, and hotbar once in deterministic combined-container order. */
    @Nonnull
    Map<String, Integer> countInventory(@Nullable Player player) {
        HashMap<String, Integer> counts = new HashMap<>();
        Inventory inventory = player == null ? null : player.getInventory();
        CombinedItemContainer combined = inventory == null
                ? null
                : inventory.getCombinedBackpackStorageHotbar();
        if (combined == null) {
            return counts;
        }
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack stack = combined.getItemStack(slot);
            if (stack == null || stack.isEmpty() || stack.getItemId() == null || stack.getQuantity() <= 0) {
                continue;
            }
            counts.merge(stack.getItemId(), stack.getQuantity(), Integer::sum);
        }
        return counts;
    }

    @Nonnull
    static String fingerprint(@Nonnull TwCompanionConfig.ReviveSettings settings) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(settings.isEnabled()).append('|').append(settings.getGameplayCooldownMs());
        for (TwItemCostComponent cost : settings.getCosts()) {
            canonical.append('|').append(cost.getItemId()).append('=').append(cost.getQuantity());
        }
        canonical.append('|').append(settings.getInsufficientCostMessage());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Nullable
    private static String resolveItemIcon(@Nonnull String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item == null) return null;
            Method method = item.getClass().getMethod("getIcon");
            Object value = method.invoke(item);
            return value instanceof String icon && !icon.isBlank() ? icon : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
