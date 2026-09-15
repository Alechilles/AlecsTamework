package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stores authoritative water charge state for feed trough block entities.
 */
public final class TameworkFeedTroughWaterChargesComponent implements Component<ChunkStore> {
    public static final String BASE_BLOCK_ID_TAG = "BaseBlockId";
    public static final String MAX_WATER_CHARGES_TAG = "MaxWaterCharges";
    public static final String FOOD_CAPACITY_TAG = "FoodCapacity";
    public static final String WATER_CHARGES_TAG = "WaterCharges";

    public static final String DEFAULT_BASE_BLOCK_ID = "Tw_Feed_Trough";
    public static final int DEFAULT_MAX_WATER_CHARGES = 200;
    public static final int DEFAULT_FOOD_CAPACITY = 5;

    public static final BuilderCodec<TameworkFeedTroughWaterChargesComponent> CODEC = BuilderCodec.builder(
            TameworkFeedTroughWaterChargesComponent.class,
            TameworkFeedTroughWaterChargesComponent::new
    ).append(
            new KeyedCodec<>(BASE_BLOCK_ID_TAG, Codec.STRING),
            TameworkFeedTroughWaterChargesComponent::setBaseBlockId,
            TameworkFeedTroughWaterChargesComponent::getBaseBlockId
    ).add().append(
            new KeyedCodec<>(MAX_WATER_CHARGES_TAG, Codec.INTEGER),
            TameworkFeedTroughWaterChargesComponent::setMaxWaterCharges,
            TameworkFeedTroughWaterChargesComponent::getMaxWaterCharges
    ).add().append(
            new KeyedCodec<>(FOOD_CAPACITY_TAG, Codec.INTEGER),
            TameworkFeedTroughWaterChargesComponent::setFoodCapacity,
            TameworkFeedTroughWaterChargesComponent::getFoodCapacity
    ).add().append(
            new KeyedCodec<>(WATER_CHARGES_TAG, Codec.INTEGER),
            // Decode order is not fixed: capacity may follow charges in a saved document.
            (component, charges) -> component.waterCharges = charges,
            TameworkFeedTroughWaterChargesComponent::getWaterCharges
    ).add().build();

    @Nonnull
    private String baseBlockId = DEFAULT_BASE_BLOCK_ID;
    private int maxWaterCharges = DEFAULT_MAX_WATER_CHARGES;
    private int foodCapacity = DEFAULT_FOOD_CAPACITY;
    private int waterCharges;

    public TameworkFeedTroughWaterChargesComponent() {
    }

    public TameworkFeedTroughWaterChargesComponent(int waterCharges) {
        setWaterCharges(waterCharges);
    }

    @Nullable
    public static ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getFeedTroughWaterChargesComponentType() : null;
    }

    @Nullable
    public static TameworkFeedTroughWaterChargesComponent resolve(@Nullable WorldChunk chunk,
                                                                  @Nullable Store<ChunkStore> chunkStore,
                                                                  int x,
                                                                  int y,
                                                                  int z) {
        if (chunk == null || chunkStore == null) {
            return null;
        }
        ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> componentType = getComponentType();
        if (componentType == null) {
            return null;
        }
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(x, y, z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        return chunkStore.getComponent(blockRef, componentType);
    }

    public int getWaterCharges() {
        return Math.max(0, Math.min(waterCharges, maxWaterCharges));
    }

    public void setWaterCharges(int waterCharges) {
        this.waterCharges = Math.max(0, Math.min(waterCharges, maxWaterCharges));
    }

    @Nonnull
    public String getBaseBlockId() {
        return baseBlockId;
    }

    public void setBaseBlockId(@Nullable String baseBlockId) {
        this.baseBlockId = baseBlockId == null || baseBlockId.isBlank()
                ? DEFAULT_BASE_BLOCK_ID
                : baseBlockId.trim();
    }

    public int getMaxWaterCharges() {
        return maxWaterCharges;
    }

    public void setMaxWaterCharges(int maxWaterCharges) {
        this.maxWaterCharges = maxWaterCharges > 0 ? maxWaterCharges : DEFAULT_MAX_WATER_CHARGES;
        waterCharges = Math.min(waterCharges, this.maxWaterCharges);
    }

    public int getFoodCapacity() {
        return foodCapacity;
    }

    public void setFoodCapacity(int foodCapacity) {
        this.foodCapacity = foodCapacity > 0 ? foodCapacity : DEFAULT_FOOD_CAPACITY;
    }

    @Override
    @Nonnull
    public TameworkFeedTroughWaterChargesComponent clone() {
        TameworkFeedTroughWaterChargesComponent copy = new TameworkFeedTroughWaterChargesComponent();
        copy.setBaseBlockId(baseBlockId);
        copy.setMaxWaterCharges(maxWaterCharges);
        copy.setFoodCapacity(foodCapacity);
        copy.setWaterCharges(waterCharges);
        return copy;
    }
}
