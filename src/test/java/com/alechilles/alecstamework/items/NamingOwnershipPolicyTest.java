package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamingOwnershipPolicyTest {

    @Test
    void allowsAnyPlayerWhenOwnerIsNotRequired() {
        NamingRules rules = NamingRules.builder()
                .requireOwner(false)
                .build();
        assertTrue(NamingOwnershipPolicy.canName(UUID.randomUUID(), null, rules));
        assertTrue(NamingOwnershipPolicy.canName(UUID.randomUUID(), UUID.randomUUID(), rules));
    }

    @Test
    void requiresMatchingOwnerWhenOwnerIsRequired() {
        UUID ownerId = UUID.randomUUID();
        NamingRules rules = NamingRules.builder()
                .requireOwner(true)
                .allowUnownedWhenRequireOwner(false)
                .build();
        assertFalse(NamingOwnershipPolicy.canName(UUID.randomUUID(), null, rules));
        assertFalse(NamingOwnershipPolicy.canName(null, ownerId, rules));
        assertFalse(NamingOwnershipPolicy.canName(UUID.randomUUID(), ownerId, rules));
        assertTrue(NamingOwnershipPolicy.canName(ownerId, ownerId, rules));
    }

    @Test
    void allowsUnownedWhenConfiguredAlongsideRequireOwner() {
        UUID ownerId = UUID.randomUUID();
        NamingRules rules = NamingRules.builder()
                .requireOwner(true)
                .allowUnownedWhenRequireOwner(true)
                .build();
        assertTrue(NamingOwnershipPolicy.canName(UUID.randomUUID(), null, rules));
        assertTrue(NamingOwnershipPolicy.canName(ownerId, ownerId, rules));
        assertFalse(NamingOwnershipPolicy.canName(UUID.randomUUID(), ownerId, rules));
    }
}
