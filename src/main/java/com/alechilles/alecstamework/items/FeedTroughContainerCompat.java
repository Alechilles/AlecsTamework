package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-version bridge for feed-trough container block access.
 */
public final class FeedTroughContainerCompat {
    private static final String MODERN_ITEM_CONTAINER_BLOCK_CLASS =
            "com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock";

    @Nullable
    private static volatile ComponentType<ChunkStore, ? extends Component<ChunkStore>> modernItemContainerComponentType;
    private static volatile boolean modernItemContainerComponentTypeResolved;
    private static final Map<NoArgMethodLookupKey, Method> NO_ARG_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<NoArgMethodLookupKey> MISSING_NO_ARG_METHOD_CACHE = ConcurrentHashMap.newKeySet();
    private static final Map<SingleArgMethodLookupKey, Method> SINGLE_ARG_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<SingleArgMethodLookupKey> MISSING_SINGLE_ARG_METHOD_CACHE = ConcurrentHashMap.newKeySet();
    private static final Map<FieldLookupKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<FieldLookupKey> MISSING_FIELD_CACHE = ConcurrentHashMap.newKeySet();

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
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(x, y, z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        return chunkStore.getComponent(blockRef, castComponentType(componentType));
    }

    @Nullable
    private static ComponentType<ChunkStore, ? extends Component<ChunkStore>> resolveModernComponentType() {
        if (modernItemContainerComponentTypeResolved) {
            return modernItemContainerComponentType;
        }
        synchronized (FeedTroughContainerCompat.class) {
            if (modernItemContainerComponentTypeResolved) {
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
            modernItemContainerComponentTypeResolved = true;
            return modernItemContainerComponentType;
        }
    }

    static void clearModernComponentTypeCacheForTests() {
        synchronized (FeedTroughContainerCompat.class) {
            modernItemContainerComponentType = null;
            modernItemContainerComponentTypeResolved = false;
            NO_ARG_METHOD_CACHE.clear();
            MISSING_NO_ARG_METHOD_CACHE.clear();
            SINGLE_ARG_METHOD_CACHE.clear();
            MISSING_SINGLE_ARG_METHOD_CACHE.clear();
            FIELD_CACHE.clear();
            MISSING_FIELD_CACHE.clear();
        }
    }

    static boolean isModernComponentTypeResolvedForTests() {
        return modernItemContainerComponentTypeResolved;
    }

    @Nullable
    static ComponentType<ChunkStore, ? extends Component<ChunkStore>> resolveModernComponentTypeForTests() {
        return resolveModernComponentType();
    }

    @Nullable
    private static Object invokeNoArg(@Nonnull Object target, @Nonnull String methodName) {
        Method method = resolveNoArgMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private static Object readField(@Nonnull Object target, @Nonnull String fieldName) {
        Field field = resolveField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static boolean invokeSingleArg(@Nullable Object target,
                                           @Nonnull String methodName,
                                           @Nullable Object argument) {
        if (target == null) {
            return false;
        }
        Method method = resolveSingleArgMethod(target.getClass(), methodName, argument);
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
    private static Method resolveNoArgMethod(@Nonnull Class<?> type, @Nonnull String methodName) {
        NoArgMethodLookupKey key = new NoArgMethodLookupKey(type, methodName);
        Method cached = NO_ARG_METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_NO_ARG_METHOD_CACHE.contains(key)) {
            return null;
        }
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 0) {
                continue;
            }
            Method existing = NO_ARG_METHOD_CACHE.putIfAbsent(key, method);
            return existing != null ? existing : method;
        }
        MISSING_NO_ARG_METHOD_CACHE.add(key);
        return null;
    }

    @Nullable
    private static Method resolveSingleArgMethod(@Nonnull Class<?> type,
                                                 @Nonnull String methodName,
                                                 @Nullable Object argument) {
        Class<?> argumentClass = argument != null ? argument.getClass() : null;
        SingleArgMethodLookupKey key = new SingleArgMethodLookupKey(type, methodName, argumentClass);
        Method cached = SINGLE_ARG_METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_SINGLE_ARG_METHOD_CACHE.contains(key)) {
            return null;
        }
        Method resolved = findCompatibleSingleArgMethod(type, methodName, argumentClass);
        if (resolved == null) {
            MISSING_SINGLE_ARG_METHOD_CACHE.add(key);
            return null;
        }
        Method existing = SINGLE_ARG_METHOD_CACHE.putIfAbsent(key, resolved);
        return existing != null ? existing : resolved;
    }

    @Nullable
    private static Method findCompatibleSingleArgMethod(@Nonnull Class<?> type,
                                                        @Nonnull String methodName,
                                                        @Nullable Class<?> argumentClass) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (argumentClass == null || parameterType.isAssignableFrom(argumentClass)) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Field resolveField(@Nonnull Class<?> targetClass, @Nonnull String fieldName) {
        FieldLookupKey key = new FieldLookupKey(targetClass, fieldName);
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_FIELD_CACHE.contains(key)) {
            return null;
        }
        Class<?> type = targetClass;
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equals(fieldName)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (SecurityException ignored) {
                    MISSING_FIELD_CACHE.add(key);
                    return null;
                }
                Field existing = FIELD_CACHE.putIfAbsent(key, field);
                return existing != null ? existing : field;
            }
            type = type.getSuperclass();
        }
        MISSING_FIELD_CACHE.add(key);
        return null;
    }

    private record NoArgMethodLookupKey(Class<?> targetClass, String methodName) {
    }

    private record SingleArgMethodLookupKey(Class<?> targetClass,
                                            String methodName,
                                            @Nullable Class<?> argumentClass) {
    }

    private record FieldLookupKey(Class<?> targetClass, String fieldName) {
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
