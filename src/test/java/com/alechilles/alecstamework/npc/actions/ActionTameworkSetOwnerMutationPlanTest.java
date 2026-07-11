package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for NPC action ownership transition intent selection. */
class ActionTameworkSetOwnerMutationPlanTest {
    private static final UUID OWNER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void distinguishesFirstAcquisitionTransferAndIdempotentSameOwnerApply() {
        assertEquals(
                OwnerPopulationOperation.NEW_OWNERSHIP,
                ActionTameworkSetOwner.resolveOperation(null, OWNER_A)
        );
        assertEquals(
                OwnerPopulationOperation.OWNER_TRANSFER,
                ActionTameworkSetOwner.resolveOperation(OWNER_A, OWNER_B)
        );
        assertEquals(
                OwnerPopulationOperation.LIFECYCLE_CHANGE,
                ActionTameworkSetOwner.resolveOperation(OWNER_A, OWNER_A)
        );
    }
}
