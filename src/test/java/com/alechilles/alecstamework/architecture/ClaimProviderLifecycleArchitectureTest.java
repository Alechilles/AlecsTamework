package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects fail-closed optional-claim provider lifecycle and reference-release wiring. */
class ClaimProviderLifecycleArchitectureTest {
    private static final Path JAVA_ROOT = Path.of("src/main/java/com/alechilles/alecstamework");

    @Test
    void bridgeFactoriesCannotReintroduceGenerationBlindStaticCaches() throws IOException {
        String questLines = Files.readString(JAVA_ROOT.resolve(
                "integration/questlinesclaims/QuestLinesClaimsBridge.java"
        ));
        String simpleClaims = Files.readString(JAVA_ROOT.resolve(
                "integration/simpleclaims/SimpleClaimsBreedingBridge.java"
        ));

        assertFalse(questLines.contains("cachedBridge"));
        assertFalse(simpleClaims.contains("cachedBridge"));
        assertFalse(questLines.contains("static volatile QuestLinesClaimsBridge"));
        assertFalse(simpleClaims.contains("static volatile SimpleClaimsBreedingBridge"));
    }

    @Test
    void missingRuntimeCannotBypassLifecycleAwareProviderRegistry() throws IOException {
        String service = Files.readString(JAVA_ROOT.resolve(
                "npc/actions/BreedingClaimLimitPolicyService.java"
        ));
        String selector = Files.readString(JAVA_ROOT.resolve(
                "integration/claims/ClaimIntegrationProviderSelector.java"
        ));

        assertTrue(service.contains("unavailableWithoutPopulationRuntime(providerRequest)"));
        assertFalse(service.contains("QuestLinesClaimsBridge.initialize()"));
        assertFalse(service.contains("SimpleClaimsBreedingBridge.initialize()"));
        assertFalse(selector.contains("ClaimIntegrationBridge select("));
    }

    @Test
    void pluginReloadInvalidationAndWeakReferenceReleaseRemainWired() throws IOException {
        String plugin = Files.readString(JAVA_ROOT.resolve("Tamework.java"));
        String questLinesProbe = Files.readString(JAVA_ROOT.resolve(
                "integration/questlinesclaims/QuestLinesClaimsProviderProbe.java"
        ));
        String simpleClaimsProbe = Files.readString(JAVA_ROOT.resolve(
                "integration/simpleclaims/SimpleClaimsProviderProbe.java"
        ));
        String damageRegistry = Files.readString(JAVA_ROOT.resolve(
                "damage/SimpleClaimsDamageCapabilityRegistry.java"
        ));

        assertTrue(plugin.contains("PluginSetupEvent.class"));
        assertTrue(plugin.contains("claimProviderLifecycleInvalidator::onPluginSetup"));
        assertTrue(plugin.contains("claimProviderRegistry().onPluginLifecycleChanged(provider)"));
        assertTrue(plugin.contains("damagePolicy.onRuntimeSettingsChanged()"));
        assertTrue(questLinesProbe.contains("WeakReference<ClaimProviderProbeResult>"));
        assertTrue(simpleClaimsProbe.contains("WeakReference<ClaimProviderProbeResult>"));
        assertTrue(damageRegistry.contains("WeakReference<Resolution>"));
    }

    @Test
    void capabilitySurfaceContainsOnlyImplementedCapabilities() throws IOException {
        String capabilities = Files.readString(JAVA_ROOT.resolve(
                "integration/claims/ClaimProviderCapability.java"
        ));

        assertFalse(capabilities.contains("MEMBERSHIP_ACCESS"));
    }
}
