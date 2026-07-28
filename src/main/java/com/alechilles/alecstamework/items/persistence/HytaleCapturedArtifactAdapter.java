package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Translates exact Hytale item values at the live boundary into engine-neutral capture artifacts.
 */
public final class HytaleCapturedArtifactAdapter {
    private static final JsonWriterSettings EXTENDED_JSON =
            JsonWriterSettings.builder()
                    .outputMode(JsonMode.EXTENDED)
                    .build();
    private final ItemStackFactory stackFactory;

    public HytaleCapturedArtifactAdapter() {
        this(ItemStack::new);
    }

    HytaleCapturedArtifactAdapter(ItemStackFactory stackFactory) {
        this.stackFactory = Objects.requireNonNull(
                stackFactory,
                "stackFactory"
        );
    }

    /** Freezes every persisted Hytale stack field into one hashed artifact. */
    @Nonnull
    public CapturedArtifact toArtifact(@Nonnull ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nonempty captured item stack is required"
            );
        }
        BsonDocument metadata = stack.getMetadata();
        String metadataJson = metadata == null
                ? "{}"
                : metadata.toJson(EXTENDED_JSON);
        return CapturedArtifact.create(
                stack.getItemId(),
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                metadataJson
        );
    }

    /** Returns whether a live stack is the exact hashed artifact value. */
    public boolean matches(
            @Nullable ItemStack stack,
            @Nullable CapturedArtifact artifact
    ) {
        if (stack == null || stack.isEmpty() || artifact == null) {
            return false;
        }
        try {
            return artifact.equals(toArtifact(stack));
        } catch (IllegalArgumentException invalidStack) {
            return false;
        }
    }

    /**
     * Copies an exact stack value with the supplied persisted metadata additions.
     */
    @Nonnull
    ItemStack withMetadata(
            @Nonnull ItemStack stack,
            @Nonnull BsonDocument additions
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(additions, "additions");
        BsonDocument metadata = new BsonDocument();
        if (stack.getMetadata() != null) {
            metadata.putAll(stack.getMetadata());
        }
        metadata.putAll(additions);
        return stackFactory.create(
                stack.getItemId(),
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                metadata
        );
    }

    /** Copies an engine-neutral artifact with the supplied persisted metadata additions. */
    @Nonnull
    CapturedArtifact withMetadata(
            @Nonnull CapturedArtifact artifact,
            @Nonnull BsonDocument additions
    ) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(additions, "additions");
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        metadata.putAll(additions);
        return CapturedArtifact.create(
                artifact.itemId(),
                artifact.quantity(),
                artifact.durability(),
                artifact.maxDurability(),
                metadata.toJson(EXTENDED_JSON)
        );
    }

    /** Recreates the exact persisted item value without consulting mutable item assets. */
    @Nonnull
    public ItemStack toItemStack(@Nonnull CapturedArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException(
                    "Captured artifact is required"
            );
        }
        return stackFactory.create(
                artifact.itemId(),
                artifact.quantity(),
                artifact.durability(),
                artifact.maxDurability(),
                BsonDocument.parse(artifact.metadataExtendedJson())
        );
    }

    @FunctionalInterface
    interface ItemStackFactory {
        ItemStack create(
                String itemId,
                int quantity,
                double durability,
                double maxDurability,
                BsonDocument metadata
        );
    }
}
