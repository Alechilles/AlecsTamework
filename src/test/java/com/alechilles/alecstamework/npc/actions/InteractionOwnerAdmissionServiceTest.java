package com.alechilles.alecstamework.npc.actions;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers strict custom-owner parsing so a display name can never imply an owner clear. */
class InteractionOwnerAdmissionServiceTest {
    @Test
    void customOwnerRequiresValidUuid() {
        assertNull(InteractionOwnerAdmissionService.parseCustomOwnerUuid(null));
        assertNull(InteractionOwnerAdmissionService.parseCustomOwnerUuid("  "));
        assertNull(InteractionOwnerAdmissionService.parseCustomOwnerUuid("not-a-uuid"));

        UUID ownerId = UUID.randomUUID();
        assertEquals(ownerId, InteractionOwnerAdmissionService.parseCustomOwnerUuid(
                " " + ownerId + " "
        ));
    }
}
