package com.alechilles.alecstamework.output;

import com.alechilles.alecstamework.api.HusbandryOutputConversion;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import javax.annotation.Nonnull;

/** Finalizes companion item output before materialization and activity publication. */
public final class CompanionOutputService {
    private static final int MAX_BONUS_COPIES = 3;
    private static final int MAX_CONVERSION_BATCH_ROLLS = 128;

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

    /**
     * Applies an additive expected-yield bonus to each resolved existing product.
     * A product never appears when its scaled resolved quantity is zero.
     */
    @Nonnull
    public static FinalizedOutput finalizeExpectedQuantity(
            List<ItemStack> baseDrops,
            ToDoubleFunction<ItemStack> bonusResolver,
            DoubleSupplier random
    ) {
        ArrayList<ItemStack> finalDrops = new ArrayList<>();
        if (baseDrops != null && bonusResolver != null && random != null) {
            for (ItemStack stack : baseDrops) {
                if (!isUsable(stack)) {
                    continue;
                }
                int quantity = resolveExpectedQuantity(
                        stack.getQuantity(), bonusResolver.applyAsDouble(stack), random);
                if (quantity > 0) {
                    finalDrops.add(copyWithQuantity(stack, quantity));
                }
            }
        }
        return new FinalizedOutput(finalDrops, quantities(finalDrops));
    }

    /**
     * Converts complete resolved-output batches without creating free output.
     *
     * <p>The resolver is called only after additive quantity resolution. Each
     * successful roll consumes its full input batch; incomplete and unrolled
     * batches remain as the input item. The per-stack roll cap keeps malformed
     * output quantities from creating an unbounded action-time loop.
     */
    @Nonnull
    public static FinalizedOutput applyOutputConversions(
            @Nonnull FinalizedOutput resolved,
            Function<String, HusbandryOutputConversion> conversionResolver,
            DoubleSupplier random
    ) {
        if (resolved == null || conversionResolver == null || random == null) {
            return resolved == null ? new FinalizedOutput(List.of(), Map.of()) : resolved;
        }
        Map<String, Integer> quantities = resolveOutputConversions(
                resolved.itemQuantities(), conversionResolver, random);
        ArrayList<ItemStack> converted = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            addItem(converted, entry.getKey(), entry.getValue());
        }
        return new FinalizedOutput(converted, quantities);
    }

    /** Resolves conversion quantities before item stacks are materialized. */
    @Nonnull
    public static Map<String, Integer> resolveOutputConversions(
            Map<String, Integer> resolvedQuantities,
            Function<String, HusbandryOutputConversion> conversionResolver,
            DoubleSupplier random
    ) {
        if (resolvedQuantities == null || resolvedQuantities.isEmpty()
                || conversionResolver == null || random == null) {
            return normalizeQuantities(resolvedQuantities);
        }
        LinkedHashMap<String, Integer> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : resolvedQuantities.entrySet()) {
            String inputId = entry.getKey();
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            HusbandryOutputConversion conversion = conversionResolver.apply(inputId);
            if (quantity <= 0 || !matchesInput(inputId, conversion)) {
                mergeQuantity(converted, inputId, quantity);
                continue;
            }
            int completeBatches = quantity / conversion.inputQuantity();
            int rolls = Math.min(completeBatches, MAX_CONVERSION_BATCH_ROLLS);
            int convertedBatches = 0;
            for (int index = 0; index < rolls; index++) {
                if (random.getAsDouble() < conversion.chance()) {
                    convertedBatches++;
                }
            }
            long consumed = (long) convertedBatches * conversion.inputQuantity();
            long produced = (long) convertedBatches * conversion.outputQuantity();
            mergeQuantity(converted, inputId, safeQuantity((long) quantity - consumed));
            mergeQuantity(converted, conversion.outputItemId(), safeQuantity(produced));
        }
        return Map.copyOf(converted);
    }

    /** Resolves floor(expected) plus one fractional Bernoulli roll. */
    public static int resolveExpectedQuantity(
            int baseQuantity,
            double additiveBonus,
            DoubleSupplier random
    ) {
        if (baseQuantity <= 0 || !Double.isFinite(additiveBonus) || random == null) {
            return 0;
        }
        double expected = baseQuantity * Math.max(0.0, 1.0 + additiveBonus);
        if (!Double.isFinite(expected) || expected <= 0.0) {
            return 0;
        }
        long whole = (long) Math.floor(expected);
        double fraction = expected - whole;
        if (fraction > 0.0 && random.getAsDouble() < fraction) {
            whole++;
        }
        return (int) Math.min(Integer.MAX_VALUE, whole);
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
        if (itemId == null || itemId.isBlank() || quantity <= 0) {
            return;
        }
        quantities.merge(itemId, quantity, (left, right) ->
                (int) Math.min(Integer.MAX_VALUE, (long) left + right));
    }

    private static boolean isUsable(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItemId() != null && !stack.getItemId().isBlank()
                && stack.getQuantity() > 0;
    }

    private static boolean matchesInput(String inputId, HusbandryOutputConversion conversion) {
        return conversion != null && conversion.valid()
                && inputId != null && inputId.equalsIgnoreCase(conversion.inputItemId());
    }

    private static void addItem(List<ItemStack> target, String itemId, int quantity) {
        if (itemId != null && !itemId.isBlank() && quantity > 0) {
            target.add(new ItemStack(itemId, quantity));
        }
    }

    private static int safeQuantity(long quantity) {
        return quantity <= 0L ? 0 : (int) Math.min(Integer.MAX_VALUE, quantity);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack.cleanCopy();
    }

    private static ItemStack copyWithQuantity(ItemStack stack, int quantity) {
        return stack.withQuantity(quantity);
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
