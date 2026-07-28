package com.alechilles.alecstamework.companion.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for unambiguous companion and owner identity values. */
class CompanionIdentityValueTest {
    private static final String PROFILE = "20000000-0000-0000-0000-000000000001";
    private static final String NPC = "00000000-0000-0000-0000-000000000001";
    private static final String OWNER = "10000000-0000-0000-0000-000000000001";

    @Test
    void parsesCanonicalUuidRepresentations() {
        assertEquals(UUID.fromString(PROFILE), ProfileId.parse(" " + PROFILE + " ").value());
        assertEquals(UUID.fromString(NPC), NpcAlias.parse(NPC).value());
        assertEquals(UUID.fromString(OWNER), OwnerId.parse(OWNER).value());
        assertEquals(PROFILE, ProfileId.parse(PROFILE).toString());
    }

    @Test
    void distinctIdentityRolesCannotCompareEqual() {
        assertNotEquals(ProfileId.parse(PROFILE), NpcAlias.parse(PROFILE));
        assertNotEquals(ProfileId.parse(PROFILE), OwnerId.parse(PROFILE));
    }

    @Test
    void rejectsMissingAndMalformedValues() {
        assertThrows(IllegalArgumentException.class, () -> ProfileId.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> NpcAlias.parse(null));
        assertThrows(IllegalArgumentException.class, () -> OwnerId.parse("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileId(null));
    }
}
