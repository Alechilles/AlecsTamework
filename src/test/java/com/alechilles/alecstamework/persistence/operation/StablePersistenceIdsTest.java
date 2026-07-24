package com.alechilles.alecstamework.persistence.operation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Golden contracts protect stable released-operation intent identities from accidental drift. */
class StablePersistenceIdsTest {
    private static final String[] PARTS = {
            "profile-α", "world:one", "", "42"
    };

    @Test
    void goldenValuesRemainStableAcrossRestartsAndUnicodeInputs() {
        assertEquals(
                "2351db9a-2a43-87b6-aa69-1d2d03e1e11b",
                StablePersistenceIds.operationId(
                        "capture:v1", PARTS
                ).toString()
        );
        assertEquals(
                "intent:v1:"
                        + "e5c46a24d50bbb4702b5925883de8c816694634db6643313591f3ed471a8c628",
                StablePersistenceIds.idempotencyKey(
                        "capture:v1", PARTS
                ).toString()
        );
        assertEquals(
                "receipt:v1:"
                        + "cf61eb82952ae424326e9cb59bd50bee2c4cf1ca927ca706ba0abc762b3fd3b2",
                StablePersistenceIds.receipt(
                        "capture:v1", PARTS
                )
        );
        assertEquals(
                "5ef34f9e-2337-8498-94b0-66b098fcfea4",
                StablePersistenceIds.targetAlias(
                        "capture:v1", PARTS
                ).toString()
        );
    }

    @Test
    void namespacesPurposesAndPartBoundariesAreSeparated() {
        OperationId base = StablePersistenceIds.operationId(
                "capture:v1", "ab", "c"
        );

        assertNotEquals(
                base,
                StablePersistenceIds.operationId(
                        "coop_capture:v1", "ab", "c"
                )
        );
        assertNotEquals(
                base,
                StablePersistenceIds.operationId(
                        "capture:v1", "a", "bc"
                )
        );
        assertNotEquals(
                base.toString(),
                StablePersistenceIds.targetAlias(
                        "capture:v1", "ab", "c"
                ).toString()
        );
    }

    @Test
    void invalidNamespaceAndNullPartsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StablePersistenceIds.operationId(" ", "profile")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> StablePersistenceIds.receipt(
                        "capture:v1", (String[]) null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> StablePersistenceIds.idempotencyKey(
                        "capture:v1", "profile", null
                )
        );
    }
}
