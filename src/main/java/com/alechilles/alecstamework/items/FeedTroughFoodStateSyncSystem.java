package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.compat.HytaleBlockStateAccess;
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
        if (!isFeedTroughFoodSyncTarget(
                state,
                location,
                normalizeId(blockType != null ? blockType.getId() : null),
                store
        )) {
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
        Store<ChunkStore> store = storesByReference.get(ref);
        FeedTroughWaterStateService.TroughVariant variant = FeedTroughWaterStateService.resolveVariant(
                chunk, store, x, y, z, normalizedCurrentId
        );
        if (variant == null || !FeedTroughWaterStateService.isFoodTroughBlockId(normalizedCurrentId, variant)) {
            return;
        }
        if (!FeedTroughWaterStateService.resizeContainer(state, variant.foodCapacity())) {
            return;
        }
        BlockType targetType = resolveTargetFoodBlockType(resolveItemContainer(state), variant);
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
    private BlockType resolveTargetFoodBlockType(@Nullable ItemContainer container,
                                                 @Nonnull FeedTroughWaterStateService.TroughVariant variant) {
        if (container == null) {
            return resolveVariantBlockType(FeedTroughWaterStateService.resolveCanonicalFoodBlockIdForPercent(variant, 0));
        }
        short capacity = container.getCapacity();
        if (capacity <= 0) {
            return resolveVariantBlockType(FeedTroughWaterStateService.resolveCanonicalFoodBlockIdForPercent(variant, 0));
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
            return resolveVariantBlockType(FeedTroughWaterStateService.resolveCanonicalFoodBlockIdForPercent(variant, 0));
        }

        int fullnessPercent = (int) Math.ceil((double) totalQuantity * 100.0 / totalCapacity);
        return resolveVariantBlockType(FeedTroughWaterStateService.resolveCanonicalFoodBlockIdForPercent(
                variant, fullnessPercent
        ));
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

    private boolean isFeedTroughFoodSyncTarget(@Nullable Object state,
                                                @Nonnull BlockLocation location,
                                                @Nullable String normalizedBlockId,
                                                @Nonnull Store<ChunkStore> store) {
        FeedTroughWaterStateService.TroughVariant variant = FeedTroughWaterStateService.resolveVariant(
                location.chunk, store, location.x, location.y, location.z, normalizedBlockId
        );
        return state != null && variant != null
                && FeedTroughWaterStateService.isFoodTroughBlockId(normalizedBlockId, variant);
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
        HytaleBlockStateAccess.BlockLocation resolved =
                HytaleBlockStateAccess.resolve(store, info);
        if (resolved == null) {
            return null;
        }
        return new BlockLocation(
                resolved.chunk(), resolved.x(), resolved.y(), resolved.z());
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
