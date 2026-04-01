package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cross-version bridge for feed-trough container block access.
 */
public final class FeedTroughContainerCompat {
    private static final String MODERN_ITEM_CONTAINER_BLOCK_CLASS =
            "com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock";

    @Nullable
    private static volatile ComponentType<ChunkStore, ? extends Component<ChunkStore>> modernItemContainerComponentType;

    private FeedTroughContainerCompat() {
    }

    @Nullable
    public static Object resolveContainerState(@Nullable WorldChunk chunk,
                                               @Nullable Store<ChunkStore> chunkStore,
                                               int x,
                                               int y,
                                               int z) {
        if (chunk == null) {
            return null;
        }
        return resolveModernContainerState(chunk, chunkStore, x, y, z);
    }

    @Nullable
    public static ItemContainer getItemContainer(@Nullable Object state) {
        if (state == null) {
            return null;
        }
        Object container = invokeNoArg(state, "getItemContainer");
        if (container instanceof ItemContainer itemContainer) {
            return itemContainer;
        }
        Object fieldContainer = readField(state, "itemContainer");
        if (fieldContainer instanceof ItemContainer itemContainer) {
            return itemContainer;
        }
        return null;
    }

    @Nullable
    public static String getDroplist(@Nullable Object state) {
        if (state == null) {
            return null;
        }
        Object droplist = invokeNoArg(state, "getDroplist");
        if (droplist instanceof String value) {
            return value;
        }
        return null;
    }

    public static boolean setDroplist(@Nullable Object state, @Nullable String droplist) {
        return invokeSingleArg(state, "setDroplist", droplist);
    }

    public static boolean setItemContainer(@Nullable Object state, @Nonnull ItemContainer container) {
        return invokeSingleArg(state, "setItemContainer", container);
    }

    @Nullable
    private static Object resolveModernContainerState(@Nonnull WorldChunk chunk,
                                                      @Nullable Store<ChunkStore> chunkStore,
                                                      int x,
                                                      int y,
                                                      int z) {
        if (chunkStore == null) {
            return null;
        }
        ComponentType<ChunkStore, ? extends Component<ChunkStore>> componentType = resolveModernComponentType();
        if (componentType == null) {
            return null;
        }
        int blockIndex = ChunkUtil.indexBlockInColumn(x, y, z);
        Ref<ChunkStore> blockRef = chunk.getBlockComponentChunk().getEntityReference(blockIndex);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        return chunkStore.getComponent(blockRef, castComponentType(componentType));
    }

    @Nullable
    private static ComponentType<ChunkStore, ? extends Component<ChunkStore>> resolveModernComponentType() {
        if (modernItemContainerComponentType != null) {
            return modernItemContainerComponentType;
        }
        synchronized (FeedTroughContainerCompat.class) {
            if (modernItemContainerComponentType != null) {
                return modernItemContainerComponentType;
            }
            try {
                Class<?> modernClass = Class.forName(MODERN_ITEM_CONTAINER_BLOCK_CLASS);
                Method getComponentTypeMethod = modernClass.getMethod("getComponentType");
                Object componentType = getComponentTypeMethod.invoke(null);
                if (componentType instanceof ComponentType<?, ?> resolvedType) {
                    modernItemContainerComponentType = castComponentTypeUnchecked(resolvedType);
                }
            } catch (ReflectiveOperationException ignored) {
                modernItemContainerComponentType = null;
            }
            return modernItemContainerComponentType;
        }
    }

    @Nullable
    private static Object invokeNoArg(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Object readField(@Nonnull Object target, @Nonnull String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean invokeSingleArg(@Nullable Object target,
                                           @Nonnull String methodName,
                                           @Nullable Object argument) {
        if (target == null) {
            return false;
        }
        Method method = findCompatibleSingleArgMethod(target.getClass(), methodName, argument);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target, argument);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    @Nullable
    private static Method findCompatibleSingleArgMethod(@Nonnull Class<?> type,
                                                        @Nonnull String methodName,
                                                        @Nullable Object argument) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (argument == null || parameterType.isAssignableFrom(argument.getClass())) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component<ChunkStore>> ComponentType<ChunkStore, T> castComponentType(
            @Nonnull ComponentType<ChunkStore, ? extends Component<ChunkStore>> type
    ) {
        return (ComponentType<ChunkStore, T>) type;
    }

    @SuppressWarnings("unchecked")
    private static ComponentType<ChunkStore, ? extends Component<ChunkStore>> castComponentTypeUnchecked(
            @Nonnull ComponentType<?, ?> type
    ) {
        return (ComponentType<ChunkStore, ? extends Component<ChunkStore>>) type;
    }
}
