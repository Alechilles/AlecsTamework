package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;

public record InteractionPresetDefinition(String id,
                                          List<InteractionRequirementSpec> requirements,
                                          List<InteractionEffectSpec> effects) {
    public InteractionPresetDefinition {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Preset id must be nonblank.");
        }
        requirements = requirements == null
                ? List.of()
                : requirements.stream().filter(Objects::nonNull).toList();
        effects = effects == null
                ? List.of()
                : effects.stream().filter(Objects::nonNull).toList();
    }
}
