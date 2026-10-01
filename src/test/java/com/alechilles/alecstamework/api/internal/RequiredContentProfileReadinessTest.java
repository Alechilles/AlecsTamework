package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.RequiredContentProfileStatus;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredContentProfileReadinessTest {
    private static final String PROFILE = "runeteria:husbandry";
    private static final String PROVIDER = "runeteria:husbandry_provider";

    private final AdmissionProviderRegistry providers = new AdmissionProviderRegistry();

    @AfterEach
    void closeProviders() {
        providers.close();
    }

    private RequiredContentProfileStatus status(boolean configReady, String detail) {
        return new RequiredContentProfileReadiness(
                id -> new ManagedActivityConfigRegistry.Readiness(configReady, id, PROVIDER, 2, 7L, detail),
                providers).status(PROFILE);
    }

    private AutoCloseable register(int contractVersion) {
        return providers.register(PROVIDER, contractVersion, request -> CompletableFuture.completedFuture(null));
    }

    @Test
    void aLoadedProfileIsReadyOnlyWhileItsProviderIsRegistered() throws Exception {
        assertFalse(status(true, "ready").available());
        assertEquals("provider-not-registered", status(true, "ready").detail());

        AutoCloseable registration = register(2);
        RequiredContentProfileStatus ready = status(true, "ready");
        assertTrue(ready.available());
        assertEquals(PROFILE, ready.profileId());
        assertEquals(PROVIDER, ready.providerId());
        assertEquals(2, ready.providerContractVersion());
        assertEquals(7L, ready.configRevision());

        registration.close();
        assertFalse(status(true, "ready").available());
    }

    @Test
    void aProviderWithAnotherContractVersionIsNotReady() {
        register(1);

        RequiredContentProfileStatus status = status(true, "ready");

        assertFalse(status.available());
        assertEquals("provider-contract-mismatch", status.detail());
    }

    @Test
    void aProfileWhoseConfigIsNotUsableReportsTheConfigReason() {
        register(2);

        RequiredContentProfileStatus status = status(false, "population-group-revision-stale");

        assertFalse(status.available());
        assertEquals("population-group-revision-stale", status.detail());
    }
}
