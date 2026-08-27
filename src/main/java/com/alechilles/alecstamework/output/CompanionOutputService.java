package com.alechilles.alecstamework.output;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/** Finalizes companion item output before materialization and activity publication. */
public final class CompanionOutputService {
    private static final int MAX_BONUS_COPIES = 3;

    private CompanionOutputService() {
    }

    /** Apply the current bonus-copy modifier and report the exact final quantities. */
    @Nonnull
    public static FinalizedOutput finalizeDrops(
            List<ItemStack> baseDrops,
            boolean duplicate
    ) {
        return finalizeDrops(baseDrops, duplicate ? 1 : 0);
    }

    /** Apply a bounded number of additional copies and report exact quantities. */
    @Nonnull
    public static FinalizedOutput finalizeDrops(
            List<ItemStack> baseDrops,
            int bonusCopies
    ) {
        ArrayList<ItemStack> finalDrops = new ArrayList<>();
        int copies = Math.max(0, Math.min(MAX_BONUS_COPIES, bonusCopies));
        if (baseDrops != null) {
            for (ItemStack stack : baseDrops) {
                if (!isUsable(stack)) {
                    continue;
                }
                finalDrops.add(stack);
                for (int copy = 0; copy < copies; copy++) {
                    finalDrops.add(copy(stack));
                }
            }
        }
        return new FinalizedOutput(finalDrops, quantities(finalDrops));
    }

    /** Normalize an output that was already materialized, such as a filled container. */
    @Nonnull
    public static FinalizedOutput finalizeQuantities(Map<String, Integer> quantities) {
        return new FinalizedOutput(List.of(), normalizeQuantities(quantities));
    }

    private static Map<String, Integer> quantities(List<ItemStack> stacks) {
        LinkedHashMap<String, Integer> quantities = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!isUsable(stack)) {
                continue;
            }
            mergeQuantity(quantities, stack.getItemId(), stack.getQuantity());
        }
        return Map.copyOf(quantities);
    }

    private static Map<String, Integer> normalizeQuantities(
            Map<String, Integer> source
    ) {
        LinkedHashMap<String, Integer> quantities = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                String itemId = entry.getKey();
                Integer quantity = entry.getValue();
                if (itemId != null && !itemId.isBlank()
                        && quantity != null && quantity > 0) {
                    mergeQuantity(quantities, itemId, quantity);
                }
            }
        }
        return Map.copyOf(quantities);
    }

    private static void mergeQuantity(
            Map<String, Integer> quantities,
            String itemId,
            int quantity
    ) {
        quantities.merge(itemId, quantity, (left, right) ->
                (int) Math.min(Integer.MAX_VALUE, (long) left + right));
    }

    private static boolean isUsable(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItemId() != null && !stack.getItemId().isBlank()
                && stack.getQuantity() > 0;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack.cleanCopy();
    }

    /** Immutable finalized item output used by drop and activity paths. */
    public record FinalizedOutput(
            @Nonnull List<ItemStack> itemStacks,
            @Nonnull Map<String, Integer> itemQuantities
    ) {
        public FinalizedOutput {
            itemStacks = List.copyOf(itemStacks);
            itemQuantities = Map.copyOf(itemQuantities);
        }
    }
}
