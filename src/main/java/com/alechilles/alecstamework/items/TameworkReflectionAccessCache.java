package com.alechilles.alecstamework.items;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches optional reflection handles used by compatibility paths inside gameplay scans.
 */
final class TameworkReflectionAccessCache {
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<MemberKey, Object> NO_ARG_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<MemberKey, Object> FIELDS = new ConcurrentHashMap<>();

    private TameworkReflectionAccessCache() {
    }

    @Nullable
    static Object invokeNoArg(@Nullable Object target, @Nonnull String methodName) {
        if (target == null || methodName.isBlank()) {
            return null;
        }
        Method method = resolveNoArgMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    static Object readField(@Nullable Object target, @Nonnull String fieldName) {
        if (target == null || fieldName.isBlank()) {
            return null;
        }
        Field field = resolveField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method resolveNoArgMethod(@Nonnull Class<?> type, @Nonnull String methodName) {
        Object cached = NO_ARG_METHODS.computeIfAbsent(
                new MemberKey(type, methodName),
                TameworkReflectionAccessCache::lookupNoArgMethod
        );
        return cached == MISSING ? null : (Method) cached;
    }

    @Nonnull
    private static Object lookupNoArgMethod(@Nonnull MemberKey key) {
        try {
            return key.type().getMethod(key.name());
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return MISSING;
        }
    }

    @Nullable
    private static Field resolveField(@Nonnull Class<?> type, @Nonnull String fieldName) {
        Object cached = FIELDS.computeIfAbsent(
                new MemberKey(type, fieldName),
                TameworkReflectionAccessCache::lookupField
        );
        return cached == MISSING ? null : (Field) cached;
    }

    @Nonnull
    private static Object lookupField(@Nonnull MemberKey key) {
        Class<?> type = key.type();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(key.name());
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return MISSING;
            }
        }
        return MISSING;
    }

    static void clearForTests() {
        NO_ARG_METHODS.clear();
        FIELDS.clear();
    }

    static int methodCacheSizeForTests() {
        return NO_ARG_METHODS.size();
    }

    static int fieldCacheSizeForTests() {
        return FIELDS.size();
    }

    private record MemberKey(@Nonnull Class<?> type, @Nonnull String name) {
    }
}
