package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Regression coverage for worlds that intentionally disable all NPC behavior. */
class CompanionDestinationAdmissionPolicyTest {
    @Test
    void admitsWorldsThatAllowNpcBehavior() {
        assertEquals(
                CompanionDestinationAdmissionPolicy.Decision.ALLOWED,
                CompanionDestinationAdmissionPolicy.assess(false)
        );
    }

    @Test
    void rejectsWorldsThatFreezeEveryNpc() {
        assertEquals(
                CompanionDestinationAdmissionPolicy.Decision.NPCS_FROZEN,
                CompanionDestinationAdmissionPolicy.assess(true)
        );
    }
}
