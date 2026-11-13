package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.SynchronousPopulationAdmissionProvider;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Protects provider identity, failure translation, and registration lifecycle. */
class AdmissionProviderRegistryTest {
    private static final PopulationAdmissionProviderDecision ALLOWED = new PopulationAdmissionProviderDecision(
            PopulationAdmissionProviderStatus.ALLOW, "allowed", Set.of(), Map.of(), 7, 7);

    @Test
    void duplicateRegistrationAndClosedIdentityFailClosed() throws Exception {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry();
        SynchronousPopulationAdmissionProvider provider = request -> ALLOWED;

        AutoCloseable first = registry.register(" Animal.Policy ", 1, provider);
        assertThrows(IllegalStateException.class, () -> registry.register("animal.policy", 1, provider));
        assertEquals(PopulationAdmissionProviderStatus.ALLOW, registry.decide(request("animal.policy")).status());

        first.close();
        assertEquals(PopulationAdmissionProviderStatus.UNAVAILABLE, registry.decide(request("animal.policy")).status());
        registry.close();
    }

    @Test
    void answersAreReturnedAndFailuresBecomeUnavailable() {
        try (AdmissionProviderRegistry registry = new AdmissionProviderRegistry()) {
            registry.register("allow", 1, ignored -> ALLOWED);
            registry.register("deny", 1, ignored -> new PopulationAdmissionProviderDecision(
                    PopulationAdmissionProviderStatus.DENY, "denied", Set.of(), Map.of(), 7, 7));
            registry.register("null-decision", 1, ignored -> null);
            registry.register("failure", 1, ignored -> {
                throw new IllegalStateException("provider-failed");
            });
            registry.register("other-contract", 2, ignored -> ALLOWED);

            assertEquals(PopulationAdmissionProviderStatus.ALLOW, registry.decide(request("allow")).status());
            assertEquals("denied", registry.decide(request("deny")).messageKey());
            assertEquals("provider-null-decision", registry.decide(request("null-decision")).messageKey());
            assertEquals("provider-exception", registry.decide(request("failure")).messageKey());
            assertEquals("provider-not-ready", registry.decide(request("other-contract")).messageKey());
            assertEquals("provider-not-ready", registry.decide(request("missing")).messageKey());
        }
    }

    @Test
    void closeStopsCallsAndAReplacementGetsANewGeneration() throws Exception {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry();
        AutoCloseable first = registry.register("generation", 1, ignored -> ALLOWED);
        String firstGeneration = registry.readiness("generation", 1).generationToken();
        first.close();
        registry.register("generation", 1, ignored -> ALLOWED);
        assertNotEquals(firstGeneration, registry.readiness("generation", 1).generationToken());

        registry.close();
        assertEquals("provider-not-ready", registry.decide(request("generation")).messageKey());
        assertThrows(IllegalStateException.class, () -> registry.register("late", 1, ignored -> ALLOWED));
    }

    private PopulationAdmissionProviderRequest request(String providerId) {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        null,
                        "20000000-0000-0000-0000-000000000401",
                        "provider-test"
                ),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                java.util.UUID.fromString(
                        "30000000-0000-0000-0000-000000000401"
                ),
                null,
                new PopulationAdmissionLocation("world", 0, 0),
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return new PopulationAdmissionProviderRequest(
                providerId,
                1,
                new PopulationAdmissionRequestV3(
                        new PopulationAdmissionRequestV2(
                                admission, "role", "world"
                        ),
                        "profile"
                ),
                "group",
                Set.of("group"),
                "gate",
                1,
                7
        );
    }
}
