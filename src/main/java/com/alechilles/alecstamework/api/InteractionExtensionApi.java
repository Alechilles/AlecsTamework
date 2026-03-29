package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface InteractionExtensionApi {
    @Nonnull
    AutoCloseable registerRequirement(@Nonnull String id, @Nonnull InteractionRequirementHandler handler);

    @Nonnull
    AutoCloseable registerEffect(@Nonnull String id, @Nonnull InteractionEffectHandler handler);

    @Nonnull
    AutoCloseable registerPreset(@Nonnull InteractionPresetDefinition preset);

    @Nonnull
    Optional<InteractionPresetDefinition> getPreset(@Nullable String id);

    @Nonnull
    Set<String> listRequirementIds();

    @Nonnull
    Set<String> listEffectIds();

    @Nonnull
    Set<String> listPresetIds();
}
