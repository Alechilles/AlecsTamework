package com.alechilles.alecstamework.api;

import javax.annotation.Nullable;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;

/** Immutable description of the tool captured when a husbandry action began. */
public record HusbandryToolContext(
        String itemId,
        int quantity,
        double durability,
        double maxDurability,
        @Nullable BsonDocument metadata
) {
    public HusbandryToolContext {
        itemId = itemId == null || itemId.isBlank() ? null : itemId.trim();
        quantity = Math.max(0, quantity);
        durability = Double.isFinite(durability) ? durability : 0.0;
        maxDurability = Double.isFinite(maxDurability) ? Math.max(0.0, maxDurability) : 0.0;
        metadata = metadata == null ? null : metadata.clone();
    }

    @Override
    @Nullable
    public BsonDocument metadata() {
        return metadata == null ? null : metadata.clone();
    }

    /** Returns whether this action started with an actual item stack. */
    public boolean present() {
        return itemId != null && quantity > 0;
    }

    /** Captures one stack without exposing a mutable live item to outcome providers. */
    @Nullable
    public static HusbandryToolContext from(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return new HusbandryToolContext(stack.getItemId(), stack.getQuantity(), stack.getDurability(),
                stack.getMaxDurability(), stack.getMetadata());
    }
}
