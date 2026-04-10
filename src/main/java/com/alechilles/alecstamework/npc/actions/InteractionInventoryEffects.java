package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemsHandEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.DropItemEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemQuantity;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RemoveItemsHandEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RemoveItemsInventoryEffect;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import com.hypixel.hytale.server.npc.role.Role;

/** Applies inventory-related interaction effects and item drops. */
final class InteractionInventoryEffects {
    private static final String HARVEST_DOUBLE_DROP_CHANCE_EFFECT_KEY = "HarvestDoubleDropChanceMultiplier";
    private final ActionTameworkInteract owner;

    // Builds inventory effects for interaction entries.
    InteractionInventoryEffects(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Removes items from the player's active hotbar slot.
    boolean applyRemoveItemsHand(RemoveItemsHandEffect effect,
                                 Role role,
                                 InteractionContextSnapshot ctx,
                                 Player player) {
        if (effect == null || player == null) {
            return false;
        }
        String[] allowedItems = resolveHandItems(effect, role, ctx);
        if (allowedItems != null && allowedItems.length > 0 && !owner.isHeldItemInList(allowedItems, ctx)) {
            return false;
        }
        int quantity = effect.getQuantity() != null ? effect.getQuantity() : 1;
        return InteractionItemConsumption.removeHeldItemQuantity(player, quantity);
    }

    // Adds items to the player's active hotbar slot when possible.
    boolean applyAddItemsHand(AddItemsHandEffect effect,
                              Role role,
                              InteractionContextSnapshot ctx,
                              Player player) {
        if (effect == null || player == null) {
            return false;
        }
        ItemQuantity[] items = resolveItemQuantities(effect.getItems(), effect.getItemsParam(), role, ctx);
        if (items == null || items.length == 0) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        CombinedItemContainer combined = resolveInventoryContainer(player);
        byte activeSlot = inventory.getActiveHotbarSlot();
        boolean applied = false;
        for (ItemQuantity item : items) {
            if (item == null || item.getItem() == null || item.getItem().isBlank()) {
                continue;
            }
            int quantity = item.getQuantity() != null ? item.getQuantity() : 1;
            if (quantity <= 0) {
                continue;
            }
            boolean placed = tryAddToHotbarSlot(hotbar, activeSlot, item.getItem(), quantity);
            if (!placed && combined != null) {
                ItemStackTransaction transaction = combined.addItemStack(new ItemStack(item.getItem(), quantity));
                if (transaction != null) {
                    ItemStack remainder = transaction.getRemainder();
                    if (remainder == null || remainder.isEmpty() || remainder.getQuantity() < quantity) {
                        placed = true;
                    }
                }
            }
            applied |= placed;
        }
        return applied;
    }

    // Removes items from the player's inventory container.
    boolean applyRemoveItemsInventory(RemoveItemsInventoryEffect effect,
                                      Role role,
                                      InteractionContextSnapshot ctx,
                                      Player player) {
        if (effect == null || player == null) {
            return false;
        }
        ItemQuantity[] items = resolveItemQuantities(effect.getItems(), effect.getItemsParam(), role, ctx);
        if (items == null || items.length == 0) {
            return false;
        }
        CombinedItemContainer container = resolveInventoryContainer(player);
        if (container == null) {
            return false;
        }
        boolean applied = false;
        for (ItemQuantity item : items) {
            if (item == null || item.getItem() == null || item.getItem().isBlank()) {
                continue;
            }
            int quantity = item.getQuantity() != null ? item.getQuantity() : 1;
            if (quantity <= 0) {
                continue;
            }
            ItemStackTransaction transaction = container.removeItemStack(new ItemStack(item.getItem(), quantity));
            if (transaction != null) {
                ItemStack remainder = transaction.getRemainder();
                if (remainder == null || remainder.isEmpty() || remainder.getQuantity() < quantity) {
                    applied = true;
                }
            }
        }
        return applied;
    }

    // Adds items into the player's inventory container.
    boolean applyAddItemInventory(AddItemInventoryEffect effect,
                                  Role role,
                                  InteractionContextSnapshot ctx,
                                  Player player) {
        if (effect == null || player == null) {
            return false;
        }
        ItemQuantity[] items = resolveItemQuantities(effect.getItems(), effect.getItemsParam(), role, ctx);
        if (items == null || items.length == 0) {
            return false;
        }
        CombinedItemContainer container = resolveInventoryContainer(player);
        if (container == null) {
            return false;
        }
        boolean applied = false;
        for (ItemQuantity item : items) {
            if (item == null || item.getItem() == null || item.getItem().isBlank()) {
                continue;
            }
            int quantity = item.getQuantity() != null ? item.getQuantity() : 1;
            if (quantity <= 0) {
                continue;
            }
            ItemStackTransaction transaction = container.addItemStack(new ItemStack(item.getItem(), quantity));
            if (transaction != null) {
                ItemStack remainder = transaction.getRemainder();
                if (remainder == null || remainder.isEmpty() || remainder.getQuantity() < quantity) {
                    applied = true;
                }
            }
        }
        return applied;
    }

    // Drops items from the NPC into the world based on drop config.
    boolean applyDropItem(DropItemEffect effect,
                          Ref<EntityStore> npcRef,
                          Store<EntityStore> store,
                          boolean harvestInteraction) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        List<ItemStack> drops = resolveDropItems(effect);
        if (drops.isEmpty()) {
            return false;
        }
        if (harvestInteraction && shouldDoubleHarvestDrops(npcRef, store)) {
            drops = duplicateDrops(drops);
        }
        float throwSpeed = effect.getThrowSpeed() != null ? effect.getThrowSpeed().floatValue() : 0.0f;
        boolean applied = false;
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (throwSpeed > 0.0f) {
                ItemUtils.throwItem(npcRef, stack, throwSpeed, store);
            } else {
                ItemUtils.dropItem(npcRef, stack, store);
            }
            applied = true;
        }
        return applied;
    }

    private boolean shouldDoubleHarvestDrops(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                HARVEST_DOUBLE_DROP_CHANCE_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(multiplier)) {
            return false;
        }
        double chance = clamp(multiplier - 1.0, 0.0, 1.0);
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    private List<ItemStack> duplicateDrops(List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return List.of();
        }
        ArrayList<ItemStack> out = new ArrayList<>(drops.size() * 2);
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            out.add(stack);
            ItemStack duplicate = cloneStack(stack);
            if (duplicate != null && !duplicate.isEmpty()) {
                out.add(duplicate);
            }
        }
        return out;
    }

    private ItemStack cloneStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        int quantity = stack.getQuantity();
        if (quantity <= 0) {
            return null;
        }
        return new ItemStack(
                itemId,
                quantity,
                stack.getDurability(),
                stack.getMaxDurability(),
                stack.getMetadata()
        );
    }

    // Resolves the combined inventory container for a player.
    private CombinedItemContainer resolveInventoryContainer(Player player) {
        if (player == null) {
            return null;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        return inventory.getCombinedBackpackStorageHotbar();
    }

    private boolean tryAddToHotbarSlot(ItemContainer hotbar,
                                       byte slot,
                                       String itemId,
                                       int quantity) {
        if (hotbar == null || itemId == null || itemId.isBlank() || quantity <= 0) {
            return false;
        }
        short slotIndex = slot < 0 ? 0 : (short) slot;
        ItemStack existing = hotbar.getItemStack(slotIndex);
        if (existing == null || existing.isEmpty()) {
            hotbar.setItemStackForSlot(slotIndex, new ItemStack(itemId, quantity));
            return true;
        }
        String existingId = existing.getItemId();
        if (existingId != null && existingId.equalsIgnoreCase(itemId)) {
            int newQuantity = existing.getQuantity() + quantity;
            ItemStack merged = new ItemStack(
                    existingId,
                    newQuantity,
                    existing.getDurability(),
                    existing.getMaxDurability(),
                    existing.getMetadata()
            );
            hotbar.setItemStackForSlot(slotIndex, merged);
            return true;
        }
        return false;
    }

    private String[] resolveHandItems(RemoveItemsHandEffect effect,
                                      Role role,
                                      InteractionContextSnapshot ctx) {
        if (effect == null || owner == null) {
            return null;
        }
        String itemsParam = effect.getItemsParam();
        if (itemsParam != null && !itemsParam.isBlank()) {
            String[] rawValues = owner.getRoleStringArrayParam(role, ctx, itemsParam);
            String[] resolved = InteractionItemParser.parseItemIdsFromParam(rawValues);
            if (resolved != null && resolved.length > 0) {
                return resolved;
            }
        }
        return effect.getItems();
    }

    private ItemQuantity[] resolveItemQuantities(ItemQuantity[] configured,
                                                 String itemsParam,
                                                 Role role,
                                                 InteractionContextSnapshot ctx) {
        if (owner != null && itemsParam != null && !itemsParam.isBlank()) {
            String[] rawValues = owner.getRoleStringArrayParam(role, ctx, itemsParam);
            ItemQuantity[] resolved = InteractionItemParser.parseItemQuantitiesFromParam(rawValues);
            if (resolved != null && resolved.length > 0) {
                return resolved;
            }
        }
        return configured;
    }

    // Builds the list of item drops for a drop effect.
    private List<ItemStack> resolveDropItems(DropItemEffect effect) {
        List<ItemStack> drops = new ArrayList<>();
        if (effect == null) {
            return drops;
        }
        Random random = ThreadLocalRandom.current();
        String dropListId = effect.getDropList();
        if (dropListId != null && !dropListId.isBlank()) {
            DefaultAssetMap<String, ItemDropList> assetMap = ItemDropList.getAssetMap();
            ItemDropList dropList = assetMap != null ? assetMap.getAssetMap().get(dropListId) : null;
            if (dropList == null) {
                logDebug("DropItem effect: drop list not found: " + dropListId);
            } else {
                ItemDropContainer container = dropList.getContainer();
                if (container != null) {
                    List<ItemDrop> roll = new ArrayList<>();
                    container.populateDrops(roll, random::nextDouble, null);
                    for (ItemDrop drop : roll) {
                        if (drop == null || drop.getItemId() == null || drop.getItemId().isBlank()) {
                            continue;
                        }
                        int quantity = drop.getRandomQuantity(random);
                        if (quantity <= 0) {
                            continue;
                        }
                        if (drop.getMetadata() != null) {
                            drops.add(new ItemStack(drop.getItemId(), quantity, drop.getMetadata()));
                        } else {
                            drops.add(new ItemStack(drop.getItemId(), quantity));
                        }
                    }
                }
            }
        }
        if (!drops.isEmpty()) {
            return drops;
        }
        String itemId = effect.getItem();
        if (itemId == null || itemId.isBlank()) {
            return drops;
        }
        int quantity = resolveDropQuantity(effect, random);
        if (quantity > 0) {
            drops.add(new ItemStack(itemId, quantity));
        }
        return drops;
    }

    // Picks a quantity within the configured bounds.
    private int resolveDropQuantity(DropItemEffect effect, Random random) {
        int min = effect.getQuantityMin() != null ? effect.getQuantityMin() : 1;
        int max = effect.getQuantityMax() != null ? effect.getQuantityMax() : min;
        if (min < 1) {
            min = 1;
        }
        if (max < min) {
            max = min;
        }
        if (min == max) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    // Emits a debug log entry when available.
    private void logDebug(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.FINE).log(message);
        }
    }
}
