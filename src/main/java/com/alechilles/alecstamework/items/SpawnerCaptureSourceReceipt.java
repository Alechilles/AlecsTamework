package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** Exact inventory receipt used to disambiguate singleton source spending across a crash. */
final class SpawnerCaptureSourceReceipt {
    private SpawnerCaptureSourceReceipt() {
    }

    @Nonnull
    static ItemStack mark(@Nonnull ItemStack source, @Nonnull UUID attemptId) {
        return source.withMetadata(TameworkMetadataKeys.CAPTURE_SOURCE_SPEND_ATTEMPT_ID,
                Codec.UUID_STRING, attemptId);
    }

    static boolean belongsTo(@Nullable ItemStack stack, @Nonnull UUID attemptId) {
        if (stack == null || stack.isEmpty()) return false;
        UUID stored = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_SOURCE_SPEND_ATTEMPT_ID, Codec.UUID_STRING);
        return attemptId.equals(stored);
    }

    @Nonnull
    static ItemStack original(@Nonnull ItemStack receipt) {
        BsonDocument metadata = receipt.getMetadata();
        BsonDocument cleared = metadata == null ? null : metadata.clone();
        if (cleared != null) cleared.remove(TameworkMetadataKeys.CAPTURE_SOURCE_SPEND_ATTEMPT_ID);
        return receipt.withMetadata(cleared == null || cleared.isEmpty() ? null : cleared);
    }

    @Nonnull
    static ItemStack after(@Nonnull ItemStack receipt) {
        if (receipt.getQuantity() <= 0) {
            throw new IllegalArgumentException("Receipted source must contain at least one item");
        }
        if (receipt.getQuantity() == 1) return ItemStack.EMPTY;
        return original(receipt).withQuantity(receipt.getQuantity() - 1);
    }
}
