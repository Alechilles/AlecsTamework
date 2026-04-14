package com.alechilles.alecstamework.npc.params;

import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Caches typed StdScope lookup shapes so hot paths do not use IllegalStateException as control flow.
 */
public final class StdScopeLookupCache {
    private final Map<StdScope, Map<String, StringKind>> stringKindsByScope = new WeakHashMap<>();
    private final Map<StdScope, Map<String, StringArrayKind>> stringArrayKindsByScope = new WeakHashMap<>();
    private final Map<StdScope, Map<String, SimpleKind>> booleanKindsByScope = new WeakHashMap<>();
    private final Map<StdScope, Map<String, SimpleKind>> numberKindsByScope = new WeakHashMap<>();
    private final Map<StdScope, Map<String, SimpleKind>> numberArrayKindsByScope = new WeakHashMap<>();

    @Nullable
    public String getString(@Nullable StdScope scope, @Nullable String paramName) {
        if (scope == null || paramName == null || paramName.isBlank()) {
            return null;
        }
        StringKind kind = getCachedKind(stringKindsByScope, scope, paramName);
        if (kind == null) {
            return resolveAndCacheString(scope, paramName);
        }
        try {
            return switch (kind) {
                case STRING -> normalizeString(scope.getStringSupplier(paramName).get());
                case MISSING -> null;
            };
        } catch (IllegalStateException ignored) {
            clearCachedKind(stringKindsByScope, scope, paramName);
            return resolveAndCacheString(scope, paramName);
        }
    }

    @Nullable
    public String[] getStringArrayOrString(@Nullable StdScope scope, @Nullable String paramName) {
        if (scope == null || paramName == null || paramName.isBlank()) {
            return null;
        }
        StringArrayKind kind = getCachedKind(stringArrayKindsByScope, scope, paramName);
        if (kind == null) {
            return resolveAndCacheStringArray(scope, paramName);
        }
        try {
            return switch (kind) {
                case STRING_ARRAY -> normalizeStringArray(scope.getStringArraySupplier(paramName).get());
                case STRING -> normalizeStringAsArray(scope.getStringSupplier(paramName).get());
                case MISSING -> null;
            };
        } catch (IllegalStateException ignored) {
            clearCachedKind(stringArrayKindsByScope, scope, paramName);
            return resolveAndCacheStringArray(scope, paramName);
        }
    }

    @Nullable
    public Boolean getBoolean(@Nullable StdScope scope, @Nullable String paramName) {
        if (scope == null || paramName == null || paramName.isBlank()) {
            return null;
        }
        SimpleKind kind = getCachedKind(booleanKindsByScope, scope, paramName);
        if (kind == null) {
            return resolveAndCacheBoolean(scope, paramName);
        }
        if (kind == SimpleKind.MISSING) {
            return null;
        }
        try {
            return scope.getBooleanSupplier(paramName).getAsBoolean();
        } catch (IllegalStateException ignored) {
            clearCachedKind(booleanKindsByScope, scope, paramName);
            return resolveAndCacheBoolean(scope, paramName);
        }
    }

    @Nullable
    public Double getNumber(@Nullable StdScope scope, @Nullable String paramName) {
        if (scope == null || paramName == null || paramName.isBlank()) {
            return null;
        }
        SimpleKind kind = getCachedKind(numberKindsByScope, scope, paramName);
        if (kind == null) {
            return resolveAndCacheNumber(scope, paramName);
        }
        if (kind == SimpleKind.MISSING) {
            return null;
        }
        try {
            return scope.getNumberSupplier(paramName).getAsDouble();
        } catch (IllegalStateException ignored) {
            clearCachedKind(numberKindsByScope, scope, paramName);
            return resolveAndCacheNumber(scope, paramName);
        }
    }

    @Nullable
    public double[] getNumberArray(@Nullable StdScope scope, @Nullable String paramName) {
        if (scope == null || paramName == null || paramName.isBlank()) {
            return null;
        }
        SimpleKind kind = getCachedKind(numberArrayKindsByScope, scope, paramName);
        if (kind == null) {
            return resolveAndCacheNumberArray(scope, paramName);
        }
        if (kind == SimpleKind.MISSING) {
            return null;
        }
        try {
            return scope.getNumberArraySupplier(paramName).get();
        } catch (IllegalStateException ignored) {
            clearCachedKind(numberArrayKindsByScope, scope, paramName);
            return resolveAndCacheNumberArray(scope, paramName);
        }
    }

    @Nullable
    private String resolveAndCacheString(StdScope scope, String paramName) {
        try {
            String value = normalizeString(scope.getStringSupplier(paramName).get());
            cacheKind(stringKindsByScope, scope, paramName, StringKind.STRING);
            return value;
        } catch (IllegalStateException ignored) {
            cacheKind(stringKindsByScope, scope, paramName, StringKind.MISSING);
            return null;
        }
    }

    @Nullable
    private String[] resolveAndCacheStringArray(StdScope scope, String paramName) {
        try {
            String[] values = normalizeStringArray(scope.getStringArraySupplier(paramName).get());
            cacheKind(stringArrayKindsByScope, scope, paramName, StringArrayKind.STRING_ARRAY);
            return values;
        } catch (IllegalStateException ignored) {
            // Fall through to scalar-string lookup.
        }
        try {
            String[] values = normalizeStringAsArray(scope.getStringSupplier(paramName).get());
            cacheKind(stringArrayKindsByScope, scope, paramName, StringArrayKind.STRING);
            return values;
        } catch (IllegalStateException ignored) {
            cacheKind(stringArrayKindsByScope, scope, paramName, StringArrayKind.MISSING);
            return null;
        }
    }

    @Nullable
    private Boolean resolveAndCacheBoolean(StdScope scope, String paramName) {
        try {
            BooleanSupplier supplier = scope.getBooleanSupplier(paramName);
            cacheKind(booleanKindsByScope, scope, paramName, SimpleKind.PRESENT);
            return supplier != null ? supplier.getAsBoolean() : null;
        } catch (IllegalStateException ignored) {
            cacheKind(booleanKindsByScope, scope, paramName, SimpleKind.MISSING);
            return null;
        }
    }

    @Nullable
    private Double resolveAndCacheNumber(StdScope scope, String paramName) {
        try {
            DoubleSupplier supplier = scope.getNumberSupplier(paramName);
            cacheKind(numberKindsByScope, scope, paramName, SimpleKind.PRESENT);
            return supplier != null ? supplier.getAsDouble() : null;
        } catch (IllegalStateException ignored) {
            cacheKind(numberKindsByScope, scope, paramName, SimpleKind.MISSING);
            return null;
        }
    }

    @Nullable
    private double[] resolveAndCacheNumberArray(StdScope scope, String paramName) {
        try {
            Supplier<double[]> supplier = scope.getNumberArraySupplier(paramName);
            cacheKind(numberArrayKindsByScope, scope, paramName, SimpleKind.PRESENT);
            return supplier != null ? supplier.get() : null;
        } catch (IllegalStateException ignored) {
            cacheKind(numberArrayKindsByScope, scope, paramName, SimpleKind.MISSING);
            return null;
        }
    }

    @Nullable
    private static String normalizeString(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Nullable
    private static String[] normalizeStringArray(@Nullable String[] values) {
        return values != null && values.length > 0 ? values : null;
    }

    @Nullable
    private static String[] normalizeStringAsArray(@Nullable String value) {
        String normalized = normalizeString(value);
        return normalized != null ? new String[] { normalized } : null;
    }

    private <T> void cacheKind(Map<StdScope, Map<String, T>> cache, StdScope scope, String paramName, T kind) {
        synchronized (cache) {
            cache.computeIfAbsent(scope, ignored -> new HashMap<>()).put(paramName, kind);
        }
    }

    @Nullable
    private <T> T getCachedKind(Map<StdScope, Map<String, T>> cache, StdScope scope, String paramName) {
        synchronized (cache) {
            Map<String, T> kindsByParam = cache.get(scope);
            return kindsByParam != null ? kindsByParam.get(paramName) : null;
        }
    }

    private <T> void clearCachedKind(Map<StdScope, Map<String, T>> cache, StdScope scope, String paramName) {
        synchronized (cache) {
            Map<String, T> kindsByParam = cache.get(scope);
            if (kindsByParam == null) {
                return;
            }
            kindsByParam.remove(paramName);
            if (kindsByParam.isEmpty()) {
                cache.remove(scope);
            }
        }
    }

    private enum StringKind {
        STRING,
        MISSING
    }

    private enum StringArrayKind {
        STRING_ARRAY,
        STRING,
        MISSING
    }

    private enum SimpleKind {
        PRESENT,
        MISSING
    }
}
