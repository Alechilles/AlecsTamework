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
        assertTrue(NamingOwnershipPolicy.canName(null, rules, false));
        assertTrue(NamingOwnershipPolicy.canName(UUID.randomUUID(), rules, false));
    }

    @Test
    void requiresRenameAccessWhenOwnerIsRequired() {
        UUID ownerId = UUID.randomUUID();
        NamingRules rules = NamingRules.builder()
                .requireOwner(true)
                .allowUnownedWhenRequireOwner(false)
                .build();
        assertFalse(NamingOwnershipPolicy.canName(null, rules, false));
        assertFalse(NamingOwnershipPolicy.canName(ownerId, rules, false));
        assertTrue(NamingOwnershipPolicy.canName(ownerId, rules, true));
    }

    @Test
    void allowsUnownedWhenConfiguredAlongsideRequireOwner() {
        UUID ownerId = UUID.randomUUID();
        NamingRules rules = NamingRules.builder()
                .requireOwner(true)
                .allowUnownedWhenRequireOwner(true)
                .build();
        assertTrue(NamingOwnershipPolicy.canName(null, rules, false));
        assertTrue(NamingOwnershipPolicy.canName(ownerId, rules, true));
        assertFalse(NamingOwnershipPolicy.canName(ownerId, rules, false));
    }
}
