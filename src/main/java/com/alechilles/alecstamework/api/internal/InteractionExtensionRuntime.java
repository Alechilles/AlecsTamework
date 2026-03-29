package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementContext;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface InteractionExtensionRuntime {
    @Nonnull
    Optional<InteractionPresetDefinition> resolvePreset(@Nullable String presetId);

    boolean evaluateRequirement(@Nonnull InteractionRequirementSpec spec,
                                @Nonnull InteractionRequirementContext context);

    boolean applyEffect(@Nonnull InteractionEffectSpec spec,
                        @Nonnull InteractionEffectContext context);
}
