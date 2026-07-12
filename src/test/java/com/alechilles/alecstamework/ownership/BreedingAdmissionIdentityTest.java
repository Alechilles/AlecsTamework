package com.alechilles.alecstamework.ownership;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Regression coverage for crash-retry-stable breeding child identities. */
class BreedingAdmissionIdentityTest {
    @Test
    void sameAttemptAndChildReusesExactProfileAndNpcIdentity() {
        String attempt = "breeding:" + UUID.randomUUID();

        assertEquals(
                BreedingAdmissionIdentity.profileId(attempt, "child-0"),
                BreedingAdmissionIdentity.profileId(attempt, "child-0")
        );
        assertEquals(
                BreedingAdmissionIdentity.npcUuid(attempt, "child-0"),
                BreedingAdmissionIdentity.npcUuid(attempt, "child-0")
        );
    }

    @Test
    void childAndAttemptNamespacesCannotAlias() {
        String attempt = "breeding:" + UUID.randomUUID();

        assertNotEquals(
                BreedingAdmissionIdentity.npcUuid(attempt, "child-0"),
                BreedingAdmissionIdentity.npcUuid(attempt, "child-1")
        );
        assertNotEquals(
                BreedingAdmissionIdentity.profileId(attempt, "child-0"),
                BreedingAdmissionIdentity.npcUuid(attempt, "child-0").toString()
        );
    }
}
