package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
    @Test
    void aRosterMemberIsListedForItsFamilyButABondedCompanionWithTheSameRosterIdIsNot() {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        LoadedBodies<Ref<EntityStore>> loaded = new LoadedBodies<>();
        UUID owner = UUID.randomUUID();
        CompanionRecord member = CompanionTransitions.newLive(UUID.randomUUID(), 0, ownedBody(owner, UUID.randomUUID()))
                .toBuilder().rosterId("dragons").rosterSlot(-1).build();
        CompanionRecord bonded = CompanionTransitions.newLive(UUID.randomUUID(), 0, ownedBody(owner, UUID.randomUUID()))
                .toBuilder().rosterId("dragons").bonded(true).build();
        CompanionRecord otherFamily = CompanionTransitions.newLive(UUID.randomUUID(), 0,
                ownedBody(owner, UUID.randomUUID())).toBuilder().rosterId("wolves").rosterSlot(-1).build();
        for (CompanionRecord record : List.of(member, bonded, otherFamily)) {
            assertTrue(CompanionRegistration.register(index, new LoadedBodies<String>(), record, "ref", r -> null)
                    .registered());
        }

        List<CompanionRecord> members = new CompanionQueries(index, loaded).rosterMembers(owner, "dragons");

        assertEquals(List.of(member.profileId()), members.stream().map(CompanionRecord::profileId).toList());
    }

    private static CompanionTransitions.BodyFacts ownedBody(UUID owner, UUID npc) {
        return new CompanionTransitions.BodyFacts(npc, owner, "Alec", "Tamed_Dragon", null, "default",
                0, 0, 0, List.of(), CompanionSummary.EMPTY);
    }
}
