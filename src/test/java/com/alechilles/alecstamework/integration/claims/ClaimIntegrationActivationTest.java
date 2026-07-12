package com.alechilles.alecstamework.integration.claims;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimIntegrationActivationTest {
    @Test
    void masterProviderRulesAndProtectTruthTableRemainIndependent() {
        List<ClaimProviderRequest> providers = List.of(
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.AUTO),
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.SIMPLE_CLAIMS),
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.QUESTLINES_CLAIMS),
                ClaimProviderRequest.forProvider(ClaimIntegrationProvider.OFF),
                ClaimProviderRequest.fromConfigValue("TownyMaybe")
        );

        for (boolean master : List.of(false, true)) {
            for (ClaimProviderRequest provider : providers) {
                for (boolean caps : List.of(false, true)) {
                    for (boolean requiresClaim : List.of(false, true)) {
                        for (boolean protect : List.of(false, true)) {
                            ClaimIntegrationActivation activation = ClaimIntegrationActivation.evaluate(
                                    master,
                                    provider,
                                    caps ? 2 : 0,
                                    0,
                                    requiresClaim,
                                    protect
                            );
                            boolean providerEnabled = provider.valid()
                                    && provider.provider() != ClaimIntegrationProvider.OFF;
                            assertEquals(
                                    master && providerEnabled && caps,
                                    activation.standardPopulationActive(),
                                    caseLabel(master, provider, caps, requiresClaim, protect)
                            );
                            assertEquals(
                                    master && providerEnabled && (caps || requiresClaim),
                                    activation.breedingPopulationActive(),
                                    caseLabel(master, provider, caps, requiresClaim, protect)
                            );
                            assertEquals(
                                    master && protect,
                                    activation.damageActive(),
                                    caseLabel(master, provider, caps, requiresClaim, protect)
                            );
                        }
                    }
                }
            }
        }
    }

    private static String caseLabel(boolean master,
                                    ClaimProviderRequest provider,
                                    boolean caps,
                                    boolean requiresClaim,
                                    boolean protect) {
        return "master=" + master
                + ", provider=" + provider.displayValue()
                + ", caps=" + caps
                + ", requiresClaim=" + requiresClaim
                + ", protect=" + protect;
    }
}
