package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests global ownership-requirement policy resolution helpers. */
class OwnershipRequirementPolicyTest {

    @Test
    void spawnerCaptureAndSpawnUseGlobalDefaults() throws Exception {
        assertFalse(SpawnerOwnershipPolicyService.resolveCaptureRequireOwnerDefault(null));
        assertFalse(SpawnerOwnershipPolicyService.resolveSpawnRequireOwnerDefault(null));

        TwGlobalConfig globalConfig = TwGlobalConfig.defaultConfig();
        setField(globalConfig, "ownershipCaptureRequiresOwner", true);
        setField(globalConfig, "ownershipSpawnRequiresOwner", false);

        assertTrue(SpawnerOwnershipPolicyService.resolveCaptureRequireOwnerDefault(globalConfig));
        assertFalse(SpawnerOwnershipPolicyService.resolveSpawnRequireOwnerDefault(globalConfig));
    }

    @Test
    void commandLinkingUsesGlobalToggleAndDefaultsToRequireOwnerWhenMissingConfig() throws Exception {
        assertTrue(CommandLinkMutationService.resolveLinkingRequireOwner(null));
        assertTrue(CommandRecipientService.resolveLinkingRequireOwner(null));

        TwGlobalConfig globalConfig = TwGlobalConfig.defaultConfig();
        setField(globalConfig, "ownershipLinkingRequiresOwner", false);

        assertFalse(CommandLinkMutationService.resolveLinkingRequireOwner(globalConfig));
        assertFalse(CommandRecipientService.resolveLinkingRequireOwner(globalConfig));
    }

    @Test
    void spawnerOwnerRequirementSatisfactionGlobalToggleWins() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(SpawnerOwnershipPolicyService.isOwnerRequirementSatisfied(false, player, null));
        assertTrue(SpawnerOwnershipPolicyService.isOwnerRequirementSatisfied(false, player, other));
        assertFalse(SpawnerOwnershipPolicyService.isOwnerRequirementSatisfied(true, player, null));
        assertFalse(SpawnerOwnershipPolicyService.isOwnerRequirementSatisfied(true, player, other));
        assertTrue(SpawnerOwnershipPolicyService.isOwnerRequirementSatisfied(true, player, player));
    }

    @Test
    void ownerInteractionPolicyUsesGlobalTogglesByContext() throws Exception {
        TwGlobalConfig globalConfig = TwGlobalConfig.defaultConfig();
        setField(globalConfig, "ownershipCaptureRequiresOwner", false);
        setField(globalConfig, "ownershipLinkingRequiresOwner", true);
        setField(globalConfig, "ownershipInteractionRequiresOwner", false);

        assertFalse(
                OwnerInteractionListener.isOwnerRequiredForPolicy(
                        OwnerInteractionListener.InteractionOwnershipPolicy.CAPTURE,
                        globalConfig
                )
        );
        assertTrue(
                OwnerInteractionListener.isOwnerRequiredForPolicy(
                        OwnerInteractionListener.InteractionOwnershipPolicy.LINKING,
                        globalConfig
                )
        );
        assertFalse(
                OwnerInteractionListener.isOwnerRequiredForPolicy(
                        OwnerInteractionListener.InteractionOwnershipPolicy.INTERACTION,
                        globalConfig
                )
        );
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
