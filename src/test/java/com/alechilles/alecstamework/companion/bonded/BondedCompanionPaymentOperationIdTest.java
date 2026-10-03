package com.alechilles.alecstamework.persistence.operation;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for canonical bonded-revival payment identities. */
class BondedCompanionPaymentOperationIdTest {
    private static final UUID OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void delimiterAmbiguityCannotCollideAndCanonicalInputsAreStable() {
        String namespaceContainsDelimiter = paymentId(
                "panel:revive", "request", OWNER, "roster-a", "profile-a", 4L);
        String keyContainsDelimiter = paymentId(
                "panel", "revive:request", OWNER, "roster-a", "profile-a", 4L);

        assertNotEquals(namespaceContainsDelimiter, keyContainsDelimiter);
        assertEquals(namespaceContainsDelimiter, paymentId(
                " panel:revive ", " request ", OWNER,
                " roster-a ", " profile-a ", 4L));
    }

    @Test
    void canonicalIdentityBindsOwnerRosterProfileAndExpectedRevision() {
        String baseline = paymentId(
                "panel", "request", OWNER, "roster-a", "profile-a", 4L);

        assertEquals(5, Set.of(
                baseline,
                paymentId("panel", "request", OTHER_OWNER,
                        "roster-a", "profile-a", 4L),
                paymentId("panel", "request", OWNER,
                        "roster-b", "profile-a", 4L),
                paymentId("panel", "request", OWNER,
                        "roster-a", "profile-b", 4L),
                paymentId("panel", "request", OWNER,
                        "roster-a", "profile-a", 5L)
        ).size());
    }

    @Test
    void legacyAdapterRecoversOnlyTheHistoricalFlattenedKey() {
        String operationId = paymentId(
                "panel", "request-42", OWNER, "roster-a", "profile-a", 4L);

        assertEquals("panel:request-42",
                BondedCompanionPaymentOperationId.legacyOperationKey(operationId));
        assertEquals("legacy:pending",
                BondedCompanionPaymentOperationId.legacyOperationKey(
                        "legacy:pending"));
    }

    @Test
    void restartIdentityRoundTripsAndRejectsTampering() {
        String operationId = paymentId(
                "panel:namespace", "request:key", OWNER,
                "roster:a", "profile:a", -4L);

        BondedCompanionPaymentOperationId.Identity identity =
                BondedCompanionPaymentOperationId.parse(operationId)
                        .orElseThrow();

        assertEquals("panel:namespace", identity.callerNamespace());
        assertEquals("request:key", identity.idempotencyKey());
        assertEquals(OWNER, identity.ownerUuid());
        assertEquals("roster:a", identity.rosterId());
        assertEquals("profile:a", identity.profileId());
        assertEquals(-4L, identity.expectedRevision());
        assertEquals(operationId, identity.operationId());
        assertTrue(BondedCompanionPaymentOperationId.parse(operationId)
                .isPresent());
        assertFalse(BondedCompanionPaymentOperationId.parse(
                operationId.substring(0, operationId.length() - 1) + "0")
                .isPresent());
    }

    private String paymentId(
            String callerNamespace,
            String idempotencyKey,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            long expectedRevision
    ) {
        return BondedCompanionPaymentOperationId.create(
                callerNamespace, idempotencyKey, ownerUuid, rosterId,
                profileId, expectedRevision);
    }
}
