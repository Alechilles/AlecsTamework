package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One item-and-quantity component of an ordered AND cost.
 *
 * <p>The config type is command-agnostic so other item-consuming features can share one
 * validation and authoring contract.</p>
 */
public final class TwItemCostComponent {
    public static final BuilderCodec<TwItemCostComponent> CODEC = BuilderCodec.builder(
            TwItemCostComponent.class,
            TwItemCostComponent::new
    )
            .<String>append(
                    new KeyedCodec<>("ItemId", Codec.STRING),
                    (component, value) -> component.itemId = value,
                    component -> component.itemId
            )
            .documentation("Exact item asset ID required by this cost component.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Quantity", Codec.INTEGER),
                    (component, value) -> component.quantity = value == null ? 0 : value,
                    component -> component.quantity
            )
            .documentation("Positive number of this item required and consumed.")
            .add()
            .build();

    public static final ArrayCodec<TwItemCostComponent> ARRAY_CODEC =
            new ArrayCodec<>(CODEC, TwItemCostComponent[]::new);
    public static final TwItemCostComponent[] EMPTY_ARRAY =
            new TwItemCostComponent[0];

    private String itemId;
    private int quantity;

    private TwItemCostComponent() {
    }

    public TwItemCostComponent(@Nonnull String itemId, int quantity) {
        this.itemId = normalizeItemId(itemId);
        this.quantity = quantity;
        validate();
    }

    @Nonnull
    public String getItemId() {
        return normalizeItemId(itemId);
    }

    public int getQuantity() {
        return quantity;
    }

    public void validate() {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                    "Cost ItemId must be non-blank."
            );
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Cost Quantity must be positive for " + itemId + "."
            );
        }
    }

    @Nonnull
    public TwItemCostComponent copy() {
        return new TwItemCostComponent(getItemId(), quantity);
    }

    /** Validates and defensively copies an ordered AND cost. */
    @Nonnull
    public static TwItemCostComponent[] validateAndCopy(
            @Nullable TwItemCostComponent[] components
    ) {
        if (components == null || components.length == 0) {
            return EMPTY_ARRAY;
        }
        TwItemCostComponent[] copy =
                new TwItemCostComponent[components.length];
        Set<String> itemIds = new HashSet<>();
        for (int index = 0; index < components.length; index++) {
            TwItemCostComponent component = components[index];
            if (component == null) {
                throw new IllegalArgumentException(
                        "Cost component at index " + index + " is null."
                );
            }
            component.validate();
            String itemId = component.getItemId();
            if (!itemIds.add(itemId)) {
                throw new IllegalArgumentException(
                        "Duplicate cost ItemId is not allowed: " + itemId
                );
            }
            copy[index] = component.copy();
        }
        return copy;
    }

    @Nonnull
    private static String normalizeItemId(@Nonnull String itemId) {
        return itemId.trim();
    }
}
