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
    public static final String WATER_CHARGES_TAG = "WaterCharges";

    public static final BuilderCodec<TameworkFeedTroughWaterChargesComponent> CODEC = BuilderCodec.builder(
            TameworkFeedTroughWaterChargesComponent.class,
            TameworkFeedTroughWaterChargesComponent::new
    ).append(
            new KeyedCodec<>(WATER_CHARGES_TAG, Codec.INTEGER),
            TameworkFeedTroughWaterChargesComponent::setWaterCharges,
            TameworkFeedTroughWaterChargesComponent::getWaterCharges
    ).add().build();

    private int waterCharges;

    public TameworkFeedTroughWaterChargesComponent() {
    }

    public TameworkFeedTroughWaterChargesComponent(int waterCharges) {
        this.waterCharges = waterCharges;
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
        return waterCharges;
    }

    public void setWaterCharges(int waterCharges) {
        this.waterCharges = waterCharges;
    }

    @Override
    @Nonnull
    public TameworkFeedTroughWaterChargesComponent clone() {
        return new TameworkFeedTroughWaterChargesComponent(waterCharges);
    }
}
