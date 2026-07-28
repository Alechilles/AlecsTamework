package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for immutable replacement identity rows and mutation outcomes. */
class CompanionIdentityContractTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");

    @Test
    void validatesMetadataIntegrityWithoutRejectingSignedWorldTime() throws Exception {
        String json = "{\"worldTimeMs\":-5000}";
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE, "Companion", "role", json, Sha256Hash.ofUtf8(json), "world",
                -10_000, -5_000, -4_000, 0
        );

        assertEquals(-10_000, identity.createdAtMs());
        assertThrows(IllegalArgumentException.class, () -> new CompanionIdentity(
                PROFILE, null, null, json, new Sha256Hash("0".repeat(64)), null,
                0, 0, 0, 0
        ));
    }

    @Test
    void aliasesEnforceLeaseAndRetirementShape() {
        NpcAlias alias = new NpcAlias(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> new CompanionAlias(
                alias, PROFILE, 0, CompanionAlias.State.LEASED,
                null, 0, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new CompanionAlias(
                alias, PROFILE, 0, CompanionAlias.State.RETIRED,
                null, 0, null
        ));
    }

    @Test
    void mutationResultsNeverConfuseRejectionWithPersistedState() {
        PersistenceMutationResult<String> applied = PersistenceMutationResult.applied("row");
        PersistenceMutationResult<String> conflict =
                PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);

        assertEquals("row", applied.value());
        assertFalse(conflict.applied());
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceMutationResult<>(PersistenceMutationStatus.APPLIED, null));
    }
}
