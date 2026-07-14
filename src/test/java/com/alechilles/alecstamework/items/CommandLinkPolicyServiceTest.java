package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
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

    /** Protects nearby-panel cards from showing Link for a live NPC already linked to the tool. */
    @Test
    void nearbyLinkStateUsesLiveToolAndCanonicalOwnerAuthority() {
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        TameworkCommandLinksComponent links =
                new TameworkCommandLinksComponent(owner, new String[]{"treat-bag"});

        assertTrue(CommandLinkPolicyService.isLinkedToTool(owner, links, owner, "treat-bag"));
        assertFalse(CommandLinkPolicyService.isLinkedToTool(owner, links, owner, "other-tool"));
        assertFalse(CommandLinkPolicyService.isLinkedToTool(otherOwner, links, owner, "treat-bag"));
    }
}
