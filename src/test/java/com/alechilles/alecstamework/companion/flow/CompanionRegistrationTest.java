package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionRegistrationTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static CompanionTransitions.BodyFacts body() {
        return new CompanionTransitions.BodyFacts(NPC, UUID.randomUUID(), "Alec", "Tamed_Sheep", null, "default",
                0, 0, 0, List.of(), CompanionSummary.EMPTY);
    }

    @Test
    void aSecondTameOfTheSameBodyInOneTickIsIgnored() {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        LoadedBodies<String> loaded = new LoadedBodies<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(CompanionRegistration.register(index, loaded,
                CompanionTransitions.newLive(first, 0, body()), "ref", r -> null).registered());
        // The duplicate wins over the caps: the existing record must not count against its own body.
        CompanionRegistration.Outcome duplicate = CompanionRegistration.register(index, loaded,
                CompanionTransitions.newLive(second, 0, body()), "ref", r -> CompanionAdmission.Refusal.OWNED);
        assertFalse(duplicate.registered());
        assertNull(duplicate.refusal());

        assertEquals(first, index.byNpcUuid(NPC).profileId());
        assertEquals("ref", loaded.get(first));
        assertEquals(null, index.get(second));
    }

    @Test
    void aTameRefusedByTheCapsRegistersNothing() {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        LoadedBodies<String> loaded = new LoadedBodies<>();
        UUID profile = UUID.randomUUID();

        CompanionRegistration.Outcome outcome = CompanionRegistration.register(index, loaded,
                CompanionTransitions.newLive(profile, 0, body()), "ref", r -> CompanionAdmission.Refusal.GROUP_OWNED);

        assertFalse(outcome.registered());
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED, outcome.refusal());
        assertNull(index.get(profile));
        assertNull(loaded.get(profile));
    }
}
