package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// Handles item-based requirement resolution and checks for interactions.
final class InteractionItemRequirementResolver {
    private final InteractionParamResolver paramResolver;

    // Builds a resolver tied to the shared parameter resolver.
    InteractionItemRequirementResolver(InteractionParamResolver paramResolver) {
        this.paramResolver = paramResolver;
    }

    // Returns true if the held item matches the requirement and quantity.
    boolean matchesItemsInHand(ItemsInHandRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return false;
        }
        String[] items = resolveItemsInHand(requirement, role, ctx);
        int quantity = resolveRequiredQuantity(requirement.getQuantity());
        return isHeldItemInList(items, quantity, ctx);
    }

    // Returns true if the inventory contains any listed item with the required quantity.
    boolean matchesItemsInInventory(ItemsInInventoryRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null || ctx == null) {
            return false;
        }
        String[] items = resolveItemsInInventory(requirement, role, ctx);
        if (items == null || items.length == 0) {
            return false;
        }
        int quantity = resolveRequiredQuantity(requirement.getQuantity());
        CombinedItemContainer container = resolveInventoryContainer(ctx);
        if (container == null) {
            return false;
        }
        Set<String> itemSet = normalizeItemSet(items);
        if (itemSet.isEmpty()) {
            return false;
        }
        for (String itemId : itemSet) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            if (countItemInContainer(container, itemId) >= quantity) {
                return true;
            }
        }
        return false;
    }

    // Returns true if the equipped slots contain any listed item (or any item if list empty).
    boolean matchesItemsEquipped(ItemsEquippedRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null || ctx == null) {
            return false;
        }
        Inventory inventory = ctx.inventory;
        if (inventory == null) {
            return false;
        }
        EquippedSlotSelection selection = resolveEquippedSlots(requirement.getSlots());
        if (!selection.hasAny()) {
            return false;
        }
        String[] items = resolveItemsEquipped(requirement, role, ctx);
        if (items == null || items.length == 0) {
            return hasAnyItemEquipped(inventory, selection);
        }
        Set<String> itemSet = normalizeItemSet(items);
        if (itemSet.isEmpty()) {
            return false;
        }
        if (selection.includeArmor) {
            ItemContainer armor = inventory.getArmor();
            if (armor != null && containsItemInArmor(armor, selection.armorSlots, itemSet)) {
                return true;
            }
        }
        if (selection.includeUtility) {
            ItemContainer utility = inventory.getUtility();
            if (utility != null && containsItemInContainer(utility, itemSet)) {
                return true;
            }
        }
        return false;
    }

    // Checks if the active item is included in the provided list.
    boolean isHeldItemInList(String[] items, InteractionContextSnapshot ctx) {
        if (ctx == null || items == null || items.length == 0) {
            return false;
        }
        ItemStack stack = ctx.activeItem;
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String itemId = stack.getItemId();
        if (itemId == null) {
            return false;
        }
        return Arrays.stream(items).anyMatch(itemId::equalsIgnoreCase);
    }

    // Checks if the active item matches the list and has enough quantity.
    boolean isHeldItemInList(String[] items, int quantity, InteractionContextSnapshot ctx) {
        if (!isHeldItemInList(items, ctx)) {
            return false;
        }
        ItemStack stack = ctx != null ? ctx.activeItem : null;
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getQuantity() >= quantity;
    }

    // Resolves the items list for an in-hand requirement.
    String[] resolveItemsInHand(ItemsInHandRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return new String[0];
        }
        String[] paramItems = resolveItemsParam(role, ctx, requirement.getItemsParam());
        if (paramItems != null && paramItems.length > 0) {
            return paramItems;
        }
        return requirement.getItems();
    }

    // Resolves the items list for an inventory requirement.
    String[] resolveItemsInInventory(ItemsInInventoryRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return new String[0];
        }
        String[] paramItems = resolveItemsParam(role, ctx, requirement.getItemsParam());
        if (paramItems != null && paramItems.length > 0) {
            return paramItems;
        }
        return requirement.getItems();
    }

    // Resolves the items list for an equipped requirement.
    String[] resolveItemsEquipped(ItemsEquippedRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return new String[0];
        }
        String[] paramItems = resolveItemsParam(role, ctx, requirement.getItemsParam());
        if (paramItems != null && paramItems.length > 0) {
            return paramItems;
        }
        return requirement.getItems();
    }

    // Reads items from a role parameter, parsing arrays when provided.
    String[] resolveItemsParam(Role role, InteractionContextSnapshot ctx, String itemsParam) {
        String paramName = itemsParam;
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        String[] rawValues = paramResolver.getStringArrayParam(role, ctx, paramName);
        if (rawValues == null || rawValues.length == 0) {
            return null;
        }
        String[] resolved = InteractionItemParser.parseItemIdsFromParam(rawValues);
        return resolved != null && resolved.length > 0 ? resolved : null;
    }

    // Returns a normalized set of item ids for matching.
    Set<String> normalizeItemSet(String[] items) {
        Set<String> set = new HashSet<>();
        if (items == null) {
            return set;
        }
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            set.add(item.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    // Resolves the equipped slot selection flags from config values.
    EquippedSlotSelection resolveEquippedSlots(String[] slots) {
        boolean[] armorSlots = new boolean[ItemArmorSlot.VALUES.length];
        if (slots == null || slots.length == 0) {
            Arrays.fill(armorSlots, true);
            return new EquippedSlotSelection(true, true, armorSlots);
        }
        boolean includeArmor = false;
        boolean includeUtility = false;
        for (String slot : slots) {
            if (slot == null || slot.isBlank()) {
                continue;
            }
            String normalized = slot.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "head":
                    armorSlots[ItemArmorSlot.Head.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "chest":
                    armorSlots[ItemArmorSlot.Chest.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "hands":
                    armorSlots[ItemArmorSlot.Hands.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "legs":
                    armorSlots[ItemArmorSlot.Legs.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "armor":
                case "equipped":
                    Arrays.fill(armorSlots, true);
                    includeArmor = true;
                    break;
                case "utility":
                case "accessory":
                case "accessories":
                    includeUtility = true;
                    break;
                default:
                    break;
            }
        }
        return new EquippedSlotSelection(includeArmor, includeUtility, armorSlots);
    }

    // Returns true when any equipped item exists in the selected slots.
    boolean hasAnyItemEquipped(Inventory inventory, EquippedSlotSelection selection) {
        if (inventory == null || selection == null || !selection.hasAny()) {
            return false;
        }
        if (selection.includeArmor) {
            ItemContainer armor = inventory.getArmor();
            if (armor != null && hasAnyItemInArmor(armor, selection.armorSlots)) {
                return true;
            }
        }
        if (selection.includeUtility) {
            ItemContainer utility = inventory.getUtility();
            if (utility != null && hasAnyItemInContainer(utility)) {
                return true;
            }
        }
        return false;
    }

    // Checks for any item in armor slots.
    boolean hasAnyItemInArmor(ItemContainer armor, boolean[] slots) {
        if (armor == null || slots == null) {
            return false;
        }
        int max = Math.min(slots.length, armor.getCapacity());
        for (short i = 0; i < max; i++) {
            if (!slots[i]) {
                continue;
            }
            ItemStack stack = armor.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Checks for any item in a container.
    boolean hasAnyItemInContainer(ItemContainer container) {
        if (container == null) {
            return false;
        }
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Checks whether armor slots contain any of the requested items.
    boolean containsItemInArmor(ItemContainer armor, boolean[] slots, Set<String> items) {
        if (armor == null || slots == null || items == null || items.isEmpty()) {
            return false;
        }
        int max = Math.min(slots.length, armor.getCapacity());
        for (short i = 0; i < max; i++) {
            if (!slots[i]) {
                continue;
            }
            ItemStack stack = armor.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId != null && items.contains(itemId.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // Checks whether any item in the container matches the requested set.
    boolean containsItemInContainer(ItemContainer container, Set<String> items) {
        if (container == null || items == null || items.isEmpty()) {
            return false;
        }
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId != null && items.contains(itemId.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // Counts occurrences of an item id in a combined container.
    int countItemInContainer(CombinedItemContainer container, String itemId) {
        if (container == null || itemId == null || itemId.isBlank()) {
            return 0;
        }
        short capacity = container.getCapacity();
        int total = 0;
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (itemId.equalsIgnoreCase(stack.getItemId())) {
                total += stack.getQuantity();
            }
        }
        return total;
    }

    // Returns the combined inventory container for the interaction context.
    CombinedItemContainer resolveInventoryContainer(InteractionContextSnapshot ctx) {
        return ctx != null ? ctx.combinedInventory : null;
    }

    // Normalizes quantity to a minimum of one.
    int resolveRequiredQuantity(Integer quantity) {
        if (quantity == null) {
            return 1;
        }
        return Math.max(1, quantity);
    }

    // Encapsulates which armor and utility slots are included in matching.
    static final class EquippedSlotSelection {
        private final boolean includeArmor;
        private final boolean includeUtility;
        private final boolean[] armorSlots;

        // Builds a slot selection for armor/utility groups.
        private EquippedSlotSelection(boolean includeArmor, boolean includeUtility, boolean[] armorSlots) {
            this.includeArmor = includeArmor;
            this.includeUtility = includeUtility;
            this.armorSlots = armorSlots;
        }

        // Returns true when any armor or utility slot is included.
        private boolean hasAny() {
            return includeArmor || includeUtility;
        }
    }
}
