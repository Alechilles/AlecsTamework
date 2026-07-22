package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards startup and hot-reload wiring for the API 0.9 integration config families. */
class IntegrationConfigRuntimeWiringTest {
    private static final Path ENTRYPOINT = Path.of(
            "src/main/java/com/alechilles/alecstamework/Tamework.java");

    @Test
    void registersCaptureAndPopulationGroupAssetStoresBeforeRuntimeInitialization() throws Exception {
        String compact = Files.readString(ENTRYPOINT).replaceAll("\\s+", "");

        assertTrue(compact.contains("setPath(\"Tamework/CapturePolicies\")"));
        assertTrue(compact.contains("setCodec(TwCapturePolicyConfig.CODEC)"));
        assertTrue(compact.contains("setPath(\"Tamework/PopulationGroups\")"));
        assertTrue(compact.contains("setCodec(TwPopulationGroupConfig.CODEC)"));
        assertTrue(compact.indexOf("registerCapturePolicyAssets();")
                < compact.indexOf("TameworkPersistenceRuntime.initialize("));
        assertTrue(compact.indexOf("registerPopulationGroupAssets();")
                < compact.indexOf("TameworkPersistenceRuntime.initialize("));
    }

    @Test
    void hotReloadOnlyPublishesAfterAValidAtomicIndexSwap() throws Exception {
        String source = Files.readString(ENTRYPOINT);

        assertTrue(source.contains("TwCapturePolicyConfig.clearInheritanceFallbackCache();"));
        assertTrue(source.contains("capturePolicyRegistry.replace("));
        assertTrue(source.contains("if (rebuildCapturePolicyIndex() && !event.isInitial())"));
        assertTrue(source.contains("TwPopulationGroupConfig.clearInheritanceFallbackCache();"));
        assertTrue(source.contains("populationGroupRegistry.replace("));
        assertTrue(source.contains(
                "if (rebuildPopulationGroupIndex(event.isInitial()) && !event.isInitial())"));
        assertTrue(source.contains("retaining revision"),
                "Rejected assets must retain the prior compiled index and log its revision.");
    }

    @Test
    void initialPopulationGroupAssetLoadRetriesProvisioningRecoveryBeforeActivation() throws Exception {
        String compact = Files.readString(ENTRYPOINT).replaceAll("\\s+", "");

        assertTrue(compact.contains(
                "activatePopulationGroupsIfReady();"
                        + "retryCompanionProvisioningRecoveryIfPopulationGroupsReady();"
                        + "activateCompanionProvisioningIfReady();"),
                "Population-group readiness must retry provisioning recovery before capability activation.");
        assertTrue(compact.contains(
                "if(companionProvisioningRecoveryReady"
                        + "||!populationGroupRecoveryReady"
                        + "||ownerPopulationRuntime==null"
                        + "||!ownerPopulationRuntime.populationGroupsReady()"),
                "The retry must remain idempotent and gated by authoritative population readiness.");
        assertTrue(compact.contains(
                "retryCompanionProvisioningRecoveryIfPopulationGroupsReady();"
                        + "activateCompanionProvisioningIfReady();"),
                "The canonical owner-recovery callback must use the same guarded retry path.");
    }
}
