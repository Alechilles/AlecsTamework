package com.alechilles.alecstamework.integration.simpleclaims;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Small reflection helpers shared by the independently probed SimpleClaims capabilities.
 */
final class SimpleClaimsReflection {
    static final String CLAIM_MANAGER_CLASS = "com.buuz135.simpleclaims.claim.ClaimManager";
    static final String CHUNK_INFO_CLASS = "com.buuz135.simpleclaims.claim.chunk.ChunkInfo";
    static final String PARTY_INFO_CLASS = "com.buuz135.simpleclaims.claim.party.PartyInfo";

    private SimpleClaimsReflection() {
    }

    @Nonnull
    static Class<?> load(@Nonnull ClassLoader classLoader, @Nonnull String className) throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    @Nonnull
    static Method requiredMethod(@Nonnull Class<?> type,
                                 @Nonnull String methodName,
                                 @Nonnull Class<?>... parameterTypes) throws NoSuchMethodException {
        return type.getMethod(methodName, parameterTypes);
    }

    @Nullable
    static Method optionalMethod(@Nonnull Class<?> type,
                                 @Nonnull String methodName,
                                 @Nonnull Class<?>... parameterTypes) {
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    static Throwable unwrap(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    @Nonnull
    static String message(@Nullable Throwable throwable) {
        Throwable unwrapped = unwrap(throwable);
        if (unwrapped == null) {
            return "unknown error";
        }
        String message = unwrapped.getMessage();
        return message == null || message.isBlank() ? unwrapped.getClass().getSimpleName() : message;
    }
}
