package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectHandler;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementContext;
import com.alechilles.alecstamework.api.InteractionRequirementHandler;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class InteractionExtensionRegistry implements InteractionExtensionApi, InteractionExtensionRuntime {
    @Nullable
    private final HytaleLogger logger;
    private final ConcurrentHashMap<String, InteractionRequirementHandler> requirementHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InteractionEffectHandler> effectHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InteractionPresetDefinition> presets = new ConcurrentHashMap<>();

    public InteractionExtensionRegistry(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    @Nonnull
    public AutoCloseable registerRequirement(@Nonnull String id, @Nonnull InteractionRequirementHandler handler) {
        String normalizedId = requireNormalizedId(id, "requirement");
        InteractionRequirementHandler normalizedHandler = Objects.requireNonNull(handler, "handler");
        requirementHandlers.put(normalizedId, normalizedHandler);
        return () -> requirementHandlers.remove(normalizedId, normalizedHandler);
    }

    @Override
    @Nonnull
    public AutoCloseable registerEffect(@Nonnull String id, @Nonnull InteractionEffectHandler handler) {
        String normalizedId = requireNormalizedId(id, "effect");
        InteractionEffectHandler normalizedHandler = Objects.requireNonNull(handler, "handler");
        effectHandlers.put(normalizedId, normalizedHandler);
        return () -> effectHandlers.remove(normalizedId, normalizedHandler);
    }

    @Override
    @Nonnull
    public AutoCloseable registerPreset(@Nonnull InteractionPresetDefinition preset) {
        InteractionPresetDefinition normalizedPreset = Objects.requireNonNull(preset, "preset");
        String normalizedId = requireNormalizedId(normalizedPreset.id(), "preset");
        presets.put(normalizedId, normalizedPreset);
        return () -> presets.remove(normalizedId, normalizedPreset);
    }

    @Override
    @Nonnull
    public Optional<InteractionPresetDefinition> getPreset(@Nullable String id) {
        return resolvePreset(id);
    }

    @Override
    @Nonnull
    public Set<String> listRequirementIds() {
        return Set.copyOf(requirementHandlers.keySet());
    }

    @Override
    @Nonnull
    public Set<String> listEffectIds() {
        return Set.copyOf(effectHandlers.keySet());
    }

    @Override
    @Nonnull
    public Set<String> listPresetIds() {
        return Set.copyOf(presets.keySet());
    }

    @Override
    @Nonnull
    public Optional<InteractionPresetDefinition> resolvePreset(@Nullable String presetId) {
        String normalized = normalizeId(presetId);
        return normalized == null ? Optional.empty() : Optional.ofNullable(presets.get(normalized));
    }

    @Override
    public boolean evaluateRequirement(@Nonnull InteractionRequirementSpec spec,
                                       @Nonnull InteractionRequirementContext context) {
        InteractionRequirementSpec normalizedSpec = Objects.requireNonNull(spec, "spec");
        InteractionRequirementContext normalizedContext = Objects.requireNonNull(context, "context");
        String normalizedId = normalizeId(normalizedSpec.id());
        if (normalizedId == null) {
            return false;
        }
        InteractionRequirementHandler handler = requirementHandlers.get(normalizedId);
        if (handler == null) {
            return false;
        }
        try {
            return handler.test(normalizedContext, normalizedSpec);
        } catch (Throwable error) {
            logExtensionFailure("requirement", normalizedId, error);
            return false;
        }
    }

    @Override
    public boolean applyEffect(@Nonnull InteractionEffectSpec spec,
                               @Nonnull InteractionEffectContext context) {
        InteractionEffectSpec normalizedSpec = Objects.requireNonNull(spec, "spec");
        InteractionEffectContext normalizedContext = Objects.requireNonNull(context, "context");
        String normalizedId = normalizeId(normalizedSpec.id());
        if (normalizedId == null) {
            return false;
        }
        InteractionEffectHandler handler = effectHandlers.get(normalizedId);
        if (handler == null) {
            return false;
        }
        try {
            return handler.apply(normalizedContext, normalizedSpec);
        } catch (Throwable error) {
            logExtensionFailure("effect", normalizedId, error);
            return false;
        }
    }

    @Nonnull
    private String requireNormalizedId(@Nullable String rawId, @Nonnull String typeName) {
        String normalized = normalizeId(rawId);
        if (normalized == null) {
            throw new IllegalArgumentException(typeName + " id must be nonblank.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeId(@Nullable String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return rawId.trim().toLowerCase(Locale.ROOT);
    }

    private void logExtensionFailure(@Nonnull String typeName,
                                     @Nonnull String id,
                                     @Nullable Throwable error) {
        if (logger == null) {
            return;
        }
        String message = "Tamework interaction extension " + typeName + " failed: " + id;
        if (error == null) {
            logger.at(Level.WARNING).log(message);
        } else {
            logger.at(Level.WARNING).withCause(error).log(message);
        }
    }
}
