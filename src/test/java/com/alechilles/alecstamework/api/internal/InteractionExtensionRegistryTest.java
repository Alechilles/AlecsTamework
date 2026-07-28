package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.InteractionRequirementContext;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        registry.registerBuiltInRequirement("tamework:attachment_exchange_available", (context, spec) -> true);
        registry.registerBuiltInEffect("tamework:set_attachment_from_held_item", (context, spec) -> true);
        registry.registerBuiltInEffect("tamework:exchange_attachment", (context, spec) -> true);

        assertTrue(registry.listRequirementIds().contains("tamework:model_supports_attachment"));
        assertTrue(registry.listRequirementIds().contains("tamework:attachment_exchange_available"));
        assertTrue(registry.listEffectIds().contains("tamework:set_attachment_from_held_item"));
        assertTrue(registry.listEffectIds().contains("tamework:exchange_attachment"));
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

    @Test
    void captureRequirementsAreGenerationFencedAndFailClosed() throws Exception {
        InteractionExtensionRegistry registry = new InteractionExtensionRegistry(null);
        CaptureRequirementSpec spec = new CaptureRequirementSpec(
                "hydragon:special_encounter_capture_ready", "grounded_phase", List.of(), null
        );
        CaptureRequirementContext context = captureContext();

        long emptyGeneration = registry.captureRequirementGeneration();
        AutoCloseable registration = registry.registerCaptureRequirement(spec.id(), (candidate, configured) ->
                CaptureRequirementDecision.allow()
        );
        long registeredGeneration = registry.captureRequirementGeneration();

        assertTrue(registeredGeneration > emptyGeneration);
        assertEquals(
                "capture-requirement-generation-changed",
                registry.evaluateCaptureRequirement(spec, context, emptyGeneration).reason()
        );
        assertTrue(registry.evaluateCaptureRequirement(spec, context, registeredGeneration).allowed());
        assertTrue(registry.listCaptureRequirementIds().contains(spec.id()));

        registration.close();
        assertEquals(
                "capture-requirement-handler-missing",
                registry.evaluateCaptureRequirement(
                        spec, context, registry.captureRequirementGeneration()
                ).reason()
        );
    }

    @Test
    void captureRequirementMutationOrFailureDuringEvaluationDenies() throws Exception {
        InteractionExtensionRegistry registry = new InteractionExtensionRegistry(null);
        CaptureRequirementSpec spec = new CaptureRequirementSpec("hydragon:changing", null, List.of(), null);
        AtomicReference<AutoCloseable> registration = new AtomicReference<>();
        registration.set(registry.registerCaptureRequirement(spec.id(), (context, configured) -> {
            try {
                registration.get().close();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            return CaptureRequirementDecision.allow();
        }));

        CaptureRequirementDecision changed = registry.evaluateCaptureRequirement(
                spec, captureContext(), registry.captureRequirementGeneration()
        );
        assertFalse(changed.allowed());
        assertEquals("capture-requirement-generation-changed", changed.reason());

        registry.registerCaptureRequirement("hydragon:throwing", (context, configured) -> {
            throw new IllegalStateException("boom");
        });
        CaptureRequirementDecision failed = registry.evaluateCaptureRequirement(
                new CaptureRequirementSpec("hydragon:throwing", null, List.of(), null),
                captureContext(),
                registry.captureRequirementGeneration()
        );
        assertFalse(failed.allowed());
        assertEquals("capture-requirement-handler-failed", failed.reason());
    }

    @Test
    void captureRequirementRegistrationRequiresUniqueNamespacedIds() throws Exception {
        InteractionExtensionRegistry registry = new InteractionExtensionRegistry(null);
        assertThrows(IllegalArgumentException.class, () ->
                registry.registerCaptureRequirement("not_namespaced", (context, spec) -> CaptureRequirementDecision.allow()));
        try (AutoCloseable ignored = registry.registerCaptureRequirement(
                "hydragon:ready", (context, spec) -> CaptureRequirementDecision.allow())) {
            assertThrows(IllegalArgumentException.class, () ->
                    registry.registerCaptureRequirement(
                            "HYDRAGON:READY", (context, spec) -> CaptureRequirementDecision.allow()));
        }
    }

    private static CaptureRequirementContext captureContext() {
        return new CaptureRequirementContext(
                UUID.randomUUID(),
                CaptureRequirementPhase.FINAL_REVALIDATION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Hydra",
                "default",
                "Draconic_Stone",
                0.2D,
                CaptureRequirementContext.UNKNOWN_PROFILE_REVISION
        );
    }
}
