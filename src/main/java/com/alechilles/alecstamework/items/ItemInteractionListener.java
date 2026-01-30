package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.ownership.OwnerStore;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.logging.Level;

public final class ItemInteractionListener {
    private final ItemFeatureRegistry registry;
    private final HytaleLogger logger;
    private final SpawnerFeatureHandler spawnerFeatureHandler;

    public ItemInteractionListener(ItemFeatureRegistry registry, HytaleLogger logger, SpawnerCaptureTracker captureTracker, OwnerStore ownerStore) {
        this.registry = registry;
        this.logger = logger;
        this.spawnerFeatureHandler = new SpawnerFeatureHandler(logger, captureTracker, registry, ownerStore);
    }

    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack itemStack = event.getItemInHand();
        if (itemStack == null || itemStack.isEmpty()) {
            return;
        }
        String itemId = itemStack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        ItemFeatureConfig config = registry.get(itemId);
        if (config == null) {
            return;
        }

        InteractionType action = event.getActionType();
        logger.at(Level.INFO).log(
                "Tamework item interaction (matched config): item=" + itemId
                        + " action=" + action
                        + " features=[spawner=" + config.isSpawnerEnabled()
                        + ", whistle=" + config.isWhistleEnabled()
                        + ", captureClearsOwner=" + config.isCaptureClearsOwner()
                        + ", spawnAssignsOwner=" + config.isSpawnAssignsOwner()
                        + ", whistleRadius=" + config.getWhistleRadius()
                        + "]"
        );

        spawnerFeatureHandler.handle(event, config);
    }

    public void onMouseButton(PlayerMouseButtonEvent event) {
        String itemId = resolveItemId(event);
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        ItemFeatureConfig config = registry.get(itemId);
        if (config == null) {
            return;
        }

        logger.at(Level.INFO).log(
                "Tamework mouse button (matched config): item=" + itemId
                        + " button=" + event.getMouseButton()
                        + " targetEntity=" + (event.getTargetEntity() != null)
                        + " targetBlock=" + (event.getTargetBlock() != null)
        );
    }

    private String resolveItemId(PlayerMouseButtonEvent event) {
        Item item = event.getItemInHand();
        if (item != null) {
            String id = item.getId();
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        if (event.getPlayer() == null) {
            return null;
        }
        Inventory inventory = event.getPlayer().getInventory();
        if (inventory == null) {
            return null;
        }
        ItemStack stack = inventory.getItemInHand();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getItemId();
    }
}
