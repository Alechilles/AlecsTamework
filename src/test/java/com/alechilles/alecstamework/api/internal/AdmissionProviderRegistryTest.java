package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.AdmissionProviderApi;
import com.alechilles.alecstamework.api.PopulationAdmissionProvider;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Protects provider identity, failure translation, and registration lifecycle. */
class AdmissionProviderRegistryTest {
    @Test
    void duplicateRegistrationAndClosedIdentityFailClosed() throws Exception {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry();
        PopulationAdmissionProvider provider = request -> CompletableFuture.completedFuture(
                new PopulationAdmissionProviderDecision(
                        PopulationAdmissionProviderStatus.ALLOW,
                        "allowed",
                        Set.of(),
                        Map.of(),
                        7,
                        9
                )
        );

        AutoCloseable first = registry.register(" Animal.Policy ", 2, provider);
        assertThrows(
                IllegalStateException.class,
                () -> registry.register("animal.policy", 2, provider)
        );
        first.close();
        assertEquals(
                PopulationAdmissionProviderStatus.UNAVAILABLE,
                registry.evaluate("animal.policy", request()).toCompletableFuture()
                        .join().status()
        );
    }

    @Test
    void callbackFailuresBecomeUnavailable() {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry();
        registry.register("failure", 1, request -> {
            throw new IllegalStateException("provider-failed");
        });
        assertEquals(
                PopulationAdmissionProviderStatus.UNAVAILABLE,
                registry.evaluate("failure", request()).toCompletableFuture()
                        .join().status()
        );
    }

    private PopulationAdmissionProviderRequest request() {
        return null;
    }
}
