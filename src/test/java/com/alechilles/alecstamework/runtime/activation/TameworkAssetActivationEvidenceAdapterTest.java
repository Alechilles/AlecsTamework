package com.alechilles.alecstamework.runtime.activation;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for effective role and item evidence adaptation. */
class TameworkAssetActivationEvidenceAdapterTest {
    @Test
    void disabledRoleConfigDoesNotActivate() {
        TameworkEffectiveAssetFact fact = TameworkAssetActivationEvidenceAdapter.roleConfigs(
                TameworkRuntimeModule.BREEDING,
                "breeding",
                List.of(new RoleConfig(false, List.of("npc:goat"))),
                RoleConfig::enabled,
                RoleConfig::roles,
                ignored -> true
        );

        assertFalse(fact.hasEffectiveContent());
    }

    @Test
    void emptyRoleDefaultDoesNotActivate() {
        TameworkEffectiveAssetFact fact = TameworkAssetActivationEvidenceAdapter.roleConfigs(
                TameworkRuntimeModule.NEEDS,
                "needs",
                List.of(new RoleConfig(true, List.of())),
                RoleConfig::enabled,
                RoleConfig::roles,
                ignored -> true
        );

        assertFalse(fact.hasEffectiveContent());
    }

    @Test
    void enabledRoleAndExistingItemProduceEvidence() {
        TameworkEffectiveAssetFact roleFact = TameworkAssetActivationEvidenceAdapter.roleConfigs(
                TameworkRuntimeModule.BREEDING,
                "breeding",
                List.of(new RoleConfig(true, List.of("npc:goat", "npc:missing"))),
                RoleConfig::enabled,
                RoleConfig::roles,
                "npc:goat"::equals
        );
        TameworkEffectiveAssetFact itemFact = TameworkAssetActivationEvidenceAdapter.itemConfigs(
                TameworkRuntimeModule.COMMAND_ITEMS,
                "commands",
                List.of(new ItemConfig(true, List.of("Tamework_Command", "missing"))),
                ItemConfig::enabled,
                ItemConfig::items,
                "Tamework_Command"::equals
        );

        assertTrue(roleFact.hasEffectiveContent());
        assertTrue(roleFact.effectiveTargets().contains("npc:goat"));
        assertTrue(itemFact.hasEffectiveContent());
        assertTrue(itemFact.configuredItemIds().contains("Tamework_Command"));
    }

    @Test
    void passiveReusableConfigNeedsAConsumerProfileToActivate() {
        TameworkEffectiveAssetFact passiveOnly =
                TameworkAssetActivationEvidenceAdapter.enabledConsumerConfigs(
                        TameworkRuntimeModule.AVATAR_FLIGHT,
                        "avatar-flight",
                        List.of(new ConsumerConfig("Tamework_Avatar_Flight_Default", true)),
                        ConsumerConfig::enabled,
                        config -> !"Tamework_Avatar_Flight_Default".equals(config.id()),
                        ignored -> true
                );
        TameworkEffectiveAssetFact downstreamConsumer =
                TameworkAssetActivationEvidenceAdapter.enabledConsumerConfigs(
                        TameworkRuntimeModule.AVATAR_FLIGHT,
                        "avatar-flight",
                        List.of(
                                new ConsumerConfig("Tamework_Avatar_Flight_Default", true),
                                new ConsumerConfig("HyDragonNordicDrake", true)
                        ),
                        ConsumerConfig::enabled,
                        config -> !"Tamework_Avatar_Flight_Default".equals(config.id()),
                        ignored -> true
                );

        assertFalse(passiveOnly.hasEffectiveContent());
        assertTrue(downstreamConsumer.hasEffectiveContent());
    }

    private record RoleConfig(boolean enabled, List<String> roles) {
    }

    private record ItemConfig(boolean enabled, List<String> items) {
    }

    private record ConsumerConfig(String id, boolean enabled) {
    }
}
