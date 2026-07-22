package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves and exact-fences optional command access before capture entropy is requested. */
final class SpawnerCaptureCommandAccessService {
    private final CommandLinkPolicyService roles = new CommandLinkPolicyService();

    @Nonnull
    Decision validate(@Nullable Player player,
                      @Nonnull ItemFeatureConfig config,
                      @Nullable String postCaptureRoleId) {
        ItemFeatureConfig.CaptureItemMechanics mechanics = config.getCaptureMechanics();
        String requiredConfigId = normalize(mechanics.requiredCommandConfigId());
        if (requiredConfigId == null) {
            return mechanics.requireCommandAccessItem()
                    ? Decision.deny("capture-command-config-required")
                    : Decision.allow(null);
        }
        DefaultAssetMap<String, TwCommandItemConfig> map = TwCommandItemConfig.getAssetMap();
        TwCommandItemConfig command = map == null ? null : map.getAsset(requiredConfigId);
        if (command == null || !command.isEnabled()) {
            return Decision.deny("capture-command-config-unavailable");
        }
        String configuredFamily = normalize(mechanics.commandFamilyId());
        String commandFamily = normalize(command.getCommandFamilyId());
        if (configuredFamily != null && !configuredFamily.equals(commandFamily)) {
            return Decision.deny("capture-command-family-mismatch");
        }
        if (postCaptureRoleId == null || postCaptureRoleId.isBlank()
                || !roles.isRoleAllowed(postCaptureRoleId, command, true)) {
            return Decision.deny("capture-command-role-not-allowed");
        }
        String[] itemIds = command.getItemIds();
        if (itemIds == null || Arrays.stream(itemIds).noneMatch(id -> normalize(id) != null)) {
            return Decision.deny("capture-command-config-has-no-access-items");
        }
        String accessItemId = findAccessItem(player, itemIds);
        if (mechanics.requireCommandAccessItem() && accessItemId == null) {
            return Decision.deny("capture-command-access-item-missing");
        }
        return Decision.allow(accessItemId);
    }

    @Nullable
    private static String findAccessItem(@Nullable Player player, @Nonnull String[] itemIds) {
        Inventory inventory = player == null ? null : player.getInventory();
        CombinedItemContainer combined = inventory == null
                ? null : inventory.getCombinedBackpackStorageHotbarFirst();
        if (combined == null) return null;
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack stack = combined.getItemStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            for (String candidate : itemIds) {
                if (stack.getItemId().equals(normalize(candidate))) return stack.getItemId();
            }
        }
        return null;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record Decision(boolean allowed, @Nullable String accessItemId, @Nonnull String reason) {
        private static Decision allow(@Nullable String accessItemId) {
            return new Decision(true, accessItemId, "capture-command-access-allowed");
        }

        private static Decision deny(@Nonnull String reason) {
            return new Decision(false, null, reason);
        }
    }
}
