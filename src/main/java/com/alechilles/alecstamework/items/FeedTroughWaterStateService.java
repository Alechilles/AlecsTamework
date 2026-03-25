package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Handles water-charge persistence and visual-state sync for feed trough water variants.
 */
public final class FeedTroughWaterStateService {
    private static final String BASE_BLOCK_ID = "Tw_Feed_Trough";
    private static final String WATER_STATE_PREFIX = "Tw_Feed_Trough_State_Water";
    private static final String WATER_STATE_TOKEN = "_State_Water";

    private static final String WATER_STATE_FULL_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_Full";
    private static final String WATER_STATE_90_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_90";
    private static final String WATER_STATE_80_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_80";
    private static final String WATER_STATE_70_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_70";
    private static final String WATER_STATE_60_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_60";
    private static final String WATER_STATE_50_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_50";
    private static final String WATER_STATE_40_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_40";
    private static final String WATER_STATE_30_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_30";
    private static final String WATER_STATE_20_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_20";
    private static final String WATER_STATE_10_BLOCK_ID = "Tw_Feed_Trough_State_Water_State_10";

    private static final String CHARGE_STORAGE_PREFIX = "tw_water_charges:";

    public static final int MAX_WATER_CHARGES = 200;

    private FeedTroughWaterStateService() {
    }

    public static boolean isWaterTroughBlockId(@Nullable String normalizedBlockId) {
        if (normalizedBlockId == null || normalizedBlockId.isBlank()) {
            return false;
        }
        if (!normalizedBlockId.startsWith(BASE_BLOCK_ID)) {
            return false;
        }
        return normalizedBlockId.contains(WATER_STATE_TOKEN);
    }

    public static boolean hasConsumableWater(@Nullable WorldChunk chunk,
                                             @Nullable Store<ChunkStore> chunkStore,
                                             int x,
                                             int y,
                                             int z,
                                             @Nullable BlockType blockType) {
        if (chunk == null || blockType == null) {
            return false;
        }
        Object state = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        if (state == null) {
            return false;
        }
        String normalizedBlockId = normalizeId(blockType.getId());
        if (!isWaterTroughBlockId(normalizedBlockId)) {
            return false;
        }
        return resolveStoredOrInferredCharges(state, normalizedBlockId) > 0;
    }

    public static boolean isWaterTroughBlockType(@Nullable BlockType blockType) {
        if (blockType == null) {
            return false;
        }
        return isWaterTroughBlockId(normalizeId(blockType.getId()));
    }

    public static boolean clearStoredCharges(@Nullable WorldChunk chunk,
                                             @Nullable Store<ChunkStore> chunkStore,
                                             int x,
                                             int y,
                                             int z) {
        if (chunk == null) {
            return false;
        }
        BlockType currentType = chunk.getBlockType(x, y, z);
        if (!isWaterTroughBlockType(currentType)) {
            return false;
        }
        BlockType baseType = resolveVariantBlockType(BASE_BLOCK_ID);
        if (baseType == null) {
            return false;
        }
        int baseIndex = BlockType.getAssetMap().getIndex(baseType.getId());
        if (baseIndex == Integer.MIN_VALUE) {
            return false;
        }
        int currentRotation = chunk.getRotationIndex(x, y, z);
        chunk.setBlock(x, y, z, baseIndex, baseType, currentRotation, 0, 198);

        Object updatedState = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        if (updatedState == null) {
            return false;
        }
        if (!resizeContainerToBaseCapacity(updatedState)) {
            return false;
        }
        return FeedTroughContainerCompat.setDroplist(updatedState, null);
    }

    public static boolean consumeSingleCharge(@Nullable WorldChunk chunk,
                                              @Nullable Store<ChunkStore> chunkStore,
                                              int x,
                                              int y,
                                              int z) {
        if (chunk == null) {
            return false;
        }
        Object state = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        if (state == null) {
            return false;
        }
        BlockType currentType = chunk.getBlockType(x, y, z);
        if (currentType == null) {
            return false;
        }
        String normalizedCurrentId = normalizeId(currentType.getId());
        if (!isWaterTroughBlockId(normalizedCurrentId)) {
            return false;
        }

        int charges = resolveStoredOrInferredCharges(state, normalizedCurrentId);
        if (charges <= 0) {
            return false;
        }
        int remainingCharges = charges - 1;
        String targetCanonicalId = resolveCanonicalWaterBlockIdForCharges(remainingCharges);
        BlockType targetType = resolveVariantBlockType(targetCanonicalId);
        if (targetType == null) {
            return false;
        }

        String normalizedTargetId = normalizeId(targetType.getId());
        if (!normalizedCurrentId.equals(normalizedTargetId)) {
            int targetIndex = BlockType.getAssetMap().getIndex(targetType.getId());
            if (targetIndex == Integer.MIN_VALUE) {
                return false;
            }
            int currentRotation = chunk.getRotationIndex(x, y, z);
            chunk.setBlock(x, y, z, targetIndex, targetType, currentRotation, 0, 198);
        }

        Object updatedState = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        if (updatedState != null) {
            setStoredCharges(updatedState, remainingCharges);
        }
        return true;
    }

    static int resolveStoredOrInferredCharges(@Nonnull Object state,
                                              @Nonnull String normalizedBlockId) {
        String encoded = FeedTroughContainerCompat.getDroplist(state);
        int stored = parseStoredCharges(encoded);
        if (stored >= 0) {
            return clampCharges(stored);
        }
        return inferChargesFromWaterBlockId(normalizedBlockId);
    }

    static int inferChargesFromWaterBlockId(@Nullable String normalizedBlockId) {
        if (!isWaterTroughBlockId(normalizedBlockId)) {
            return 0;
        }
        if (normalizedBlockId == null) {
            return 0;
        }
        if (normalizedBlockId.equals(WATER_STATE_FULL_BLOCK_ID) || normalizedBlockId.equals(WATER_STATE_PREFIX)) {
            return MAX_WATER_CHARGES;
        }
        if (normalizedBlockId.endsWith("_State_90")) {
            return 180;
        }
        if (normalizedBlockId.endsWith("_State_80")) {
            return 160;
        }
        if (normalizedBlockId.endsWith("_State_70")) {
            return 140;
        }
        if (normalizedBlockId.endsWith("_State_60")) {
            return 120;
        }
        if (normalizedBlockId.endsWith("_State_50")) {
            return 100;
        }
        if (normalizedBlockId.endsWith("_State_40")) {
            return 80;
        }
        if (normalizedBlockId.endsWith("_State_30")) {
            return 60;
        }
        if (normalizedBlockId.endsWith("_State_20")) {
            return 40;
        }
        if (normalizedBlockId.endsWith("_State_10")) {
            return 20;
        }
        return MAX_WATER_CHARGES;
    }

    private static boolean resizeContainerToBaseCapacity(@Nonnull Object state) {
        short targetCapacity = resolveBaseContainerCapacity();
        if (targetCapacity <= 0) {
            return false;
        }
        ItemContainer existing = FeedTroughContainerCompat.getItemContainer(state);
        if (existing == null) {
            return false;
        }
        if (existing.getCapacity() == targetCapacity) {
            return true;
        }

        List<ItemStack> remainder = new ObjectArrayList<>();
        ItemContainer resized = ItemContainer.ensureContainerCapacity(
                existing,
                targetCapacity,
                SimpleItemContainer::new,
                remainder
        );
        if (!(resized instanceof SimpleItemContainer simpleResized)) {
            return false;
        }
        if (resized != existing) {
            bindItemChangeListenerIfSupported(state, simpleResized);
            if (!FeedTroughContainerCompat.setItemContainer(state, simpleResized)) {
                return false;
            }
        }
        return true;
    }

    private static void bindItemChangeListenerIfSupported(@Nonnull Object state,
                                                           @Nonnull SimpleItemContainer container) {
        Method onItemChange = findMethod(state.getClass(), "onItemChange", ItemContainer.ItemContainerChangeEvent.class);
        if (onItemChange == null) {
            return;
        }
        container.registerChangeEvent(EventPriority.LAST, event -> {
            try {
                onItemChange.invoke(state, event);
            } catch (ReflectiveOperationException ignored) {
            }
        });
    }

    private static short resolveBaseContainerCapacity() {
        BlockType baseType = resolveVariantBlockType(BASE_BLOCK_ID);
        if (baseType == null) {
            return 5;
        }
        Object stateData = invokeNoArg(baseType, "getState");
        Integer configuredCapacity = invokeIntNoArg(stateData, "getCapacity");
        if (configuredCapacity != null && configuredCapacity > 0 && configuredCapacity <= Short.MAX_VALUE) {
            return configuredCapacity.shortValue();
        }
        return 5;
    }

    static String resolveCanonicalWaterBlockIdForCharges(int charges) {
        int clampedCharges = clampCharges(charges);
        if (clampedCharges <= 0) {
            return BASE_BLOCK_ID;
        }
        if (clampedCharges >= MAX_WATER_CHARGES) {
            return WATER_STATE_FULL_BLOCK_ID;
        }
        double fullnessPercent = ((double) clampedCharges * 100.0) / (double) MAX_WATER_CHARGES;
        int bucket = (int) Math.ceil(fullnessPercent / 10.0) * 10;
        int clampedBucket = Math.max(10, Math.min(90, bucket));
        return switch (clampedBucket) {
            case 90 -> WATER_STATE_90_BLOCK_ID;
            case 80 -> WATER_STATE_80_BLOCK_ID;
            case 70 -> WATER_STATE_70_BLOCK_ID;
            case 60 -> WATER_STATE_60_BLOCK_ID;
            case 50 -> WATER_STATE_50_BLOCK_ID;
            case 40 -> WATER_STATE_40_BLOCK_ID;
            case 30 -> WATER_STATE_30_BLOCK_ID;
            case 20 -> WATER_STATE_20_BLOCK_ID;
            default -> WATER_STATE_10_BLOCK_ID;
        };
    }

    private static void setStoredCharges(@Nonnull Object state, int charges) {
        int clampedCharges = clampCharges(charges);
        if (clampedCharges <= 0) {
            FeedTroughContainerCompat.setDroplist(state, null);
            return;
        }
        FeedTroughContainerCompat.setDroplist(state, CHARGE_STORAGE_PREFIX + clampedCharges);
    }

    private static int parseStoredCharges(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return -1;
        }
        String trimmed = encoded.trim();
        if (!trimmed.startsWith(CHARGE_STORAGE_PREFIX)) {
            return -1;
        }
        String value = trimmed.substring(CHARGE_STORAGE_PREFIX.length());
        if (value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int clampCharges(int charges) {
        return Math.max(0, Math.min(MAX_WATER_CHARGES, charges));
    }

    @Nullable
    private static BlockType resolveVariantBlockType(@Nonnull String canonicalId) {
        BlockType exact = BlockType.getAssetMap().getAsset(canonicalId);
        if (exact != null) {
            return exact;
        }
        BlockType oneStar = BlockType.getAssetMap().getAsset("*" + canonicalId);
        if (oneStar != null) {
            return oneStar;
        }
        return BlockType.getAssetMap().getAsset("**" + canonicalId);
    }

    @Nonnull
    private static String normalizeId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String normalized = id.trim();
        while (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    @Nullable
    private static Method findMethod(@Nonnull Class<?> type,
                                     @Nonnull String methodName,
                                     @Nonnull Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static Object invokeNoArg(@Nullable Object target, @Nonnull String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer invokeIntNoArg(@Nullable Object target, @Nonnull String methodName) {
        Object value = invokeNoArg(target, methodName);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
