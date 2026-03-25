package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.math.util.ChunkUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-driven trough food visual sync.
 *
 * <p>Registers one inventory-change listener per loaded trough container state and updates visual state only when
 * the trough inventory actually changes. If any trough container window is open, visual sync is deferred until the
 * window closes so players do not get kicked out of the UI while moving items.
 */
public final class FeedTroughFoodStateSyncSystem extends RefSystem<ChunkStore> {
    private static final int DEFAULT_EMPTY_SLOT_MAX_STACK = 100;

    private static final String MODERN_BLOCK_MODULE_CLASS =
            "com.hypixel.hytale.server.core.modules.block.BlockModule";
    private static final String MODERN_ITEM_CONTAINER_BLOCK_CLASS =
            "com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock";

    private static final String BASE_BLOCK_ID = "Tw_Feed_Trough";
    private static final String FOOD_STATE_PREFIX = "Tw_Feed_Trough_State_Food";
    private static final String WATER_STATE_TOKEN = "_State_Water";

    private static final String FOOD_STATE_FULL_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_Full";
    private static final String FOOD_STATE_90_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_90";
    private static final String FOOD_STATE_80_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_80";
    private static final String FOOD_STATE_70_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_70";
    private static final String FOOD_STATE_60_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_60";
    private static final String FOOD_STATE_50_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_50";
    private static final String FOOD_STATE_40_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_40";
    private static final String FOOD_STATE_30_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_30";
    private static final String FOOD_STATE_20_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_20";
    private static final String FOOD_STATE_10_BLOCK_ID = "Tw_Feed_Trough_State_Food_State_10";

    private final Map<Ref<ChunkStore>, EventRegistration<Void, ItemContainer.ItemContainerChangeEvent>> registrations =
            new ConcurrentHashMap<>();
    private final Map<Ref<ChunkStore>, Map<UUID, EventRegistration<Void, Window.WindowCloseEvent>>> closeRegistrations =
            new ConcurrentHashMap<>();
    private final Map<Ref<ChunkStore>, Store<ChunkStore>> storesByReference = new ConcurrentHashMap<>();

    @Nullable
    private volatile ComponentType<ChunkStore, ?> itemContainerComponentType;
    @Nullable
    private volatile ComponentType<ChunkStore, ?> blockStateInfoComponentType;
    private volatile boolean apiResolved;

    @Override
    public Query<ChunkStore> getQuery() {
        ComponentType<ChunkStore, ?> type = resolveItemContainerComponentType();
        if (type instanceof Query<?>) {
            @SuppressWarnings("unchecked")
            Query<ChunkStore> query = (Query<ChunkStore>) type;
            return query;
        }
        return Query.any();
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<ChunkStore> store,
                              @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        unregister(ref);
        storesByReference.put(ref, store);
        ComponentType<ChunkStore, ?> type = resolveItemContainerComponentType();
        if (type == null) {
            return;
        }
        Object state = commandBuffer.getComponent(ref, castComponentType(type));
        if (state == null) {
            return;
        }
        BlockLocation location = resolveBlockLocation(ref, state);
        if (location == null) {
            return;
        }
        BlockType blockType = location.chunk.getBlockType(location.x, location.y, location.z);
        if (!isFeedTroughFoodSyncTarget(normalizeId(blockType != null ? blockType.getId() : null))) {
            return;
        }
        ItemContainer container = resolveItemContainer(state);
        if (container == null) {
            return;
        }
        EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> registration =
                container.registerChangeEvent(EventPriority.LAST, event -> onContainerChanged(ref, state));
        registrations.put(ref, registration);
        onContainerChanged(ref, state);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<ChunkStore> store,
                               @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        unregister(ref);
    }

    private void unregister(@Nonnull Ref<ChunkStore> ref) {
        EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> registration = registrations.remove(ref);
        if (registration != null) {
            registration.unregister();
        }
        unregisterCloseEvents(ref);
        storesByReference.remove(ref);
    }

    private void onContainerChanged(@Nonnull Ref<ChunkStore> ref, @Nullable Object state) {
        if (state == null) {
            unregisterCloseEvents(ref);
            return;
        }
        Map<UUID, ContainerBlockWindow> windows = resolveWindows(state);
        if (windows == null || windows.isEmpty()) {
            unregisterCloseEvents(ref);
            syncStateVisual(ref, state);
            return;
        }
        registerWindowCloseSync(ref, state, windows);
    }

    private void registerWindowCloseSync(@Nonnull Ref<ChunkStore> ref,
                                         @Nonnull Object state,
                                         @Nonnull Map<UUID, ContainerBlockWindow> windows) {
        Map<UUID, EventRegistration<Void, Window.WindowCloseEvent>> trackedRegistrations =
                closeRegistrations.computeIfAbsent(ref, ignored -> new ConcurrentHashMap<>());

        trackedRegistrations.entrySet().removeIf(entry -> {
            if (windows.containsKey(entry.getKey())) {
                return false;
            }
            EventRegistration<Void, Window.WindowCloseEvent> staleRegistration = entry.getValue();
            if (staleRegistration != null) {
                staleRegistration.unregister();
            }
            return true;
        });

        for (Map.Entry<UUID, ContainerBlockWindow> entry : windows.entrySet()) {
            UUID windowOwner = entry.getKey();
            ContainerBlockWindow window = entry.getValue();
            if (windowOwner == null || window == null || trackedRegistrations.containsKey(windowOwner)) {
                continue;
            }
            EventRegistration<Void, Window.WindowCloseEvent> closeRegistration =
                    window.registerCloseEvent(EventPriority.LAST, closeEvent -> {
                        Map<UUID, EventRegistration<Void, Window.WindowCloseEvent>> registrationsByWindow =
                                closeRegistrations.get(ref);
                        if (registrationsByWindow != null) {
                            registrationsByWindow.remove(windowOwner);
                            if (registrationsByWindow.isEmpty()) {
                                closeRegistrations.remove(ref, registrationsByWindow);
                            }
                        }
                        if (resolveWindows(state).isEmpty()) {
                            syncStateVisual(ref, state);
                        }
                    });
            trackedRegistrations.put(windowOwner, closeRegistration);
        }
    }

    private void unregisterCloseEvents(@Nonnull Ref<ChunkStore> ref) {
        Map<UUID, EventRegistration<Void, Window.WindowCloseEvent>> registrationsByWindow = closeRegistrations.remove(ref);
        if (registrationsByWindow == null || registrationsByWindow.isEmpty()) {
            return;
        }
        for (EventRegistration<Void, Window.WindowCloseEvent> registration : registrationsByWindow.values()) {
            if (registration != null) {
                registration.unregister();
            }
        }
    }

    private void syncStateVisual(@Nonnull Ref<ChunkStore> ref, @Nullable Object state) {
        if (state == null) {
            return;
        }
        BlockLocation location = resolveBlockLocation(ref, state);
        if (location == null) {
            return;
        }
        WorldChunk chunk = location.chunk;
        int x = location.x;
        int y = location.y;
        int z = location.z;
        BlockType currentType = chunk.getBlockType(x, y, z);
        if (currentType == null) {
            return;
        }
        String normalizedCurrentId = normalizeId(currentType.getId());
        if (!isFeedTroughFoodSyncTarget(normalizedCurrentId)) {
            return;
        }
        BlockType targetType = resolveTargetFoodBlockType(resolveItemContainer(state));
        if (targetType == null) {
            return;
        }
        String normalizedTargetId = normalizeId(targetType.getId());
        if (normalizedCurrentId.equals(normalizedTargetId)) {
            return;
        }
        int targetIndex = BlockType.getAssetMap().getIndex(targetType.getId());
        if (targetIndex == Integer.MIN_VALUE) {
            return;
        }
        int currentRotation = chunk.getRotationIndex(x, y, z);
        chunk.setBlock(x, y, z, targetIndex, targetType, currentRotation, 0, 198);
    }

    @Nullable
    private BlockType resolveTargetFoodBlockType(@Nullable ItemContainer container) {
        if (container == null) {
            return resolveVariantBlockType(BASE_BLOCK_ID);
        }
        short capacity = container.getCapacity();
        if (capacity <= 0) {
            return resolveVariantBlockType(BASE_BLOCK_ID);
        }

        long totalCapacity = 0L;
        long totalQuantity = 0L;
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = container.getItemStack(slot);
            int slotCapacity = resolveSlotCapacity(stack);
            totalCapacity += slotCapacity;
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            int quantity = Math.max(0, stack.getQuantity());
            totalQuantity += Math.min(quantity, slotCapacity);
        }

        if (totalCapacity <= 0L || totalQuantity <= 0L) {
            return resolveVariantBlockType(BASE_BLOCK_ID);
        }
        if (totalQuantity >= totalCapacity) {
            return resolveVariantBlockType(FOOD_STATE_FULL_BLOCK_ID);
        }

        double fullnessPercent = ((double) totalQuantity * 100.0) / (double) totalCapacity;
        int bucket = (int) Math.ceil(fullnessPercent / 10.0) * 10;
        int clampedBucket = Math.max(10, Math.min(90, bucket));
        return switch (clampedBucket) {
            case 90 -> resolveVariantBlockType(FOOD_STATE_90_BLOCK_ID);
            case 80 -> resolveVariantBlockType(FOOD_STATE_80_BLOCK_ID);
            case 70 -> resolveVariantBlockType(FOOD_STATE_70_BLOCK_ID);
            case 60 -> resolveVariantBlockType(FOOD_STATE_60_BLOCK_ID);
            case 50 -> resolveVariantBlockType(FOOD_STATE_50_BLOCK_ID);
            case 40 -> resolveVariantBlockType(FOOD_STATE_40_BLOCK_ID);
            case 30 -> resolveVariantBlockType(FOOD_STATE_30_BLOCK_ID);
            case 20 -> resolveVariantBlockType(FOOD_STATE_20_BLOCK_ID);
            default -> resolveVariantBlockType(FOOD_STATE_10_BLOCK_ID);
        };
    }

    private int resolveSlotCapacity(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return DEFAULT_EMPTY_SLOT_MAX_STACK;
        }
        Item item = stack.getItem();
        if (item == null) {
            return DEFAULT_EMPTY_SLOT_MAX_STACK;
        }
        int maxStack = item.getMaxStack();
        return maxStack > 0 ? maxStack : DEFAULT_EMPTY_SLOT_MAX_STACK;
    }

    private boolean isFeedTroughFoodSyncTarget(@Nullable String normalizedBlockId) {
        if (normalizedBlockId == null || normalizedBlockId.isBlank()) {
            return false;
        }
        if (!normalizedBlockId.startsWith(BASE_BLOCK_ID)) {
            return false;
        }
        if (normalizedBlockId.contains(WATER_STATE_TOKEN)) {
            return false;
        }
        return normalizedBlockId.equals(BASE_BLOCK_ID) || normalizedBlockId.startsWith(FOOD_STATE_PREFIX);
    }

    @Nullable
    private BlockType resolveVariantBlockType(@Nonnull String canonicalId) {
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
    private String normalizeId(@Nullable String id) {
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
    private ComponentType<ChunkStore, ?> resolveItemContainerComponentType() {
        if (apiResolved) {
            return itemContainerComponentType;
        }
        synchronized (this) {
            if (apiResolved) {
                return itemContainerComponentType;
            }
            ComponentType<ChunkStore, ?> modernType = resolveModernItemContainerComponentType();
            if (modernType != null) {
                itemContainerComponentType = modernType;
                blockStateInfoComponentType = resolveModernBlockStateInfoComponentType();
                apiResolved = true;
                return modernType;
            }
            apiResolved = true;
            return null;
        }
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveModernItemContainerComponentType() {
        try {
            Class<?> itemContainerBlockClass = Class.forName(MODERN_ITEM_CONTAINER_BLOCK_CLASS);
            Method getComponentTypeMethod = itemContainerBlockClass.getMethod("getComponentType");
            Object componentType = getComponentTypeMethod.invoke(null);
            if (componentType instanceof ComponentType<?, ?> resolvedType) {
                return castComponentTypeUnchecked(resolvedType);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveModernBlockStateInfoComponentType() {
        if (blockStateInfoComponentType != null) {
            return blockStateInfoComponentType;
        }
        try {
            Class<?> blockModuleClass = Class.forName(MODERN_BLOCK_MODULE_CLASS);
            Method getMethod = blockModuleClass.getMethod("get");
            Object module = getMethod.invoke(null);
            Method getBlockStateInfoTypeMethod = blockModuleClass.getMethod("getBlockStateInfoComponentType");
            Object componentType = getBlockStateInfoTypeMethod.invoke(module);
            if (componentType instanceof ComponentType<?, ?> resolvedType) {
                blockStateInfoComponentType = castComponentTypeUnchecked(resolvedType);
                return blockStateInfoComponentType;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @Nullable
    private ItemContainer resolveItemContainer(@Nullable Object state) {
        return FeedTroughContainerCompat.getItemContainer(state);
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    private Map<UUID, ContainerBlockWindow> resolveWindows(@Nullable Object state) {
        if (state == null) {
            return Map.of();
        }
        Object windows = invokeNoArg(state, "getWindows");
        if (windows instanceof Map<?, ?> map) {
            return (Map<UUID, ContainerBlockWindow>) map;
        }
        return Map.of();
    }

    @Nullable
    private BlockLocation resolveBlockLocation(@Nonnull Ref<ChunkStore> ref, @Nullable Object state) {
        return state == null ? null : resolveModernBlockLocation(ref);
    }

    @Nullable
    private BlockLocation resolveModernBlockLocation(@Nonnull Ref<ChunkStore> ref) {
        Store<ChunkStore> store = storesByReference.get(ref);
        if (store == null) {
            return null;
        }
        ComponentType<ChunkStore, ?> infoType = resolveModernBlockStateInfoComponentType();
        if (infoType == null) {
            return null;
        }
        Object info = store.getComponent(ref, castComponentType(infoType));
        if (info == null) {
            return null;
        }
        Object chunkRefObject = invokeNoArg(info, "getChunkRef");
        if (!(chunkRefObject instanceof Ref<?> rawChunkRef)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Ref<ChunkStore> chunkRef = (Ref<ChunkStore>) rawChunkRef;
        WorldChunk chunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
        if (chunk == null) {
            return null;
        }
        Integer indexValue = invokeIntMethod(info, "getIndex");
        if (indexValue == null) {
            return null;
        }
        int localX = ChunkUtil.xFromBlockInColumn(indexValue);
        int y = ChunkUtil.yFromBlockInColumn(indexValue);
        int localZ = ChunkUtil.zFromBlockInColumn(indexValue);
        int x = ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX);
        int z = ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ);
        return new BlockLocation(chunk, x, y, z);
    }

    @Nullable
    private Object invokeNoArg(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private Integer invokeIntMethod(@Nonnull Object target, @Nonnull String methodName) {
        Object value = invokeNoArg(target, methodName);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T extends Component<ChunkStore>> ComponentType<ChunkStore, T> castComponentType(
            @Nonnull ComponentType<ChunkStore, ?> type
    ) {
        return (ComponentType<ChunkStore, T>) type;
    }

    @SuppressWarnings("unchecked")
    private ComponentType<ChunkStore, ? extends Component<ChunkStore>> castComponentTypeUnchecked(
            @Nonnull ComponentType<?, ?> type
    ) {
        return (ComponentType<ChunkStore, ? extends Component<ChunkStore>>) type;
    }

    private record BlockLocation(@Nonnull WorldChunk chunk, int x, int y, int z) {
    }
}
