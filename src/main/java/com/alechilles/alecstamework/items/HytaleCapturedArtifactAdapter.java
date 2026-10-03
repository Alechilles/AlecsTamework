package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import java.util.function.ToIntFunction;
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
    private final ToIntFunction<String> qualityResolver;

    public HytaleCapturedArtifactAdapter() {
        this(ItemStack::new);
    }

    HytaleCapturedArtifactAdapter(ItemStackFactory stackFactory) {
        this(stackFactory, HytaleCapturedArtifactAdapter::resolveQuality);
    }

    HytaleCapturedArtifactAdapter(ItemStackFactory stackFactory, ToIntFunction<String> qualityResolver) {
        this.stackFactory = Objects.requireNonNull(
                stackFactory,
                "stackFactory"
        );
        this.qualityResolver = Objects.requireNonNull(qualityResolver, "qualityResolver");
    }

    /** Freezes capture identity and metadata; optional display quality is carried by asset ID in metadata. */
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
        return restoreDisplayQuality(stackFactory.create(
                stack.getItemId(),
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                metadata
        ));
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

    /** Recreates the persisted item and resolves its optional display quality asset ID. */
    @Nonnull
    public ItemStack toItemStack(@Nonnull CapturedArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException(
                    "Captured artifact is required"
            );
        }
        return restoreDisplayQuality(stackFactory.create(
                artifact.itemId(),
                artifact.quantity(),
                artifact.durability(),
                artifact.maxDurability(),
                BsonDocument.parse(artifact.metadataExtendedJson())
        ));
    }

    private ItemStack restoreDisplayQuality(ItemStack stack) {
        BsonDocument metadata = stack.getMetadata();
        var quality = metadata == null ? null : metadata.get(TameworkMetadataKeys.CAPTURE_ITEM_QUALITY_ID);
        if (quality == null || !quality.isString()) return stack;
        int index = qualityResolver.applyAsInt(quality.asString().getValue());
        return index < 0 ? stack : stack.withQuality(index);
    }

    private static int resolveQuality(String qualityId) {
        try {
            return ItemQuality.getAssetMap().getIndexOrDefault(qualityId, -1);
        } catch (RuntimeException | LinkageError unavailable) {
            return -1;
        }
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
