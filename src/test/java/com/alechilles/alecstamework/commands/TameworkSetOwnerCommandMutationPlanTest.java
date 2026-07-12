package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for set-owner command transition intent selection. */
class TameworkSetOwnerCommandMutationPlanTest {
    private static final UUID OWNER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void routesNewTransferSameOwnerAndClearThroughExplicitOperations() {
        assertEquals(
                OwnerPopulationOperation.NEW_OWNERSHIP,
                TameworkSetOwnerCommand.resolveOperation(null, OWNER_A)
        );
        assertEquals(
                OwnerPopulationOperation.OWNER_TRANSFER,
                TameworkSetOwnerCommand.resolveOperation(OWNER_A, OWNER_B)
        );
        assertEquals(
                OwnerPopulationOperation.LIFECYCLE_CHANGE,
                TameworkSetOwnerCommand.resolveOperation(OWNER_A, OWNER_A)
        );
        assertEquals(
                OwnerPopulationOperation.OWNER_CLEAR,
                TameworkSetOwnerCommand.resolveOperation(OWNER_A, null)
        );
        assertEquals(
                OwnerPopulationOperation.ADMIN_FORCE,
                TameworkSetOwnerCommand.resolveOperation(OWNER_A, OWNER_B, true)
        );
    }

    @Test
    void parsesSelfCustomUuidAndClearWithoutConflatingInvalidInput() {
        assertEquals(OWNER_A, TameworkSetOwnerCommand.parseOwner(null, OWNER_A));
        assertEquals(OWNER_A, TameworkSetOwnerCommand.parseOwner("  ", OWNER_A));
        assertEquals(OWNER_B, TameworkSetOwnerCommand.parseOwner(OWNER_B.toString(), OWNER_A));
        assertNull(TameworkSetOwnerCommand.parseOwner("clear", OWNER_A));
        assertNull(TameworkSetOwnerCommand.parseOwner("none", OWNER_A));
        assertNull(TameworkSetOwnerCommand.parseOwner("not-a-uuid", OWNER_A));
    }
}
