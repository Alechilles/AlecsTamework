package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementContext;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionExtensionRegistryTest {
    @Test
    void registersAndResolvesRequirementEffectAndPreset() throws Exception {
        InteractionExtensionRegistry registry = new InteractionExtensionRegistry(null);
        AtomicBoolean requirementInvoked = new AtomicBoolean(false);
        AtomicBoolean effectInvoked = new AtomicBoolean(false);

        try (AutoCloseable requirementSubscription = registry.registerRequirement(
                "Example.Requirement",
                (context, spec) -> {
                    requirementInvoked.set(true);
                    return "Example.Requirement".equalsIgnoreCase(spec.id())
                            && "flag".equals(spec.param())
                            && spec.values().contains("x");
                }
        );
             AutoCloseable effectSubscription = registry.registerEffect(
                     "Example.Effect",
                     (context, spec) -> {
                         effectInvoked.set(true);
                         return "Example.Effect".equalsIgnoreCase(spec.id())
                                 && "mode".equals(spec.param())
                                 && spec.values().contains("follow");
                     }
             );
             AutoCloseable presetSubscription = registry.registerPreset(
                     new InteractionPresetDefinition(
                             "Example.Preset",
                             List.of(new InteractionRequirementSpec("Example.Requirement", "flag", List.of("x"), null)),
                             List.of(new InteractionEffectSpec("Example.Effect", "mode", List.of("follow"), null))
                     )
             )) {
            boolean requirementResult = registry.evaluateRequirement(
                    new InteractionRequirementSpec("example.requirement", "flag", List.of("x"), null),
                    new InteractionRequirementContext("cfg", 1, null, null, null, null, null, null, null, false)
            );
            boolean effectResult = registry.applyEffect(
                    new InteractionEffectSpec("example.effect", "mode", List.of("follow"), null),
                    new InteractionEffectContext("cfg", 1, null, null, null, null, null, null, null, false)
            );

            assertTrue(requirementResult);
            assertTrue(effectResult);
            assertTrue(requirementInvoked.get());
            assertTrue(effectInvoked.get());
            assertTrue(registry.resolvePreset("example.preset").isPresent());
            assertTrue(registry.listRequirementIds().contains("example.requirement"));
            assertTrue(registry.listEffectIds().contains("example.effect"));
            assertTrue(registry.listPresetIds().contains("example.preset"));
        }

        assertFalse(registry.resolvePreset("example.preset").isPresent());
        assertFalse(registry.evaluateRequirement(
                new InteractionRequirementSpec("example.requirement", null, List.of(), null),
                new InteractionRequirementContext("cfg", 1, null, null, null, null, null, null, null, false)
        ));
        assertFalse(registry.applyEffect(
                new InteractionEffectSpec("example.effect", null, List.of(), null),
                new InteractionEffectContext("cfg", 1, null, null, null, null, null, null, null, false)
        ));
    }

    @Test
    void extensionHandlerFailuresAreCaughtAndReturnFalse() {
        InteractionExtensionRegistry registry = new InteractionExtensionRegistry(null);
        registry.registerRequirement("throwing.req", (context, spec) -> {
            throw new IllegalStateException("boom");
        });
        registry.registerEffect("throwing.fx", (context, spec) -> {
            throw new IllegalStateException("boom");
        });

        assertFalse(registry.evaluateRequirement(
                new InteractionRequirementSpec("throwing.req", null, List.of(), null),
                new InteractionRequirementContext("cfg", 1, null, null, null, null, null, null, null, false)
        ));
        assertFalse(registry.applyEffect(
                new InteractionEffectSpec("throwing.fx", null, List.of(), null),
                new InteractionEffectContext("cfg", 1, null, null, null, null, null, null, null, false)
        ));
    }

    @Test
    void builtInsUseReservedNamespaceAndCannotBeOverridden() {
        InteractionExtensionRegistry registry = new InteractionExtensionRegistry(null);
        registry.registerBuiltInRequirement("tamework:model_supports_attachment", (context, spec) -> true);
        registry.registerBuiltInEffect("tamework:set_attachment_from_held_item", (context, spec) -> true);

        assertTrue(registry.listRequirementIds().contains("tamework:model_supports_attachment"));
        assertTrue(registry.listEffectIds().contains("tamework:set_attachment_from_held_item"));
        assertThrows(IllegalArgumentException.class, () -> registry.registerRequirement(
                "tamework:model_supports_attachment",
                (context, spec) -> false
        ));
        assertThrows(IllegalArgumentException.class, () -> registry.registerEffect(
                "tamework:set_attachment_from_held_item",
                (context, spec) -> false
        ));
        assertThrows(IllegalArgumentException.class, () -> registry.registerBuiltInEffect(
                "other:set_attachment",
                (context, spec) -> true
        ));
    }
}
