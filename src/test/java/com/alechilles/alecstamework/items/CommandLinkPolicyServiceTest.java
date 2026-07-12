package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandLinkPolicyServiceTest {

    @Test
    void registeredRoleIdBeatsGenericRoleName() {
        assertEquals(
                "Cat_Bobtail_Pet",
                CommandLinkPolicyService.selectRoleId("Cat", 7, index -> "Cat_Bobtail_Pet")
        );
    }

    @Test
    void roleNameIsFallbackWhenRegisteredRoleIdIsMissing() {
        assertEquals(
                "Cat",
                CommandLinkPolicyService.selectRoleId("Cat", 7, index -> null)
        );
    }

    @Test
    void returnsNullWhenNoRoleIdentifierIsAvailable() {
        assertNull(CommandLinkPolicyService.selectRoleId(null, -1, index -> "Cat_Bobtail_Pet"));
    }

    @Test
    void linkedAuthorizationRequiresCanonicalLinkAndPlayerOwnerToMatch() {
        UUID owner = UUID.randomUUID();
        UUID previousOwner = UUID.randomUUID();

        assertTrue(CommandLinkPolicyService.isLinkAuthorized(owner, owner, owner, true));
        assertFalse(CommandLinkPolicyService.isLinkAuthorized(null, owner, owner, true));
        assertFalse(CommandLinkPolicyService.isLinkAuthorized(owner, previousOwner, owner, true));
        assertFalse(CommandLinkPolicyService.isLinkAuthorized(owner, owner, previousOwner, true));
        assertFalse(CommandLinkPolicyService.isLinkAuthorized(owner, owner, owner, false));
    }
}
