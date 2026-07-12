package com.alechilles.alecstamework.integration.questlinesclaims;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Isolates cached method accessors and scalar conversion for the QuestLines Claims bridge.
 */
final class QuestLinesReflectionAccess {
    static final MethodFinder PUBLIC_METHODS = QuestLinesReflectionAccess::findMethod;

    private QuestLinesReflectionAccess() {
    }

    @Nullable
    static Throwable firstFailure(@Nonnull ReflectedValue... values) {
        for (ReflectedValue value : values) {
            if (value.failure() != null) {
                return value.failure();
            }
        }
        return null;
    }

    @Nullable
    static String normalizeText(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    @Nullable
    static UUID parseUuid(@Nullable Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String text = normalizeText(value);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    static Integer parseInteger(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = normalizeText(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nonnull
    static Accessor resolveAccessor(@Nonnull Class<?> type,
                                    @Nonnull MethodFinder methodFinder,
                                    @Nonnull String... methodNames) {
        for (String methodName : methodNames) {
            Method method = methodFinder.find(type, methodName);
            if (method != null) {
                return Accessor.found(methodName, method);
            }
        }
        return Accessor.missing();
    }

    @Nullable
    static Method findMethod(@Nonnull Class<?> type,
                             @Nonnull String name,
                             Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    static Throwable unwrapInvocation(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    @Nonnull
    static String extractMessage(@Nullable Throwable throwable) {
        Throwable unwrapped = unwrapInvocation(throwable);
        if (unwrapped == null) {
            return "unknown error";
        }
        String message = unwrapped.getMessage();
        return message == null || message.isBlank() ? unwrapped.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface MethodFinder {
        @Nullable
        Method find(@Nonnull Class<?> type,
                    @Nonnull String name,
                    @Nonnull Class<?>... parameterTypes);
    }

    record Accessor(boolean methodFound,
                    @Nullable String methodName,
                    @Nullable Method method) {
        @Nonnull
        static Accessor found(@Nonnull String methodName, @Nonnull Method method) {
            return new Accessor(true, methodName, method);
        }

        @Nonnull
        static Accessor missing() {
            return new Accessor(false, null, null);
        }

        @Nonnull
        ReflectedValue read(@Nonnull Object target) {
            if (!methodFound || method == null || methodName == null) {
                return ReflectedValue.missing();
            }
            try {
                return ReflectedValue.success(methodName, method.invoke(target));
            } catch (Throwable throwable) {
                return ReflectedValue.failure(methodName, unwrapInvocation(throwable));
            }
        }
    }

    record ReflectedValue(boolean methodFound,
                          @Nullable String methodName,
                          @Nullable Object value,
                          @Nullable Throwable failure) {
        @Nonnull
        static ReflectedValue success(@Nonnull String methodName, @Nullable Object value) {
            return new ReflectedValue(true, methodName, value, null);
        }

        @Nonnull
        static ReflectedValue failure(@Nonnull String methodName, @Nullable Throwable failure) {
            return new ReflectedValue(true, methodName, null, failure);
        }

        @Nonnull
        static ReflectedValue missing() {
            return new ReflectedValue(false, null, null, null);
        }
    }
}
