package com.alechilles.alecstamework.npc.progression;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Reads an optional model attachment API and caches both present and absent methods by model class.
 */
final class CompanionModelAttachmentAccessor {
    private final ClassValue<Optional<Method>> methodsByClass;

    CompanionModelAttachmentAccessor(@Nonnull MethodResolver resolver) {
        MethodResolver requiredResolver = Objects.requireNonNull(resolver, "resolver");
        this.methodsByClass = new ClassValue<>() {
            @Override
            protected Optional<Method> computeValue(Class<?> type) {
                try {
                    return Optional.ofNullable(requiredResolver.resolve(type));
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    return Optional.empty();
                }
            }
        };
    }

    @Nonnull
    Map<?, ?> read(@Nonnull Object model) {
        Optional<Method> method = methodsByClass.get(model.getClass());
        if (method.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Object value = method.get().invoke(model);
            if (value instanceof Map<?, ?> map) {
                return map;
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return Collections.emptyMap();
        }
        return Collections.emptyMap();
    }

    @FunctionalInterface
    interface MethodResolver {
        Method resolve(Class<?> type) throws ReflectiveOperationException;
    }
}
