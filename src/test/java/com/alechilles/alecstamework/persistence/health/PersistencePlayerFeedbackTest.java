package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistencePlayerFeedbackTest {
    @Test
    void scopedDomainsUseDistinctLocalizedWording() {
        PersistenceMutationAvailabilityDecision denied = decision(
                PersistenceMutationAvailabilityStatus.QUARANTINED,
                "incident-abcdef"
        );

        assertTrue(resolve(PersistenceDomain.TAMING_OWNERSHIP, denied).startsWith("That companion"));
        assertTrue(resolve(PersistenceDomain.MANAGED_COOP_RELEASE, denied).startsWith("That coop"));
        assertTrue(resolve(PersistenceDomain.BREEDING_PAIRING, denied).startsWith("That breeding pair"));
    }

    @Test
    void incidentReferenceIsShortAndOnlyShownWhenIncidentBacked() {
        String incidentBacked = resolve(PersistenceDomain.TAMING_OWNERSHIP,
                decision(PersistenceMutationAvailabilityStatus.QUARANTINED,
                        "12345678-raw-identity-must-not-appear"));
        String authority = resolve(PersistenceDomain.TAMING_OWNERSHIP,
                decision(PersistenceMutationAvailabilityStatus.AUTHORITY_NOT_READY, null));

        assertTrue(incidentBacked.contains("12345678"));
        assertFalse(incidentBacked.contains("raw-identity"));
        assertFalse(authority.contains("Reference:"));
    }

    @Test
    void internalReasonAndPathsAreNeverExposed() {
        PersistenceMutationAvailabilityDecision denied = new PersistenceMutationAvailabilityDecision(
                PersistenceMutationAvailabilityStatus.GLOBAL_READ_ONLY,
                "SQLITE_IOERR C:/private/save/tamework.sqlite",
                null
        );

        String message = resolve(PersistenceDomain.STORAGE, denied);

        assertFalse(message.contains("SQLITE_IOERR"));
        assertFalse(message.contains("C:/"));
        assertEquals("Tamework persistence is temporarily read-only while storage authority is restored.",
                message);
    }

    private PersistenceMutationAvailabilityDecision decision(
            PersistenceMutationAvailabilityStatus status,
            String incidentId) {
        return new PersistenceMutationAvailabilityDecision(status, "internal_reason", incidentId);
    }

    private String resolve(PersistenceDomain domain,
                           PersistenceMutationAvailabilityDecision decision) {
        return PersistencePlayerFeedback.resolve(null, domain, decision);
    }
}
