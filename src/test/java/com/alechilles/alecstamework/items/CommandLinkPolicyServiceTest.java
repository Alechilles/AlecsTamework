package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
