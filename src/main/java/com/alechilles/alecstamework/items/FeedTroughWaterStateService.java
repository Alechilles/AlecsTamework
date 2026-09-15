package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.components.TameworkFeedTroughWaterChargesComponent;
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

/** Handles configured water-charge persistence and visual-state sync for feed trough variants. */
public final class FeedTroughWaterStateService {
    private static final String WATER_STATE_TOKEN = "_State_Water";
    private static final String FOOD_STATE_TOKEN = "_State_Food";
    private static final String LEGACY_CHARGE_STORAGE_PREFIX = "tw_water_charges:";

    /** Kept for legacy callers and existing {@code Tw_Feed_Trough} assets. */
    public static final int MAX_WATER_CHARGES = TameworkFeedTroughWaterChargesComponent.DEFAULT_MAX_WATER_CHARGES;

    private FeedTroughWaterStateService() {
    }

    public static boolean isWaterTroughBlockId(@Nullable String normalizedBlockId) {
        return normalizedBlockId != null && !normalizedBlockId.isBlank()
                && normalizedBlockId.contains(WATER_STATE_TOKEN);
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
        String normalizedBlockId = normalizeId(blockType.getId());
        if (!isWaterTroughBlockId(normalizedBlockId)) {
            return false;
        }
        Object state = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        TroughVariant variant = resolveVariant(chunk, chunkStore, x, y, z, normalizedBlockId);
        return state != null && variant != null
                && resolveStoredOrInferredCharges(state, normalizedBlockId, chunk, chunkStore, x, y, z, variant, false) > 0;
    }

    public static boolean isWaterTroughBlockType(@Nullable BlockType blockType) {
        if (blockType == null) {
            return false;
        }
        String id = normalizeId(blockType.getId());
        if (isLegacyWaterTroughBlockId(id)) {
            return true;
        }
        var componentType = TameworkFeedTroughWaterChargesComponent.getComponentType();
        var template = blockType.getBlockEntity();
        var component = componentType != null && template != null ? template.getComponent(componentType) : null;
        return component != null && isWaterStateOf(id, normalizeBaseBlockId(component.getBaseBlockId()));
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
        String currentBlockId = normalizeId(currentType != null ? currentType.getId() : null);
        if (!isWaterTroughBlockId(currentBlockId)) {
            return false;
        }
        Object state = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        TroughVariant variant = resolveVariant(chunk, chunkStore, x, y, z, currentBlockId);
        if (state == null || variant == null || !setBlockType(chunk, x, y, z, variant.baseBlockId())) {
            return false;
        }
        Object updatedState = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        if (updatedState == null || !resizeContainer(updatedState, variant.foodCapacity())) {
            return false;
        }
        setStoredCharges(chunk, chunkStore, x, y, z, updatedState, variant, 0);
        return true;
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
        BlockType currentType = chunk.getBlockType(x, y, z);
        String currentBlockId = normalizeId(currentType != null ? currentType.getId() : null);
        if (state == null || !isWaterTroughBlockId(currentBlockId)) {
            return false;
        }
        TroughVariant variant = resolveVariant(chunk, chunkStore, x, y, z, currentBlockId);
        if (variant == null) {
            return false;
        }
        int charges = resolveStoredOrInferredCharges(
                state, currentBlockId, chunk, chunkStore, x, y, z, variant, true
        );
        if (charges <= 0) {
            return false;
        }
        int remainingCharges = charges - 1;
        String targetBlockId = resolveCanonicalWaterBlockIdForCharges(
                remainingCharges, variant.baseBlockId(), variant.maxWaterCharges()
        );
        if (!currentBlockId.equals(normalizeId(targetBlockId)) && !setBlockType(chunk, x, y, z, targetBlockId)) {
            return false;
        }
        Object updatedState = FeedTroughContainerCompat.resolveContainerState(chunk, chunkStore, x, y, z);
        if (updatedState != null) {
            setStoredCharges(chunk, chunkStore, x, y, z, updatedState, variant, remainingCharges);
        }
        return true;
    }

    @Nullable
    static TroughVariant resolveVariant(@Nullable WorldChunk chunk,
                                        @Nullable Store<ChunkStore> chunkStore,
                                        int x,
                                        int y,
                                        int z,
                                        @Nullable String normalizedBlockId) {
        TameworkFeedTroughWaterChargesComponent component =
                TameworkFeedTroughWaterChargesComponent.resolve(chunk, chunkStore, x, y, z);
        if (component != null) {
            String baseId = normalizeBaseBlockId(component.getBaseBlockId());
            if (normalizedBlockId == null || !(normalizedBlockId.equals(baseId)
                    || normalizedBlockId.startsWith(baseId + FOOD_STATE_TOKEN)
                    || isWaterStateOf(normalizedBlockId, baseId))) {
                return null;
            }
            return new TroughVariant(
                    normalizeBaseBlockId(component.getBaseBlockId()),
                    positiveOrDefault(component.getMaxWaterCharges(), MAX_WATER_CHARGES),
                    positiveOrDefault(component.getFoodCapacity(), TameworkFeedTroughWaterChargesComponent.DEFAULT_FOOD_CAPACITY)
            );
        }
        TroughVariant legacy = legacyVariant();
        return isFoodTroughBlockId(normalizedBlockId, legacy) || isLegacyWaterTroughBlockId(normalizedBlockId)
                ? legacy : null;
    }

    static int resolveStoredOrInferredCharges(@Nonnull Object state,
                                              @Nonnull String normalizedBlockId,
                                              @Nullable WorldChunk chunk,
                                              @Nullable Store<ChunkStore> chunkStore,
                                              int x,
                                              int y,
                                              int z,
                                              @Nonnull TroughVariant variant,
                                              boolean allowWriteBack) {
        TameworkFeedTroughWaterChargesComponent component =
                TameworkFeedTroughWaterChargesComponent.resolve(chunk, chunkStore, x, y, z);
        if (isFullWaterState(normalizedBlockId, variant)) {
            if (allowWriteBack) {
                setComponentCharges(chunk, chunkStore, x, y, z, variant.maxWaterCharges(), variant.maxWaterCharges());
            }
            return variant.maxWaterCharges();
        }
        if (component != null && component.getWaterCharges() > 0) {
            int clamped = clampCharges(component.getWaterCharges(), variant.maxWaterCharges());
            if (allowWriteBack && clamped != component.getWaterCharges()) {
                component.setWaterCharges(clamped);
            }
            return clamped;
        }
        int stored = parseStoredCharges(FeedTroughContainerCompat.getDroplist(state));
        if (stored >= 0) {
            int clamped = clampCharges(stored, variant.maxWaterCharges());
            if (allowWriteBack) {
                setStoredCharges(chunk, chunkStore, x, y, z, state, variant, clamped);
            }
            return clamped;
        }
        int inferred = inferChargesFromWaterBlockId(normalizedBlockId, variant.baseBlockId(), variant.maxWaterCharges());
        if (allowWriteBack && inferred > 0) {
            setComponentCharges(chunk, chunkStore, x, y, z, inferred, variant.maxWaterCharges());
        }
        return inferred;
    }

    static int inferChargesFromWaterBlockId(@Nullable String normalizedBlockId) {
        return inferChargesFromWaterBlockId(normalizedBlockId, TameworkFeedTroughWaterChargesComponent.DEFAULT_BASE_BLOCK_ID,
                MAX_WATER_CHARGES);
    }

    static int inferChargesFromWaterBlockId(@Nullable String normalizedBlockId,
                                            @Nonnull String baseBlockId,
                                            int maxWaterCharges) {
        if (!isWaterStateOf(normalizedBlockId, baseBlockId) || normalizedBlockId == null) {
            return 0;
        }
        int maximum = positiveOrDefault(maxWaterCharges, MAX_WATER_CHARGES);
        if (normalizedBlockId.equals(baseBlockId + WATER_STATE_TOKEN)
                || normalizedBlockId.endsWith(WATER_STATE_TOKEN + "_State_Full")) {
            return maximum;
        }
        int percent = statePercent(normalizedBlockId, WATER_STATE_TOKEN);
        return percent > 0 ? (int) ((long) maximum * percent / 100) : 0;
    }

    static String resolveCanonicalWaterBlockIdForCharges(int charges) {
        return resolveCanonicalWaterBlockIdForCharges(
                charges, TameworkFeedTroughWaterChargesComponent.DEFAULT_BASE_BLOCK_ID, MAX_WATER_CHARGES
        );
    }

    static String resolveCanonicalWaterBlockIdForCharges(int charges,
                                                          @Nonnull String baseBlockId,
                                                          int maxWaterCharges) {
        int maximum = positiveOrDefault(maxWaterCharges, MAX_WATER_CHARGES);
        int clamped = clampCharges(charges, maximum);
        if (clamped <= 0) {
            return baseBlockId;
        }
        if (clamped >= maximum) {
            return baseBlockId + WATER_STATE_TOKEN + "_State_Full";
        }
        int bucket = (int) Math.ceil(((double) clamped * 100.0 / maximum) / 10.0) * 10;
        return baseBlockId + WATER_STATE_TOKEN + "_State_" + Math.max(10, Math.min(90, bucket));
    }

    static String resolveCanonicalFoodBlockIdForPercent(@Nonnull TroughVariant variant, int fullnessPercent) {
        if (fullnessPercent <= 0) {
            return variant.baseBlockId();
        }
        if (fullnessPercent >= 100) {
            return variant.baseBlockId() + FOOD_STATE_TOKEN + "_State_Full";
        }
        int bucket = (int) Math.ceil(fullnessPercent / 10.0) * 10;
        return variant.baseBlockId() + FOOD_STATE_TOKEN + "_State_" + Math.max(10, Math.min(90, bucket));
    }

    static boolean isFoodTroughBlockId(@Nullable String normalizedBlockId, @Nonnull TroughVariant variant) {
        return normalizedBlockId != null && (normalizedBlockId.equals(variant.baseBlockId())
                || normalizedBlockId.startsWith(variant.baseBlockId() + FOOD_STATE_TOKEN));
    }

    static boolean resizeContainer(@Nonnull Object state, int targetCapacity) {
        int capacity = positiveOrDefault(targetCapacity, TameworkFeedTroughWaterChargesComponent.DEFAULT_FOOD_CAPACITY);
        if (capacity > Short.MAX_VALUE) {
            return false;
        }
        ItemContainer existing = FeedTroughContainerCompat.getItemContainer(state);
        if (existing == null || existing.getCapacity() == (short) capacity) {
            return existing != null;
        }
        List<ItemStack> remainder = new ObjectArrayList<>();
        ItemContainer resized = ItemContainer.ensureContainerCapacity(existing, (short) capacity, SimpleItemContainer::new, remainder);
        if (!(resized instanceof SimpleItemContainer simpleResized)) {
            return false;
        }
        if (resized != existing) {
            bindItemChangeListenerIfSupported(state, simpleResized);
            return FeedTroughContainerCompat.setItemContainer(state, simpleResized);
        }
        return true;
    }

    private static boolean setBlockType(@Nonnull WorldChunk chunk, int x, int y, int z, @Nonnull String blockId) {
        BlockType targetType = resolveVariantBlockType(blockId);
        if (targetType == null) {
            return false;
        }
        int targetIndex = BlockType.getAssetMap().getIndex(targetType.getId());
        if (targetIndex == Integer.MIN_VALUE) {
            return false;
        }
        int rotation = chunk.getRotationIndex(x, y, z);
        chunk.setBlock(x, y, z, targetIndex, targetType, rotation, 0, 198);
        return true;
    }

    private static void setStoredCharges(@Nullable WorldChunk chunk,
                                         @Nullable Store<ChunkStore> chunkStore,
                                         int x,
                                         int y,
                                         int z,
                                         @Nonnull Object state,
                                         @Nonnull TroughVariant variant,
                                         int charges) {
        setComponentCharges(chunk, chunkStore, x, y, z, charges, variant.maxWaterCharges());
        FeedTroughContainerCompat.setDroplist(state, null);
    }

    private static void setComponentCharges(@Nullable WorldChunk chunk,
                                            @Nullable Store<ChunkStore> chunkStore,
                                            int x,
                                            int y,
                                            int z,
                                            int charges,
                                            int maxWaterCharges) {
        TameworkFeedTroughWaterChargesComponent component =
                TameworkFeedTroughWaterChargesComponent.resolve(chunk, chunkStore, x, y, z);
        if (component != null) {
            component.setWaterCharges(clampCharges(charges, maxWaterCharges));
        }
    }

    private static int parseStoredCharges(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank() || !encoded.trim().startsWith(LEGACY_CHARGE_STORAGE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(encoded.trim().substring(LEGACY_CHARGE_STORAGE_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int statePercent(@Nonnull String blockId, @Nonnull String stateToken) {
        int tokenIndex = blockId.lastIndexOf(stateToken + "_State_");
        if (tokenIndex < 0) {
            return -1;
        }
        String value = blockId.substring(tokenIndex + stateToken.length() + "_State_".length());
        try {
            int percent = Integer.parseInt(value);
            return percent >= 10 && percent <= 90 && percent % 10 == 0 ? percent : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isFullWaterState(@Nonnull String blockId, @Nonnull TroughVariant variant) {
        return blockId.equals(variant.baseBlockId() + WATER_STATE_TOKEN)
                || blockId.equals(variant.baseBlockId() + WATER_STATE_TOKEN + "_State_Full");
    }

    static boolean isWaterStateOf(@Nullable String id, @Nonnull String baseId) {
        return id != null && (id.equals(baseId + WATER_STATE_TOKEN)
                || id.equals(baseId + WATER_STATE_TOKEN + "_State_Full")
                || (id.startsWith(baseId + WATER_STATE_TOKEN + "_State_")
                    && statePercent(id, WATER_STATE_TOKEN) > 0));
    }

    private static boolean isLegacyWaterTroughBlockId(@Nullable String blockId) {
        return blockId != null && blockId.startsWith(TameworkFeedTroughWaterChargesComponent.DEFAULT_BASE_BLOCK_ID + WATER_STATE_TOKEN);
    }

    @Nonnull
    private static TroughVariant legacyVariant() {
        return new TroughVariant(
                TameworkFeedTroughWaterChargesComponent.DEFAULT_BASE_BLOCK_ID,
                MAX_WATER_CHARGES,
                TameworkFeedTroughWaterChargesComponent.DEFAULT_FOOD_CAPACITY
        );
    }

    private static int clampCharges(int charges, int maximum) {
        return Math.max(0, Math.min(charges, positiveOrDefault(maximum, MAX_WATER_CHARGES)));
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @Nonnull
    private static String normalizeBaseBlockId(@Nullable String baseBlockId) {
        String normalized = normalizeId(baseBlockId);
        return normalized.isBlank() ? TameworkFeedTroughWaterChargesComponent.DEFAULT_BASE_BLOCK_ID : normalized;
    }

    @Nullable
    private static BlockType resolveVariantBlockType(@Nonnull String blockId) {
        BlockType exact = BlockType.getAssetMap().getAsset(blockId);
        if (exact != null) {
            return exact;
        }
        BlockType oneStar = BlockType.getAssetMap().getAsset("*" + blockId);
        return oneStar != null ? oneStar : BlockType.getAssetMap().getAsset("**" + blockId);
    }

    private static void bindItemChangeListenerIfSupported(@Nonnull Object state, @Nonnull SimpleItemContainer container) {
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

    @Nullable
    private static Method findMethod(@Nonnull Class<?> type, @Nonnull String methodName, @Nonnull Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nonnull
    static String normalizeId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String normalized = id.trim();
        while (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    record TroughVariant(@Nonnull String baseBlockId, int maxWaterCharges, int foodCapacity) {
    }
}
