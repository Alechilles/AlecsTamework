package com.alechilles.alecstamework.companion.bonded.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BondedCompanionExpiryWarningNameResolverTest {
    @Test
    void prefers_the_durable_profile_name_over_an_empty_live_role() {
        assertEquals("Nimbus", BondedCompanionExpiryWarningNameResolver.resolve(
                "Nimbus", null, "Empty Role"));
    }

    @Test
    void does_not_surface_the_empty_role_placeholder() {
        assertEquals("Companion", BondedCompanionExpiryWarningNameResolver.resolve(
                null, null, "Empty Role"));
    }
}
